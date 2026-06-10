package com.security.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.security.agent.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Integration-style tests for DetectionOrchestrator.
 * Uses mock SSH and mock AI to verify the four verdict branches.
 */
class DetectionOrchestratorIntegrationTest {

    private DetectionOrchestrator orchestrator;
    private SshExecutionService mockSsh;
    private SkillLoaderService mockSkillLoader;
    private AiClientService mockAi;
    private AiLogService mockAiLog;
    private KubectlService mockKubectl;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mockSsh = mock(SshExecutionService.class);
        mockSkillLoader = mock(SkillLoaderService.class);
        mockAi = mock(AiClientService.class);
        mockAiLog = mock(AiLogService.class);
        mockKubectl = mock(KubectlService.class);
        mapper = new ObjectMapper();

        orchestrator = new DetectionOrchestrator(mockSsh, mockSkillLoader, mockAi, mockAiLog, mockKubectl);

        // Inject @Value fields since this is a non-Spring test
        try {
            Field maxEvolveField = DetectionOrchestrator.class.getDeclaredField("maxEvolveRounds");
            maxEvolveField.setAccessible(true);
            maxEvolveField.set(orchestrator, 5);

            Field maxCtxField = DetectionOrchestrator.class.getDeclaredField("maxContextEvolutions");
            maxCtxField.setAccessible(true);
            maxCtxField.set(orchestrator, 3);

            Field cmdTimeoutField = DetectionOrchestrator.class.getDeclaredField("commandTimeout");
            cmdTimeoutField.setAccessible(true);
            cmdTimeoutField.set(orchestrator, 30);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("PASS 分支：所有检测命令返回 PASS")
    void shouldPassWhenAllCommandsDefended() {
        SkillDefinition skill = createSkill("SEC-TEST-001");
        DetectionTask task = createTask();

        // Mock environment fingerprint
        EnvironmentFingerprint env = EnvironmentFingerprint.builder()
                .osType("linux")
                .osFlavors(List.of("debian"))
                .shellType("bash")
                .requiredTools(List.of("cat", "grep"))
                .build();
        when(mockSsh.executeRaw(anyString(), anyInt(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(ExecutionResult.builder().stdout("Linux\ndebian\nbash\n/bin/cat\n/bin/grep").exitCode(0).build());

        // Mock context match
        ExecutionContext ctx = createContext("linux-debian");
        when(mockSkillLoader.selectBestContext(any(), any())).thenReturn(java.util.Optional.of(ctx));

        // Mock SSH execution of detection command
        when(mockSsh.execute(anyString(), anyInt(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(ExecutionResult.builder().stdout("Operation not permitted").stderr("").exitCode(1).build());

        // Mock AI verdict: PASS
        when(mockAi.judgeExecution(any(), any(), any(), anyString(), anyInt(), anyInt(), any(), anyString()))
                .thenReturn(AiVerdict.builder()
                        .status("PASS")
                        .reasoning("防御生效")
                        .evidence("Operation not permitted")
                        .build());

        // Mock AI report
        when(mockAi.generateReport(any(), any(), any(), anyString(), anyBoolean(), anyBoolean(), anyList(), anyString()))
                .thenReturn(createReportResponse("PASS"));

        SkillReport report = orchestrator.executeSkillDetection(skill, task);
        assertNotNull(report);
        assertEquals("PASS", report.getFinalStatus());
    }

    @Test
    @DisplayName("FAIL 分支：检测命令暴露安全风险")
    void shouldFailWhenVulnerabilityFound() {
        SkillDefinition skill = createSkill("SEC-TEST-001");
        DetectionTask task = createTask();

        when(mockSsh.executeRaw(anyString(), anyInt(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(ExecutionResult.builder().stdout("Linux\ndebian\nbash").exitCode(0).build());

        ExecutionContext ctx = createContext("linux-debian");
        when(mockSkillLoader.selectBestContext(any(), any())).thenReturn(java.util.Optional.of(ctx));

        when(mockSsh.execute(anyString(), anyInt(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(ExecutionResult.builder().stdout("UID=0 GID=0").exitCode(0).build());

        when(mockAi.judgeExecution(any(), any(), any(), anyString(), anyInt(), anyInt(), any(), anyString()))
                .thenReturn(AiVerdict.builder()
                        .status("FAIL")
                        .reasoning("以 root 运行，存在特权逃逸风险")
                        .evidence("UID=0 GID=0")
                        .build());

        when(mockAi.generateReport(any(), any(), any(), anyString(), anyBoolean(), anyBoolean(), anyList(), anyString()))
                .thenReturn(createReportResponse("FAIL"));

        SkillReport report = orchestrator.executeSkillDetection(skill, task);
        assertNotNull(report);
        assertEquals("FAIL", report.getFinalStatus());
    }

    @Test
    @DisplayName("EVOLVE 分支：命令不存在，AI 提供替代命令")
    void shouldEvolveWhenCommandUnavailable() {
        SkillDefinition skill = createSkill("SEC-TEST-001");
        DetectionTask task = createTask();

        when(mockSsh.executeRaw(anyString(), anyInt(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(ExecutionResult.builder().stdout("Linux\ndebian\nbash").exitCode(0).build());

        ExecutionContext ctx = createContext("linux-debian");
        when(mockSkillLoader.selectBestContext(any(), any())).thenReturn(java.util.Optional.of(ctx));

        // First attempt: command not found
        when(mockSsh.execute(anyString(), anyInt(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(ExecutionResult.builder()
                        .stdout("")
                        .stderr("bash: capsh: command not found")
                        .exitCode(127).build());

        // AI says EVOLVE with next command, then PASS
        when(mockAi.judgeExecution(any(), any(), any(), anyString(), anyInt(), anyInt(), any(), anyString()))
                .thenReturn(AiVerdict.builder()
                        .status("EVOLVE")
                        .reasoning("capsh 不可用，建议使用 /proc 解析")
                        .nextCommand("cat /proc/1/status | grep CapEff")
                        .build())
                .thenReturn(AiVerdict.builder()
                        .status("PASS")
                        .reasoning("capsh 不可用，已用替代方案验证防御生效")
                        .evidence("CapEff: 00000000a80425fb (受限)")
                        .build());

        when(mockAi.generateReport(any(), any(), any(), anyString(), anyBoolean(), anyBoolean(), anyList(), anyString()))
                .thenReturn(createReportResponse("PASS"));
        when(mockSkillLoader.saveEvolvedSkill(any())).thenReturn(skill);

        SkillReport report = orchestrator.executeSkillDetection(skill, task);
        assertNotNull(report);
        // Should have evolved (command-level)
        assertTrue(report.isEvolved());
    }

    @Test
    @DisplayName("ENV_MISMATCH 分支：Context 与目标环境不匹配，触发上下文进化")
    void shouldEvolveContextOnEnvMismatch() {
        SkillDefinition skill = createSkill("SEC-TEST-001");
        DetectionTask task = createTask();

        when(mockSsh.executeRaw(anyString(), anyInt(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(ExecutionResult.builder().stdout("Windows").exitCode(0).build());

        ExecutionContext linuxCtx = createContext("linux-debian");
        when(mockSkillLoader.selectBestContext(any(), any())).thenReturn(java.util.Optional.of(linuxCtx));

        when(mockSsh.execute(anyString(), anyInt(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(ExecutionResult.builder()
                        .stdout("")
                        .stderr("Get-Command: The term 'cat' is not recognized")
                        .exitCode(1).build());

        when(mockAi.judgeExecution(any(), any(), any(), anyString(), anyInt(), anyInt(), any(), anyString()))
                .thenReturn(AiVerdict.builder()
                        .status("ENV_MISMATCH")
                        .reasoning("目标为 Windows Container，Linux Context 不适用")
                        .evidence("cat 命令不存在")
                        .build());

        ExecutionContext windowsCtx = ExecutionContext.builder()
                .contextId("windows-container")
                .environmentFingerprint(EnvironmentFingerprint.builder()
                        .osType("windows")
                        .osFlavors(List.of("windows-container"))
                        .shellType("powershell")
                        .build())
                .executionLogic(ExecutionContext.ExecutionLogic.builder()
                        .detectionCommands(List.of("Get-ComputerInfo"))
                        .build())
                .build();

        when(mockAi.evolveNewContext(any(), any(), anyString(), anyString())).thenReturn(windowsCtx);
        when(mockSkillLoader.contextExistsForEnvironment(any(), any())).thenReturn(false);

        // After context evolution, AI judges new command as PASS
        when(mockAi.judgeExecution(any(), any(), any(), eq("Get-ComputerInfo"), anyInt(), anyInt(), any(), anyString()))
                .thenReturn(AiVerdict.builder().status("PASS").reasoning("OK").build());

        when(mockAi.generateReport(any(), any(), any(), anyString(), anyBoolean(), anyBoolean(), anyList(), anyString()))
                .thenReturn(createReportResponse("PASS"));
        when(mockSkillLoader.saveEvolvedSkill(any())).thenReturn(skill);

        SkillReport report = orchestrator.executeSkillDetection(skill, task);
        assertNotNull(report);
        assertTrue(report.isEvolved());
    }

    @Test
    @DisplayName("上下文进化失败时返回特殊报告")
    void shouldBuildFailedReportWhenContextEvolutionFails() {
        SkillDefinition skill = createSkill("SEC-TEST-001");
        DetectionTask task = createTask();

        when(mockSsh.executeRaw(anyString(), anyInt(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(ExecutionResult.builder().stdout("UnknownOS").exitCode(0).build());

        // No match
        when(mockSkillLoader.selectBestContext(any(), any())).thenReturn(java.util.Optional.empty());
        when(mockSkillLoader.contextExistsForEnvironment(any(), any())).thenReturn(false);
        // AI evolution also fails
        when(mockAi.evolveNewContext(any(), any(), anyString(), anyString())).thenReturn(null);

        SkillReport report = orchestrator.executeSkillDetection(skill, task);
        assertNotNull(report);
        assertEquals("FAIL", report.getFinalStatus());
        assertTrue(report.getTestReport().getSummary().contains("无法为目标环境生成"));
    }

    private SkillDefinition createSkill(String skillId) {
        return SkillDefinition.builder()
                .skillId(skillId)
                .skillName("Test Skill")
                .versionTimestamp(System.currentTimeMillis())
                .riskLevel("HIGH")
                .description("Test detection skill")
                .evolutionCount(0)
                .executionContexts(new java.util.ArrayList<>(List.of(createContext("linux-debian"))))
                .build();
    }

    private ExecutionContext createContext(String contextId) {
        return ExecutionContext.builder()
                .contextId(contextId)
                .environmentFingerprint(EnvironmentFingerprint.builder()
                        .osType("linux")
                        .osFlavors(List.of("debian"))
                        .shellType("bash")
                        .requiredTools(List.of("cat", "grep"))
                        .build())
                .envCheckCommands(List.of("uname -s", "cat /etc/os-release", "which cat grep"))
                .executionLogic(ExecutionContext.ExecutionLogic.builder()
                        .detectionCommands(List.of("cat /proc/1/status | grep CapEff"))
                        .expectedBehavior("CapEff limited")
                        .build())
                .deprecated(false)
                .build();
    }

    private DetectionTask createTask() {
        return DetectionTask.builder()
                .taskId("TASK-20260609-0001")
                .targetIp("10.0.0.50")
                .sshUser("root")
                .sshPassword("test")
                .sshPort(22)
                .skillIds(List.of("SEC-TEST-001"))
                .status(TaskStatus.RUNNING)
                .build();
    }

    private AiReportResponse createReportResponse(String status) {
        return AiReportResponse.builder()
                .testReport(AiReportResponse.TestReport.builder()
                        .summary("Detection completed with status: " + status)
                        .riskLevel("PASS".equals(status) ? "LOW" : "HIGH")
                        .evidence("Test evidence")
                        .affectedEnvironment("Debian Container")
                        .build())
                .securityRemediation(AiReportResponse.SecurityRemediation.builder()
                        .strategy("Apply securityContext constraints")
                        .k8sYamlPatch("securityContext: {}\n  capabilities: {}\n    drop: [ALL]")
                        .alternativeAdvice("Use seccomp profiles")
                        .environmentSpecificNotes("Test env notes")
                        .build())
                .build();
    }
}
