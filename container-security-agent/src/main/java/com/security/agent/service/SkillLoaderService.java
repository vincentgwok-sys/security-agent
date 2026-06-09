package com.security.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.security.agent.model.ExecutionContext;
import com.security.agent.model.EnvironmentFingerprint;
import com.security.agent.model.SkillDefinition;
import com.security.agent.util.PathResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class SkillLoaderService {

    private static final Logger log = LoggerFactory.getLogger(SkillLoaderService.class);

    @Value("${security-agent.skills.directory:./skills}")
    private String skillsDir;

    private final ObjectMapper objectMapper;
    private volatile Map<String, SkillDefinition> skillCache = new ConcurrentHashMap<>();

    public SkillLoaderService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        this.skillsDir = PathResolver.resolve(skillsDir).toString();
        loadLatestSkills();
    }

    public Map<String, SkillDefinition> loadLatestSkills() {
        Map<String, SkillDefinition> result = new ConcurrentHashMap<>();
        Path dir = Path.of(skillsDir);

        if (!Files.exists(dir)) {
            try {
                Files.createDirectories(dir);
                log.warn("Skills 目录不存在，已创建: {}", skillsDir);
                return result;
            } catch (IOException e) {
                log.error("无法创建 Skills 目录: {}", skillsDir, e);
                return result;
            }
        }

        try (Stream<Path> files = Files.list(dir)) {
            List<Path> jsonFiles = files
                    .filter(p -> p.toString().endsWith(".json"))
                    .sorted(Comparator.comparing(Path::toString).reversed())
                    .collect(Collectors.toList());

            for (Path file : jsonFiles) {
                String fileName = file.getFileName().toString();
                String baseName = fileName.replace(".json", "");

                // 文件命名格式：{skillId}-{timestamp}.json
                int lastDash = baseName.lastIndexOf('-');
                if (lastDash < 0) {
                    log.warn("无法解析 Skill 文件名 (跳过): {}", fileName);
                    continue;
                }

                String skillId = baseName.substring(0, lastDash);
                String tsStr = baseName.substring(lastDash + 1);

                // 如果该 skillId 已有更新版本，跳过
                if (result.containsKey(skillId)) continue;

                long timestamp;
                try {
                    timestamp = Long.parseLong(tsStr);
                } catch (NumberFormatException e) {
                    log.warn("无法解析时间戳 (跳过): {}", fileName);
                    continue;
                }

                try {
                    SkillDefinition skill = objectMapper.readValue(file.toFile(), SkillDefinition.class);
                    if (skill.getSkillId() == null) {
                        skill.setSkillId(skillId);
                    }
                    skill.setVersionTimestamp(timestamp);

                    if (skill.getExecutionContexts() == null || skill.getExecutionContexts().isEmpty()) {
                        log.warn("Skill {} 的 executionContexts 为空 (跳过)", skillId);
                        continue;
                    }

                    result.put(skillId, skill);
                } catch (IOException e) {
                    log.error("Skill 文件反序列化失败: {}", fileName, e);
                }
            }

            skillCache = result;
            log.info("Skills 热加载完成，共加载 {} 个 Skill", result.size());
        } catch (IOException e) {
            log.error("Skills 目录扫描失败: {}", skillsDir, e);
        }

        return result;
    }

    public Optional<ExecutionContext> selectBestContext(SkillDefinition skill, EnvironmentFingerprint targetEnv) {
        List<ExecutionContext> contexts = skill.getExecutionContexts();

        // 跳过已弃用的 context
        List<ExecutionContext> active = contexts.stream()
                .filter(ctx -> !ctx.isDeprecated())
                .collect(Collectors.toList());

        // 第一轮：精确匹配
        for (ExecutionContext ctx : active) {
            if (isExactMatch(ctx, targetEnv)) {
                log.info("[{}] 精确匹配 executionContext: {}", skill.getSkillId(), ctx.getContextId());
                return Optional.of(ctx);
            }
        }

        // 第二轮：模糊匹配
        for (ExecutionContext ctx : active) {
            if (isPartialMatch(ctx, targetEnv)) {
                log.info("[{}] 模糊匹配 executionContext: {}", skill.getSkillId(), ctx.getContextId());
                return Optional.of(ctx);
            }
        }

        log.warn("[{}] 无匹配的 executionContext，目标环境: osType={}, osFlavor={}",
                skill.getSkillId(), targetEnv.getOsType(),
                targetEnv.getOsFlavors() != null ? targetEnv.getOsFlavors() : "N/A");
        return Optional.empty();
    }

    private boolean isExactMatch(ExecutionContext ctx, EnvironmentFingerprint env) {
        EnvironmentFingerprint fp = ctx.getEnvironmentFingerprint();
        if (fp == null) return false;

        boolean osMatch = fp.getOsType() != null && fp.getOsType().equalsIgnoreCase(env.getOsType());
        boolean flavorMatch = fp.getOsFlavors() != null && env.getOsFlavors() != null
                && fp.getOsFlavors().stream().anyMatch(f -> env.getOsFlavors().stream().anyMatch(e -> e.equalsIgnoreCase(f)));
        boolean toolsAvailable = fp.getRequiredTools() == null || fp.getRequiredTools().isEmpty()
                || (env.getRequiredTools() != null && env.getRequiredTools().containsAll(fp.getRequiredTools()));

        return osMatch && flavorMatch && toolsAvailable;
    }

    private boolean isPartialMatch(ExecutionContext ctx, EnvironmentFingerprint env) {
        EnvironmentFingerprint fp = ctx.getEnvironmentFingerprint();
        if (fp == null) return false;

        boolean osMatch = fp.getOsType() != null && fp.getOsType().equalsIgnoreCase(env.getOsType());
        boolean toolsAvailable = fp.getRequiredTools() == null || fp.getRequiredTools().isEmpty()
                || (env.getRequiredTools() != null && env.getRequiredTools().containsAll(fp.getRequiredTools()));

        return osMatch && toolsAvailable;
    }

    public SkillDefinition saveEvolvedSkill(SkillDefinition base) {
        long newTs = System.currentTimeMillis();
        base.setVersionTimestamp(newTs);
        base.setEvolutionCount(base.getEvolutionCount() + 1);

        String fileName = base.getSkillId() + "-" + newTs + ".json";
        Path targetPath = Path.of(skillsDir, fileName);

        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(targetPath.toFile(), base);
            log.info("[{}] 进化后 Skill 已持久化: {}", base.getSkillId(), fileName);
        } catch (IOException e) {
            log.error("[{}] 进化后 Skill 持久化失败", base.getSkillId(), e);
        }

        skillCache.put(base.getSkillId(), base);
        return base;
    }

    public List<SkillDefinition> getEvolutionHistory(String skillId) {
        Path dir = Path.of(skillsDir);
        if (!Files.exists(dir)) return Collections.emptyList();

        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(p -> p.getFileName().toString().startsWith(skillId + "-")
                            && p.toString().endsWith(".json"))
                    .map(p -> {
                        try {
                            SkillDefinition s = objectMapper.readValue(p.toFile(), SkillDefinition.class);
                            String name = p.getFileName().toString();
                            String tsStr = name.replace(skillId + "-", "").replace(".json", "");
                            try {
                                s.setVersionTimestamp(Long.parseLong(tsStr));
                            } catch (NumberFormatException ignored) {}
                            return s;
                        } catch (IOException e) {
                            log.warn("读取进化历史文件失败: {}", p, e);
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparingLong(SkillDefinition::getVersionTimestamp).reversed())
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.error("扫描 Skill 进化历史失败: {}", skillId, e);
            return Collections.emptyList();
        }
    }

    public boolean contextExistsForEnvironment(SkillDefinition skill, EnvironmentFingerprint env) {
        return skill.getExecutionContexts().stream()
                .anyMatch(ctx -> isExactMatch(ctx, env));
    }

    public Map<String, SkillDefinition> getSkillCache() {
        return Collections.unmodifiableMap(skillCache);
    }
}
