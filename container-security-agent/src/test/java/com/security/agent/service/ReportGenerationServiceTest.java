package com.security.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.security.agent.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReportGenerationServiceTest {

    private ReportGenerationService reportService;
    private ObjectMapper mapper;

    @TempDir
    Path reportsDir;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        reportService = new ReportGenerationService(mapper);

        try {
            java.lang.reflect.Field f = ReportGenerationService.class.getDeclaredField("reportsDir");
            f.setAccessible(true);
            f.set(reportService, reportsDir.toString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("构建 ReportData 并计算得分")
    void shouldCalculateScoreAndPassRate() {
        DetectionTask task = DetectionTask.builder()
                .taskId("TASK-20260609-0001")
                .targetIp("10.0.0.50")
                .build();

        SkillReport pass1 = SkillReport.builder()
                .skillId("SKILL-001").skillName("Skill 1").finalStatus("PASS").build();
        SkillReport pass2 = SkillReport.builder()
                .skillId("SKILL-002").skillName("Skill 2").finalStatus("PASS").build();
        SkillReport fail1 = SkillReport.builder()
                .skillId("SKILL-003").skillName("Skill 3").finalStatus("FAIL").build();

        List<SkillReport> reports = List.of(pass1, pass2, fail1);
        EnvironmentFingerprint env = EnvironmentFingerprint.builder()
                .osType("linux").osFlavors(List.of("debian")).build();

        ReportData report = reportService.buildReportData(task, reports, env);

        assertEquals("TASK-20260609-0001", report.getTaskId());
        assertEquals("10.0.0.50", report.getTargetIp());
        assertEquals(67, report.getOverallScore()); // Math.round(66.666...) = 67
        assertEquals("2/3", report.getPassRate());
        assertNotNull(report.getTargetEnvironment());
        assertEquals("linux", report.getTargetEnvironment().getOsType());
    }

    @Test
    @DisplayName("全部 PASS 时得分为 100")
    void shouldScore100WhenAllPass() {
        DetectionTask task = DetectionTask.builder().taskId("TASK-001").targetIp("10.0.0.1").build();
        List<SkillReport> reports = List.of(
                SkillReport.builder().skillId("S1").finalStatus("PASS").build(),
                SkillReport.builder().skillId("S2").finalStatus("PASS").build());

        ReportData report = reportService.buildReportData(task, reports, null);
        assertEquals(100, report.getOverallScore());
        assertEquals("2/2", report.getPassRate());
    }

    @Test
    @DisplayName("全部 FAIL 时得分为 0")
    void shouldScore0WhenAllFail() {
        DetectionTask task = DetectionTask.builder().taskId("TASK-001").targetIp("10.0.0.1").build();
        List<SkillReport> reports = List.of(
                SkillReport.builder().skillId("S1").finalStatus("FAIL").build());

        ReportData report = reportService.buildReportData(task, reports, null);
        assertEquals(0, report.getOverallScore());
        assertEquals("0/1", report.getPassRate());
    }

    @Test
    @DisplayName("持久化报告 JSON 到 reports/ 目录")
    void shouldPersistReportToDisk() throws Exception {
        DetectionTask task = DetectionTask.builder()
                .taskId("TASK-20260609-0001").targetIp("10.0.0.50").build();
        List<SkillReport> reports = List.of(
                SkillReport.builder().skillId("S1").skillName("Test").finalStatus("PASS").build());

        ReportData report = reportService.generateAndPersist(task, reports, null);

        Path expectedPath = reportsDir.resolve("TASK-20260609-0001.json");
        assertTrue(Files.exists(expectedPath));

        String content = Files.readString(expectedPath);
        assertTrue(content.contains("TASK-20260609-0001"));
        assertTrue(content.contains("10.0.0.50"));
    }

    @Test
    @DisplayName("加载已持久化的报告")
    void shouldLoadPersistedReport() throws Exception {
        DetectionTask task = DetectionTask.builder()
                .taskId("TASK-TEST-001").targetIp("10.0.0.50").build();
        List<SkillReport> reports = List.of(
                SkillReport.builder().skillId("S1").finalStatus("PASS").build());

        reportService.generateAndPersist(task, reports, null);

        ReportData loaded = reportService.loadReport("TASK-TEST-001");
        assertNotNull(loaded);
        assertEquals("TASK-TEST-001", loaded.getTaskId());
    }

    @Test
    @DisplayName("加载不存在的报告返回 null")
    void shouldReturnNullForMissingReport() {
        ReportData loaded = reportService.loadReport("NONEXISTENT-TASK");
        assertNull(loaded);
    }

    @Test
    @DisplayName("HTML 报告包含必填字段：得分环形图、通过率、PASS/FAIL 卡片")
    void shouldRenderHtmlReportWithRequiredSections() {
        DetectionTask task = DetectionTask.builder()
                .taskId("TASK-001").targetIp("10.0.0.50").build();

        SkillReport pass = SkillReport.builder()
                .skillId("S1").skillName("Pass Test").finalStatus("PASS")
                .contextEnvironment("linux-debian")
                .testReport(AiReportResponse.TestReport.builder()
                        .summary("All checks passed").riskLevel("LOW").evidence("OK").build())
                .securityRemediation(AiReportResponse.SecurityRemediation.builder()
                        .strategy("No action needed").build())
                .executionRecords(List.of(
                        ExecutionRecord.builder()
                                .command("cat /proc/1/status")
                                .result(ExecutionResult.builder().stdout("OK").exitCode(0).build())
                                .verdict(AiVerdict.builder().status("PASS").build())
                                .round(0).build()))
                .build();

        SkillReport fail = SkillReport.builder()
                .skillId("S2").skillName("Fail Test").finalStatus("FAIL")
                .isEvolved(true).evolutionType("command")
                .contextEnvironment("linux-alpine")
                .testReport(AiReportResponse.TestReport.builder()
                        .summary("Vulnerability found").riskLevel("CRITICAL")
                        .evidence("cap_net_admin enabled")
                        .affectedEnvironment("Alpine Container").build())
                .securityRemediation(AiReportResponse.SecurityRemediation.builder()
                        .strategy("Drop NET_ADMIN capability")
                        .k8sYamlPatch("capabilities:\n  drop:\n    - NET_ADMIN")
                        .alternativeAdvice("Use non-root user")
                        .environmentSpecificNotes("Alpine uses busybox").build())
                .build();

        ReportData report = reportService.buildReportData(task, List.of(pass, fail),
                EnvironmentFingerprint.builder().osType("linux").osFlavors(List.of("debian")).build());

        String html = reportService.renderHtmlReport(report);

        // Required sections
        assertTrue(html.contains("TASK-001"));
        assertTrue(html.contains("10.0.0.50"));
        assertTrue(html.contains("50")); // overallScore 50%
        assertTrue(html.contains("1/2")); // passRate
        assertTrue(html.contains("Pass Test"));
        assertTrue(html.contains("Fail Test"));
        assertTrue(html.contains("PASS"));
        assertTrue(html.contains("FAIL"));
        assertTrue(html.contains("CRITICAL"));
        assertTrue(html.contains("cap_net_admin"));
        assertTrue(html.contains("Drop NET_ADMIN"));
        assertTrue(html.contains("cat /proc/1/status"));
        // Timeline
        assertTrue(html.contains("timeline"));
        // Evolution badge
        assertTrue(html.contains("进化"));
    }
}
