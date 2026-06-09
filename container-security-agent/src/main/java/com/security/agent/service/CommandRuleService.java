package com.security.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.security.agent.model.CommandRule;
import com.security.agent.model.RuleVerdict;
import com.security.agent.util.PathResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class CommandRuleService {

    private static final Logger log = LoggerFactory.getLogger(CommandRuleService.class);

    @Value("${security-agent.rules.directory:./rules}")
    private String rulesDir;

    @Value("${security-agent.rules.default-action:BLOCK}")
    private String defaultAction;

    private final ObjectMapper objectMapper;
    private volatile RuleSet activeRuleSet;
    private final Object lock = new Object();

    private final List<RuleVerdict> hitHistory = new ArrayList<>();
    private static final int MAX_HISTORY = 1000;

    public CommandRuleService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        this.rulesDir = PathResolver.resolve(rulesDir).toString();
        reloadRules();
    }

    public RuleSet reloadRules() {
        synchronized (lock) {
            List<CommandRule> allRules = new ArrayList<>();
            Path dir = Path.of(rulesDir);

            if (!Files.exists(dir)) {
                try {
                    Files.createDirectories(dir);
                } catch (IOException e) {
                    log.error("无法创建 Rules 目录: {}", rulesDir, e);
                }
            } else {
                try (Stream<Path> files = Files.list(dir)) {
                    files.filter(p -> p.toString().endsWith(".json"))
                            .forEach(p -> {
                                try {
                                    RuleSet rs = objectMapper.readValue(p.toFile(), RuleSet.class);
                                    if (rs.getRules() != null) {
                                        allRules.addAll(rs.getRules());
                                    }
                                    if (rs.getDefaultAction() != null) {
                                        this.defaultAction = rs.getDefaultAction();
                                    }
                                } catch (IOException e) {
                                    log.error("规则文件反序列化失败: {}", p, e);
                                }
                            });
                } catch (IOException e) {
                    log.error("Rules 目录扫描失败: {}", rulesDir, e);
                }
            }

            // 去重：相同 ruleId 只保留第一个
            Map<String, CommandRule> deduped = new LinkedHashMap<>();
            for (CommandRule rule : allRules) {
                deduped.putIfAbsent(rule.getRuleId(), rule);
            }

            // 编译 REGEX 类型的 Pattern
            List<CommandRule> compiledRules = deduped.values().stream()
                    .peek(r -> {
                        if ("REGEX".equals(r.getMatchType())) {
                            r.compile();
                        }
                    })
                    .sorted(Comparator.comparingInt(this::actionPriority))
                    .collect(Collectors.toList());

            activeRuleSet = new RuleSet();
            activeRuleSet.setDefaultAction(defaultAction);
            activeRuleSet.setRules(compiledRules);

            long blocked = compiledRules.stream().filter(r -> "BLOCK".equals(r.getAction())).count();
            long warned = compiledRules.stream().filter(r -> "WARN".equals(r.getAction())).count();
            long allowed = compiledRules.stream().filter(r -> "ALLOW".equals(r.getAction())).count();

            log.info("命令规则热加载完成，共加载 {} 条规则 (BLOCK: {}, WARN: {}, ALLOW: {})",
                    compiledRules.size(), blocked, warned, allowed);

            return activeRuleSet;
        }
    }

    private int actionPriority(CommandRule rule) {
        return switch (rule.getAction()) {
            case "BLOCK" -> 0;
            case "WARN" -> 1;
            case "ALLOW" -> 2;
            default -> 3;
        };
    }

    public RuleVerdict filter(String command, String taskId, String skillId) {
        RuleSet rules = activeRuleSet;
        if (rules == null || rules.getRules() == null || rules.getRules().isEmpty()) {
            rules = reloadRules();
        }

        List<RuleVerdict> allMatches = new ArrayList<>();

        for (CommandRule rule : rules.getRules()) {
            if (matches(command, rule)) {
                RuleVerdict v = RuleVerdict.builder()
                        .verdict(rule.getAction())
                        .matchedRuleId(rule.getRuleId())
                        .message(rule.getMessage() != null
                                ? rule.getMessage().replace("{{command}}", command)
                                : "")
                        .originalCommand(command)
                        .build();
                allMatches.add(v);
            }
        }

        RuleVerdict finalVerdict;

        if (allMatches.isEmpty()) {
            String defAction = rules.getDefaultAction() != null ? rules.getDefaultAction() : this.defaultAction;
            if ("BLOCK".equals(defAction)) {
                finalVerdict = RuleVerdict.builder()
                        .verdict("BLOCK")
                        .matchedRuleId(null)
                        .message("命令 [" + command + "] 未命中任何放行规则，被默认策略拦截")
                        .originalCommand(command)
                        .build();
                log.warn("[{}][{}] 规则未命中 (默认BLOCK): {}", taskId, skillId, command);
            } else {
                finalVerdict = RuleVerdict.builder()
                        .verdict("ALLOW")
                        .matchedRuleId(null)
                        .message("")
                        .originalCommand(command)
                        .build();
                log.debug("[{}][{}] 规则未命中 (默认ALLOW): {}", taskId, skillId, command);
            }
        } else {
            // BLOCK > WARN > ALLOW
            RuleVerdict block = allMatches.stream().filter(v -> "BLOCK".equals(v.getVerdict())).findFirst().orElse(null);
            RuleVerdict warn = allMatches.stream().filter(v -> "WARN".equals(v.getVerdict())).findFirst().orElse(null);
            RuleVerdict allow = allMatches.stream().filter(v -> "ALLOW".equals(v.getVerdict())).findFirst().orElse(null);

            if (block != null) finalVerdict = block;
            else if (warn != null) finalVerdict = warn;
            else finalVerdict = allow;

            // Log hits
            for (RuleVerdict v : allMatches) {
                switch (v.getVerdict()) {
                    case "BLOCK" -> log.error("[{}][{}] 规则命中 {} (BLOCK): {} — {}",
                            taskId, skillId, v.getMatchedRuleId(), command, v.getMessage());
                    case "WARN" -> log.warn("[{}][{}] 规则命中 {} (WARN): {} — {}",
                            taskId, skillId, v.getMatchedRuleId(), command, v.getMessage());
                    case "ALLOW" -> log.debug("[{}][{}] 规则命中 {} (ALLOW): {}",
                            taskId, skillId, v.getMatchedRuleId(), command);
                }
            }
        }

        // Record history
        synchronized (hitHistory) {
            hitHistory.add(finalVerdict);
            if (hitHistory.size() > MAX_HISTORY) {
                hitHistory.subList(0, hitHistory.size() - MAX_HISTORY).clear();
            }
        }

        return finalVerdict;
    }

    private boolean matches(String command, CommandRule rule) {
        return switch (rule.getMatchType()) {
            case "EXACT" -> command.trim().equals(rule.getPattern());
            case "PREFIX" -> command.trim().startsWith(rule.getPattern());
            case "REGEX" -> {
                Pattern p = rule.getCompiledPattern();
                yield p != null && p.matcher(command).find();
            }
            default -> false;
        };
    }

    public RuleSet getActiveRules() {
        if (activeRuleSet == null) {
            reloadRules();
        }
        return activeRuleSet;
    }

    public List<RuleVerdict> getHitHistory() {
        synchronized (hitHistory) {
            return new ArrayList<>(hitHistory);
        }
    }

    // Inner class for rule set deserialization
    public static class RuleSet {
        private String ruleSetId;
        private String version;
        private String description;
        private String defaultAction;
        private boolean allowlistMode;
        private List<CommandRule> rules;

        public String getRuleSetId() { return ruleSetId; }
        public void setRuleSetId(String ruleSetId) { this.ruleSetId = ruleSetId; }

        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public String getDefaultAction() { return defaultAction; }
        public void setDefaultAction(String defaultAction) { this.defaultAction = defaultAction; }

        public boolean isAllowlistMode() { return allowlistMode; }
        public void setAllowlistMode(boolean allowlistMode) { this.allowlistMode = allowlistMode; }

        public List<CommandRule> getRules() { return rules; }
        public void setRules(List<CommandRule> rules) { this.rules = rules; }
    }
}
