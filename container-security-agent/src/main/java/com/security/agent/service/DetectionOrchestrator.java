package com.security.agent.service;

import com.security.agent.model.*;
import com.security.agent.util.DateTimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Service
public class DetectionOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(DetectionOrchestrator.class);

    @Value("${security-agent.detection.max-evolve-rounds:5}")
    private int maxEvolveRounds;

    @Value("${security-agent.detection.max-context-evolutions:3}")
    private int maxContextEvolutions;

    @Value("${security-agent.detection.command-timeout-seconds:30}")
    private int commandTimeout;

    private final SshExecutionService sshService;
    private final SkillLoaderService skillLoader;
    private final AiClientService aiClient;
    private final AiLogService aiLogService;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    public DetectionOrchestrator(SshExecutionService sshService,
                                  SkillLoaderService skillLoader,
                                  AiClientService aiClient,
                                  AiLogService aiLogService) {
        this.sshService = sshService;
        this.skillLoader = skillLoader;
        this.aiClient = aiClient;
        this.aiLogService = aiLogService;
    }

    /**
     * Execute detection for all selected skills in parallel.
     */
    public List<SkillReport> executeDetection(List<SkillDefinition> skills, DetectionTask task) {
        List<CompletableFuture<SkillReport>> futures = skills.stream()
                .map(skill -> CompletableFuture.supplyAsync(() ->
                        executeSkillDetection(skill, task), executor))
                .collect(Collectors.toList());

        return futures.stream()
                .map(f -> {
                    try {
                        return f.get();
                    } catch (Exception e) {
                        log.error("Skill 检测异常: {}", e.getMessage(), e);
                        return buildExceptionReport("unknown", e.getMessage());
                    }
                })
                .collect(Collectors.toList());
    }

    public SkillReport executeSkillDetection(SkillDefinition skill, DetectionTask task) {
        String skillId = skill.getSkillId();
        log.info("[{}] ===== 开始检测 =====", skillId);

        String ip = task.getTargetIp();
        int port = task.getSshPort() > 0 ? task.getSshPort() : 22;
        String user = task.getSshUser();
        String pwd = task.getSshPassword();

        // ── Phase 0: Environment fingerprint collection ──
        EnvironmentFingerprint targetEnv = collectEnvironmentFingerprint(skill, ip, port, user, pwd);
        log.info("[{}] 环境指纹: OS={}/{}, Shell={}, 可用工具={}",
                skillId, targetEnv.getOsType(),
                targetEnv.getOsFlavors() != null ? String.join(",", targetEnv.getOsFlavors()) : "N/A",
                targetEnv.getShellType(),
                targetEnv.getRequiredTools() != null ? String.join(",", targetEnv.getRequiredTools()) : "N/A");

        // ── Phase 0.5: Context selection ──
        Optional<ExecutionContext> matchedCtx = skillLoader.selectBestContext(skill, targetEnv);
        ExecutionContext activeContext;
        boolean isContextEvolved = false;

        if (matchedCtx.isPresent()) {
            activeContext = matchedCtx.get();
            log.info("[{}] 使用已有 Context: {}", skillId, activeContext.getContextId());
        } else {
            log.info("[{}] 无匹配 Context，启动上下文进化...", skillId);
            activeContext = evolveNewContext(skill, targetEnv, ip, port, user, pwd, task.getTaskId());
            if (activeContext == null) {
                return buildContextEvolutionFailedReport(skill, targetEnv);
            }
            skill.getExecutionContexts().add(activeContext);
            isContextEvolved = true;
        }

        // ── Phase 1: Command execution + AI judgement ──
        List<ExecutionRecord> records = new ArrayList<>();
        String finalStatus = "PASS";
        boolean commandEvolved = false;
        int contextEvolutionCount = 0;

        List<String> commands = activeContext.getExecutionLogic() != null
                && activeContext.getExecutionLogic().getDetectionCommands() != null
                ? new ArrayList<>(activeContext.getExecutionLogic().getDetectionCommands())
                : Collections.emptyList();

        int cmdIndex = 0;
        while (cmdIndex < commands.size()) {
            String command = commands.get(cmdIndex);
            int round = 0;
            String currentCommand = command;

            while (round < maxEvolveRounds) {
                // Check for cancellation
                if (Thread.currentThread().isInterrupted()) {
                    log.warn("[{}] 检测被中断，终止执行", skillId);
                    throw new RuntimeException("检测已取消", new InterruptedException("任务已终止"));
                }

                log.info("[{}] 执行命令 [{}/{}] round={}: {}",
                        skillId, cmdIndex + 1, commands.size(), round, currentCommand);

                ExecutionResult result = sshService.execute(
                        ip, port, user, pwd, currentCommand, commandTimeout);

                if (result.isBlocked()) {
                    log.warn("[{}] 命令被规则引擎拦截: {}", skillId, currentCommand);
                    // Trigger EVOLVE to get a safe alternative
                    AiVerdict blockedVerdict = AiVerdict.builder()
                            .status("EVOLVE")
                            .reasoning("命令被规则引擎拦截: " + result.getStderr())
                            .nextCommand("")
                            .evidence("")
                            .riskJustification("")
                            .build();
                    ExecutionRecord blockedRecord = ExecutionRecord.builder()
                            .command(currentCommand)
                            .result(result)
                            .verdict(blockedVerdict)
                            .round(round)
                            .build();
                    records.add(blockedRecord);

                    // Ask AI for safer alternative
                    AiVerdict alternative = aiClient.judgeExecution(skill, activeContext, targetEnv,
                            currentCommand, cmdIndex, commands.size(), result, task.getTaskId());
                    currentCommand = alternative.getNextCommand();
                    if (currentCommand == null || currentCommand.isEmpty()) break;
                    round++;
                    continue;
                }

                AiVerdict verdict = aiClient.judgeExecution(skill, activeContext, targetEnv,
                        currentCommand, cmdIndex, commands.size(), result, task.getTaskId());

                ExecutionRecord record = ExecutionRecord.builder()
                        .command(currentCommand)
                        .result(result)
                        .verdict(verdict)
                        .round(round)
                        .build();
                records.add(record);

                log.info("[{}] AI 判定: {} (round {})", skillId, verdict.getStatus(), round);

                switch (verdict.getStatus()) {
                    case "PASS":
                        cmdIndex++;
                        break;

                    case "FAIL":
                        finalStatus = "FAIL";
                        cmdIndex++;
                        break;

                    case "EVOLVE":
                        round++;
                        currentCommand = verdict.getNextCommand();
                        commandEvolved = true;
                        continue;

                    case "ENV_MISMATCH":
                        // 优先检查 SSH 连接是否已达 —— 连接失败时进化无效
                        if (result.isConnectionError()) {
                            log.error("[{}] SSH 连接不可达 ({}:{}), 终止检测",
                                    skillId, ip, port);
                            return buildConnectionFailedReport(skill, result, task.getTaskId());
                        }
                        // 检查上下文进化次数是否已达上限
                        contextEvolutionCount++;
                        if (contextEvolutionCount > maxContextEvolutions) {
                            log.error("[{}] 上下文进化次数已达上限 ({}), 终止检测",
                                    skillId, maxContextEvolutions);
                            return buildContextEvolutionLimitReport(skill, maxContextEvolutions, task.getTaskId());
                        }
                        log.warn("[{}] Context {} 环境不匹配，重新进化 ({}/{})",
                                skillId, activeContext.getContextId(),
                                contextEvolutionCount, maxContextEvolutions);
                        ExecutionContext newCtx = evolveNewContext(skill, targetEnv, ip, port, user, pwd, task.getTaskId());
                        if (newCtx == null) break;
                        activeContext = newCtx;
                        skill.getExecutionContexts().add(activeContext);
                        commands = activeContext.getExecutionLogic() != null
                                && activeContext.getExecutionLogic().getDetectionCommands() != null
                                ? new ArrayList<>(activeContext.getExecutionLogic().getDetectionCommands())
                                : Collections.emptyList();
                        cmdIndex = 0;
                        isContextEvolved = true;
                        break;
                }
                break;
            }

            if (round >= maxEvolveRounds) {
                log.warn("[{}] 命令超过最大进化轮次: {}", skillId,
                        cmdIndex < commands.size() ? commands.get(cmdIndex) : "N/A");
                cmdIndex++;
            }
        }

        // ── Persist if evolved ──
        if (isContextEvolved || commandEvolved) {
            skill.setEvolutionCount(skill.getEvolutionCount() + 1);
            skill = skillLoader.saveEvolvedSkill(skill);
        }

        // ── Phase 2: Report generation ──
        log.info("[{}] 检测完成，最终状态={}, 生成报告...", skillId, finalStatus);
        AiReportResponse report = aiClient.generateReport(skill, activeContext, targetEnv,
                finalStatus, isContextEvolved, commandEvolved, records, task.getTaskId());

        return assembleSkillReport(skill, activeContext, targetEnv, finalStatus,
                isContextEvolved || commandEvolved,
                isContextEvolved ? "context" : commandEvolved ? "command" : "none",
                records, report);
    }

    private EnvironmentFingerprint collectEnvironmentFingerprint(
            SkillDefinition skill, String ip, int port, String user, String pwd) {

        Set<String> allProbes = skill.getExecutionContexts().stream()
                .flatMap(ctx -> ctx.getEnvCheckCommands().stream())
                .collect(Collectors.toSet());

        String combined = String.join("; echo '---NEXT_PROBE---'; ", allProbes);
        ExecutionResult result = sshService.executeRaw(ip, port, user, pwd, combined, 30);

        return EnvironmentFingerprint.fromProbeResult(result);
    }

    private ExecutionContext evolveNewContext(SkillDefinition skill, EnvironmentFingerprint targetEnv,
                                               String ip, int port, String user, String pwd, String taskId) {
        if (skillLoader.contextExistsForEnvironment(skill, targetEnv)) {
            log.info("[{}] 已存在匹配当前环境的 context，跳过进化", skill.getSkillId());
            return skillLoader.selectBestContext(skill, targetEnv).orElse(null);
        }

        String rawEnvOutput = collectRawEnvOutput(skill, ip, port, user, pwd);
        ExecutionContext newCtx = aiClient.evolveNewContext(skill, targetEnv, rawEnvOutput, taskId);

        if (newCtx == null) {
            log.error("[{}] AI 上下文进化失败", skill.getSkillId());
            return null;
        }

        newCtx.setEvolvedFrom("ai-generated");
        newCtx.setEvolvedAt(System.currentTimeMillis());
        newCtx.setDeprecated(false);

        log.info("[{}] 上下文进化成功，新 Context: {}", skill.getSkillId(), newCtx.getContextId());
        return newCtx;
    }

    private String collectRawEnvOutput(SkillDefinition skill, String ip, int port, String user, String pwd) {
        Set<String> allProbes = skill.getExecutionContexts().stream()
                .flatMap(ctx -> ctx.getEnvCheckCommands().stream())
                .collect(Collectors.toSet());
        String combined = String.join("; echo '---NEXT_PROBE---'; ", allProbes);
        ExecutionResult result = sshService.executeRaw(ip, port, user, pwd, combined, 30);
        return result.getStdout() + "\n" + result.getStderr();
    }

    private SkillReport assembleSkillReport(SkillDefinition skill, ExecutionContext context,
                                             EnvironmentFingerprint env, String finalStatus,
                                             boolean isEvolved, String evolutionType,
                                             List<ExecutionRecord> records,
                                             AiReportResponse aiReport) {
        return SkillReport.builder()
                .skillId(skill.getSkillId())
                .skillName(skill.getSkillName())
                .finalStatus(finalStatus)
                .usedContextId(context != null ? context.getContextId() : "unknown")
                .contextEnvironment(context != null ? context.getContextId() : "")
                .evolutionType(evolutionType)
                .isEvolved(isEvolved)
                .executionRecords(records)
                .testReport(aiReport != null ? aiReport.getTestReport() : null)
                .securityRemediation(aiReport != null ? aiReport.getSecurityRemediation() : null)
                .build();
    }

    private SkillReport buildConnectionFailedReport(SkillDefinition skill,
                                                     ExecutionResult result, String taskId) {
        String errorDetail = result.getStderr();
        log.error("[{}] SSH 连接失败: {}", skill.getSkillId(), errorDetail);

        // 记录 AI 日志，标记连接失败
        aiLogService.log(new AiLogEntry(
                java.util.UUID.randomUUID().toString(),
                taskId,
                skill.getSkillId(),
                "ssh-connection-error",
                null,
                null,
                errorDetail,
                null,
                "FAILED",
                null,
                0L,
                System.currentTimeMillis()));

        return SkillReport.builder()
                .skillId(skill.getSkillId())
                .skillName(skill.getSkillName())
                .finalStatus("FAIL")
                .usedContextId("none")
                .contextEnvironment("SSH 连接失败")
                .evolutionType("none")
                .isEvolved(false)
                .executionRecords(Collections.emptyList())
                .testReport(AiReportResponse.TestReport.builder()
                        .summary("无法连接到目标容器: " + errorDetail)
                        .riskLevel("HIGH")
                        .evidence(errorDetail)
                        .affectedEnvironment("unknown")
                        .build())
                .securityRemediation(AiReportResponse.SecurityRemediation.builder()
                        .strategy("检查目标容器 IP 和端口是否正确，确认 SSH 服务可访问")
                        .k8sYamlPatch("")
                        .alternativeAdvice("确认网络可达后重试检测")
                        .environmentSpecificNotes("SSH 地址不可达或连接被拒绝")
                        .build())
                .build();
    }

    private SkillReport buildContextEvolutionLimitReport(SkillDefinition skill,
                                                          int maxEvolutions, String taskId) {
        log.warn("[{}] 上下文进化已达上限 ({}), 终止检测", skill.getSkillId(), maxEvolutions);

        aiLogService.log(new AiLogEntry(
                java.util.UUID.randomUUID().toString(),
                taskId,
                skill.getSkillId(),
                "ssh-connection-error",
                null,
                null,
                "上下文进化次数已达上限: " + maxEvolutions,
                null,
                "FAILED",
                null,
                0L,
                System.currentTimeMillis()));

        return SkillReport.builder()
                .skillId(skill.getSkillId())
                .skillName(skill.getSkillName())
                .finalStatus("FAIL")
                .usedContextId("none")
                .contextEnvironment("上下文进化次数耗尽")
                .evolutionType("none")
                .isEvolved(false)
                .executionRecords(Collections.emptyList())
                .testReport(AiReportResponse.TestReport.builder()
                        .summary("上下文进化已达最大次数 (" + maxEvolutions + "), 无法找到适配的执行上下文")
                        .riskLevel("HIGH")
                        .evidence("目标环境信息缺失，AI 无法生成有效执行上下文")
                        .affectedEnvironment("unknown")
                        .build())
                .securityRemediation(AiReportResponse.SecurityRemediation.builder()
                        .strategy("检查目标环境 SSH 可达性，或手动编写适用于该环境的 executionContext")
                        .k8sYamlPatch("")
                        .alternativeAdvice("")
                        .environmentSpecificNotes("")
                        .build())
                .build();
    }

    private SkillReport buildContextEvolutionFailedReport(SkillDefinition skill,
                                                           EnvironmentFingerprint env) {
        return SkillReport.builder()
                .skillId(skill.getSkillId())
                .skillName(skill.getSkillName())
                .finalStatus("FAIL")
                .usedContextId("none")
                .contextEnvironment("上下文进化失败: " + (env != null ? env.getOsType() : "unknown"))
                .evolutionType("none")
                .isEvolved(false)
                .executionRecords(Collections.emptyList())
                .testReport(AiReportResponse.TestReport.builder()
                        .summary("无法为目标环境生成适用的执行上下文")
                        .riskLevel("HIGH")
                        .evidence("环境信息: " + (env != null ? env.getOsType() + "/" + env.getOsFlavors() : "unknown"))
                        .affectedEnvironment(env != null ? env.getOsType() : "unknown")
                        .build())
                .securityRemediation(AiReportResponse.SecurityRemediation.builder()
                        .strategy("需要人工分析目标环境并手动创建 executionContext")
                        .k8sYamlPatch("")
                        .alternativeAdvice("建议为目标环境手动编写适配的检测命令")
                        .environmentSpecificNotes("")
                        .build())
                .build();
    }

    private SkillReport buildExceptionReport(String skillId, String error) {
        return SkillReport.builder()
                .skillId(skillId)
                .skillName("Unknown")
                .finalStatus("FAIL")
                .evolutionType("none")
                .isEvolved(false)
                .executionRecords(Collections.emptyList())
                .testReport(AiReportResponse.TestReport.builder()
                        .summary("检测异常: " + error)
                        .riskLevel("INFO")
                        .evidence(error)
                        .build())
                .securityRemediation(AiReportResponse.SecurityRemediation.builder()
                        .strategy("")
                        .k8sYamlPatch("")
                        .alternativeAdvice("")
                        .environmentSpecificNotes("")
                        .build())
                .build();
    }
}
