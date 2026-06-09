package com.security.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.security.agent.model.*;
import com.security.agent.util.JsonCleaner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class AiClientService {

    private static final Logger log = LoggerFactory.getLogger(AiClientService.class);

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final PromptTemplateEngine templateEngine;
    private final AiLogService aiLogService;

    public AiClientService(ChatClient chatClient, ObjectMapper objectMapper,
                           PromptTemplateEngine templateEngine,
                           AiLogService aiLogService) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
        this.templateEngine = templateEngine;
        this.aiLogService = aiLogService;
    }

    // ──── Phase 0: Environment Matching ────

    public ContextMatchResult matchEnvironment(SkillDefinition skill,
                                                EnvironmentFingerprint targetEnv,
                                                ExecutionResult rawProbeResult,
                                                String taskId) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("skillId", skill.getSkillId());
        vars.put("skillName", skill.getSkillName());
        vars.put("description", skill.getDescription());
        vars.put("executionContexts", skill.getExecutionContexts());
        vars.put("targetEnv", envToMap(targetEnv));
        vars.put("contextCount", skill.getExecutionContexts().size());

        return callAndParse(
                templateEngine.render("phase0-system", vars),
                templateEngine.render("phase0-user", buildPhase0UserVars(skill, targetEnv, rawProbeResult)),
                "phase0", taskId, skill.getSkillId(), ContextMatchResult.class);
    }

    // ──── Phase 1: Command Execution Judgement ────

    public AiVerdict judgeExecution(SkillDefinition skill, ExecutionContext activeContext,
                                     EnvironmentFingerprint targetEnv, String currentCommand,
                                     int commandIndex, int totalCommands, ExecutionResult result,
                                     String taskId) {
        Map<String, Object> sysVars = new HashMap<>();
        sysVars.put("skillId", skill.getSkillId());
        sysVars.put("skillName", skill.getSkillName());
        sysVars.put("description", skill.getDescription());
        sysVars.put("riskLevel", skill.getRiskLevel());
        sysVars.put("contextId", activeContext.getContextId());
        sysVars.put("environmentSummary", activeContext.getContextId());
        sysVars.put("expectedBehavior",
                activeContext.getExecutionLogic() != null
                        ? activeContext.getExecutionLogic().getExpectedBehavior() : "");
        sysVars.put("detectionCommands",
                activeContext.getExecutionLogic() != null
                        ? activeContext.getExecutionLogic().getDetectionCommands() : Collections.emptyList());
        sysVars.put("targetOsType", targetEnv.getOsType());
        sysVars.put("targetOsFlavor",
                targetEnv.getOsFlavors() != null ? String.join(",", targetEnv.getOsFlavors()) : "");
        sysVars.put("targetKernelVersion", targetEnv.getKernelVersion());
        sysVars.put("targetShellType", targetEnv.getShellType());
        sysVars.put("targetAvailableTools",
                targetEnv.getRequiredTools() != null ? String.join(",", targetEnv.getRequiredTools()) : "");

        Map<String, Object> userVars = new HashMap<>();
        userVars.put("skillId", skill.getSkillId());
        userVars.put("contextId", activeContext.getContextId());
        userVars.put("commandIndex", String.valueOf(commandIndex + 1));
        userVars.put("totalCommands", String.valueOf(totalCommands));
        userVars.put("currentCommand", currentCommand);
        userVars.put("exitCode", String.valueOf(result.getExitCode()));
        userVars.put("stdout", result.getStdout() != null ? result.getStdout() : "(empty)");
        userVars.put("stderr", result.getStderr() != null ? result.getStderr() : "(empty)");
        userVars.put("remainingCommands",
                String.valueOf(totalCommands - commandIndex - 1));

        return callAndParse(
                templateEngine.render("phase1-system", sysVars),
                templateEngine.render("phase1-user", userVars),
                "phase1", taskId, skill.getSkillId(), AiVerdict.class);
    }

    // ──── Context Evolution ────

    public ExecutionContext evolveNewContext(SkillDefinition skill,
                                              EnvironmentFingerprint targetEnv,
                                              String rawEnvOutput,
                                              String taskId) {
        Map<String, Object> sysVars = new HashMap<>();
        sysVars.put("skillId", skill.getSkillId());
        sysVars.put("skillName", skill.getSkillName());
        sysVars.put("description", skill.getDescription());
        sysVars.put("riskLevel", skill.getRiskLevel());
        sysVars.put("remediationFocus",
                skill.getReportMetadata() != null ? skill.getReportMetadata().getRemediationFocus() : "");
        sysVars.put("executionContexts", skill.getExecutionContexts());
        sysVars.put("targetOsType", targetEnv.getOsType());
        sysVars.put("targetOsFlavor",
                targetEnv.getOsFlavors() != null ? String.join(",", targetEnv.getOsFlavors()) : "unknown");
        sysVars.put("targetOsVersion", targetEnv.getOsVersion() != null ? targetEnv.getOsVersion() : "unknown");
        sysVars.put("targetKernelVersion", targetEnv.getKernelVersion() != null ? targetEnv.getKernelVersion() : "unknown");
        sysVars.put("targetShellType", targetEnv.getShellType() != null ? targetEnv.getShellType() : "unknown");
        sysVars.put("targetArch",
                targetEnv.getArch() != null ? String.join(",", targetEnv.getArch()) : "unknown");
        sysVars.put("targetAvailableTools",
                targetEnv.getRequiredTools() != null ? String.join(",", targetEnv.getRequiredTools()) : "");
        sysVars.put("targetMissingTools", "");

        Map<String, Object> userVars = new HashMap<>();
        userVars.put("skillId", skill.getSkillId());
        userVars.put("targetOsFlavor",
                targetEnv.getOsFlavors() != null ? String.join(",", targetEnv.getOsFlavors()) : "unknown");
        userVars.put("targetOsVersion", targetEnv.getOsVersion() != null ? targetEnv.getOsVersion() : "unknown");
        userVars.put("targetKernelVersion", targetEnv.getKernelVersion() != null ? targetEnv.getKernelVersion() : "unknown");
        userVars.put("targetShellType", targetEnv.getShellType() != null ? targetEnv.getShellType() : "unknown");
        userVars.put("targetAvailableTools",
                targetEnv.getRequiredTools() != null ? String.join(",", targetEnv.getRequiredTools()) : "");
        userVars.put("envRawOutput", rawEnvOutput != null ? rawEnvOutput : "(无)");

        return callAndParse(
                templateEngine.render("context-evolution-system", sysVars),
                templateEngine.render("context-evolution-user", userVars),
                "context-evolution", taskId, skill.getSkillId(), ExecutionContext.class);
    }

    // ──── Phase 2: Report Generation ────

    public AiReportResponse generateReport(SkillDefinition skill, ExecutionContext activeContext,
                                            EnvironmentFingerprint targetEnv, String finalStatus,
                                            boolean isContextEvolved, boolean isCommandEvolved,
                                            List<ExecutionRecord> allRecords,
                                            String taskId) {
        String evolutionType = isContextEvolved ? "context" : isCommandEvolved ? "command" : "none";

        Map<String, Object> sysVars = new HashMap<>();
        sysVars.put("skillId", skill.getSkillId());
        sysVars.put("skillName", skill.getSkillName());
        sysVars.put("description", skill.getDescription());
        sysVars.put("remediationFocus",
                skill.getReportMetadata() != null ? skill.getReportMetadata().getRemediationFocus() : "");
        sysVars.put("finalStatus", finalStatus);
        sysVars.put("contextId", activeContext != null ? activeContext.getContextId() : "unknown");
        sysVars.put("environmentSummary", activeContext != null ? activeContext.getContextId() : "");
        sysVars.put("isEvolved", String.valueOf(isContextEvolved || isCommandEvolved));
        sysVars.put("evolutionType", evolutionType);

        List<Map<String, Object>> records = new ArrayList<>();
        if (allRecords != null) {
            for (int i = 0; i < allRecords.size(); i++) {
                ExecutionRecord r = allRecords.get(i);
                Map<String, Object> rm = new HashMap<>();
                rm.put("index", String.valueOf(i + 1));
                rm.put("command", r.getCommand());
                rm.put("exitCode", String.valueOf(r.getResult().getExitCode()));
                rm.put("stdout", r.getResult().getStdout());
                rm.put("stderr", r.getResult().getStderr());
                rm.put("verdict.status",
                        r.getVerdict() != null ? r.getVerdict().getStatus() : "unknown");
                rm.put("verdict.reasoning",
                        r.getVerdict() != null ? r.getVerdict().getReasoning() : "");
                records.add(rm);
            }
        }
        sysVars.put("executionRecords", records);

        ExecutionRecord last = allRecords != null && !allRecords.isEmpty()
                ? allRecords.get(allRecords.size() - 1) : null;
        Map<String, Object> userVars = new HashMap<>();
        userVars.put("skillId", skill.getSkillId());
        userVars.put("finalStatus", finalStatus);
        userVars.put("contextId", activeContext != null ? activeContext.getContextId() : "");
        userVars.put("environmentSummary", activeContext != null ? activeContext.getContextId() : "");
        userVars.put("isEvolved", String.valueOf(isContextEvolved || isCommandEvolved));
        userVars.put("evolutionType", evolutionType);
        if (last != null) {
            userVars.put("lastCommand", last.getCommand());
            userVars.put("exitCode", String.valueOf(last.getResult().getExitCode()));
            userVars.put("stdout", last.getResult().getStdout());
            userVars.put("stderr", last.getResult().getStderr());
        } else {
            userVars.put("lastCommand", "N/A");
            userVars.put("exitCode", "0");
            userVars.put("stdout", "");
            userVars.put("stderr", "");
        }
        StringBuilder summary = new StringBuilder();
        if (allRecords != null) {
            for (ExecutionRecord r : allRecords) {
                summary.append(r.getCommand()).append(" -> ")
                        .append(r.getVerdict() != null ? r.getVerdict().getStatus() : "?").append("\n");
            }
        }
        userVars.put("executionRecordsSummary", summary.toString());

        return callAndParse(
                templateEngine.render("phase2-system", sysVars),
                templateEngine.render("phase2-user", userVars),
                "phase2", taskId, skill.getSkillId(), AiReportResponse.class);
    }

    // ──── Internal helpers ────

    private <T> T callAndParse(String system, String user, String phase, String taskId,
                               String skillId, Class<T> clazz) {
        long start = System.currentTimeMillis();
        String raw;
        try {
            raw = chatClient.prompt()
                    .system(system)
                    .user(user)
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("AI 调用失败", e);
            long cost = System.currentTimeMillis() - start;
            // Record failed call
            aiLogService.log(new AiLogEntry(
                    UUID.randomUUID().toString(), taskId, skillId,
                    phase, system, user, "AI 调用异常: " + e.getMessage(),
                    "", "FAILED", clazz.getSimpleName(), cost,
                    System.currentTimeMillis()));
            throw new RuntimeException("AI 服务不可用: " + e.getMessage(), e);
        }

        long cost = System.currentTimeMillis() - start;
        log.debug("AI 原始返回 ({}): {}", clazz.getSimpleName(),
                raw != null ? raw.substring(0, Math.min(500, raw.length())) + "..." : "null");

        // Round 1: clean then parse
        String cleaned = JsonCleaner.clean(raw);
        String parseResult;
        try {
            T result = objectMapper.readValue(cleaned, clazz);
            parseResult = "SUCCESS";
            aiLogService.log(new AiLogEntry(
                    UUID.randomUUID().toString(), taskId, skillId,
                    phase, system, user, raw, cleaned, parseResult,
                    clazz.getSimpleName(), cost, System.currentTimeMillis()));
            return result;
        } catch (JsonProcessingException e1) {
            log.warn("AI JSON 首轮解析失败 ({}), 尝试自我修复...", clazz.getSimpleName());

            // Round 2: AI self-repair
            String fixed = selfRepairJson(cleaned, clazz, taskId, skillId);
            try {
                T result = objectMapper.readValue(fixed, clazz);
                parseResult = "REPAIR_SUCCESS";
                aiLogService.log(new AiLogEntry(
                        UUID.randomUUID().toString(), taskId, skillId,
                        phase, system, user, raw, fixed, parseResult,
                        clazz.getSimpleName(), cost, System.currentTimeMillis()));
                return result;
            } catch (JsonProcessingException e2) {
                log.error("AI JSON 修复后仍无法解析\n清洗后: {}\n修复后: {}", cleaned, fixed);
                parseResult = "FAILED";
                aiLogService.log(new AiLogEntry(
                        UUID.randomUUID().toString(), taskId, skillId,
                        phase, system, user, raw, fixed, parseResult,
                        clazz.getSimpleName(), cost, System.currentTimeMillis()));
                throw new RuntimeException("AI 返回值无法解析为 " + clazz.getSimpleName(), e2);
            }
        }
    }

    private String selfRepairJson(String broken, Class<?> targetClass, String taskId, String skillId) {
        long start = System.currentTimeMillis();
        String repairPrompt = String.format("""
            以下 JSON 文本无法被 Jackson 解析。请修复它，使其成为合法的 JSON。
            目标类型: %s
            损坏的 JSON: %s
            请仅输出修复后的 JSON，不要包含任何解释或 Markdown 标记。
            """, targetClass.getSimpleName(), broken);

        try {
            String raw = chatClient.prompt()
                    .user(repairPrompt)
                    .call()
                    .content();
            long cost = System.currentTimeMillis() - start;
            String cleaned = JsonCleaner.clean(raw);
            // Record json-repair log
            aiLogService.log(new AiLogEntry(
                    UUID.randomUUID().toString(), taskId, skillId,
                    "json-repair", "", repairPrompt, raw, cleaned,
                    "attempted", targetClass.getSimpleName(), cost,
                    System.currentTimeMillis()));
            return cleaned;
        } catch (Exception e) {
            log.error("AI JSON 自我修复失败", e);
            long cost = System.currentTimeMillis() - start;
            aiLogService.log(new AiLogEntry(
                    UUID.randomUUID().toString(), taskId, skillId,
                    "json-repair", "", repairPrompt,
                    "JSON 自我修复异常: " + e.getMessage(), "",
                    "FAILED", targetClass.getSimpleName(), cost,
                    System.currentTimeMillis()));
            return broken;
        }
    }

    private Map<String, Object> envToMap(EnvironmentFingerprint env) {
        Map<String, Object> m = new HashMap<>();
        m.put("osType", env.getOsType());
        m.put("osFlavor", env.getOsFlavors() != null ? env.getOsFlavors().stream().findFirst().orElse("") : "");
        m.put("osVersion", env.getOsVersion() != null ? env.getOsVersion() : "");
        m.put("kernelVersion", env.getKernelVersion() != null ? env.getKernelVersion() : "");
        m.put("shellType", env.getShellType() != null ? env.getShellType() : "");
        m.put("arch", env.getArch() != null ? String.join(",", env.getArch()) : "");
        m.put("availableTools",
                env.getRequiredTools() != null ? String.join(",", env.getRequiredTools()) : "");
        m.put("missingTools", "");
        return m;
    }

    private Map<String, Object> buildPhase0UserVars(SkillDefinition skill,
                                                     EnvironmentFingerprint targetEnv,
                                                     ExecutionResult rawProbeResult) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("skillId", skill.getSkillId());
        vars.put("contextCount", String.valueOf(skill.getExecutionContexts().size()));

        Map<String, String> envRaw = new HashMap<>();
        envRaw.put("unameS", targetEnv.getOsType() != null ? targetEnv.getOsType() : "unknown");
        envRaw.put("osRelease", targetEnv.getOsFlavors() != null
                ? String.join(",", targetEnv.getOsFlavors()) : "unknown");
        envRaw.put("kernelVersion", targetEnv.getKernelVersion() != null ? targetEnv.getKernelVersion() : "unknown");
        envRaw.put("shell", targetEnv.getShellType() != null ? targetEnv.getShellType() : "unknown");
        envRaw.put("arch", targetEnv.getArch() != null ? String.join(",", targetEnv.getArch()) : "unknown");
        envRaw.put("whichOutput",
                targetEnv.getRequiredTools() != null ? String.join(" ", targetEnv.getRequiredTools()) : "");
        envRaw.put("extraProbes",
                rawProbeResult != null ? rawProbeResult.getStdout() : "(无)");
        vars.put("envRaw", envRaw);

        return vars;
    }
}
