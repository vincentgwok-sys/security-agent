package com.security.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.security.agent.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Handles offline execution result ZIP upload, validation, extraction,
 * and triggers AI replay analysis.
 */
@Service
public class OfflineResultService {

    private static final Logger log = LoggerFactory.getLogger(OfflineResultService.class);

    private final ObjectMapper objectMapper;
    private final TaskManagementService taskService;
    private final SkillLoaderService skillLoader;
    private final DetectionOrchestrator orchestrator;
    private final ReportGenerationService reportService;
    private final Path reportsDir;

    public OfflineResultService(ObjectMapper objectMapper,
                                TaskManagementService taskService,
                                SkillLoaderService skillLoader,
                                DetectionOrchestrator orchestrator,
                                ReportGenerationService reportService,
                                @org.springframework.beans.factory.annotation.Value(
                                        "${security-agent.report.output-directory:./reports}") String configuredReportsDir) {
        this.objectMapper = objectMapper;
        this.taskService = taskService;
        this.skillLoader = skillLoader;
        this.orchestrator = orchestrator;
        this.reportService = reportService;
        this.reportsDir = com.security.agent.util.PathResolver.resolve(configuredReportsDir);
    }

    /**
     * Validate a ZIP input stream and extract all result data.
     * Returns a result bundle ready for replay.
     */
    public ValidationResult validateAndExtract(InputStream zipStream, String taskId) throws IOException {
        Map<String, byte[]> entries = readZipEntries(zipStream);

        // 1. Validate manifest.json
        byte[] manifestBytes = entries.get("manifest.json");
        if (manifestBytes == null) {
            log.error("[{}] 缺少 manifest.json", taskId);
            return ValidationResult.error("缺少 manifest.json");
        }
        OfflineManifest manifest;
        try {
            manifest = objectMapper.readValue(manifestBytes, OfflineManifest.class);
        } catch (IOException e) {
            log.error("[{}] manifest.json 解析失败", taskId, e);
            return ValidationResult.error("manifest.json 格式无效");
        }
        if (!taskId.equals(manifest.getTaskId())) {
            log.error("[{}] manifest taskId 不匹配: {}", taskId, manifest.getTaskId());
            return ValidationResult.error("manifest 中的 taskId 与上传路径不匹配");
        }

        // 2. Validate fingerprint.json
        byte[] fpBytes = entries.get("fingerprint.json");
        if (fpBytes == null) {
            log.error("[{}] 缺少 fingerprint.json", taskId);
            return ValidationResult.error("缺少 fingerprint.json");
        }
        EnvironmentFingerprint fingerprint;
        try {
            fingerprint = objectMapper.readValue(fpBytes, EnvironmentFingerprint.class);
        } catch (IOException e) {
            log.error("[{}] fingerprint.json 解析失败", taskId, e);
            return ValidationResult.error("fingerprint.json 格式无效");
        }

        // 3. Validate execution_results/ entries
        List<String> expectedSkillIds = manifest.getSkillIds();
        Map<String, List<ExecutionRecord>> executionRecords = new HashMap<>();
        for (String skillId : expectedSkillIds) {
            String key = "execution_results/" + skillId + ".json";
            byte[] skillBytes = entries.get(key);
            if (skillBytes == null) {
                log.warn("[{}] 缺少 execution_results/{}.json", taskId, skillId);
                continue;
            }
            try {
                ExecutionRecord[] records = objectMapper.readValue(
                        skillBytes, ExecutionRecord[].class);
                executionRecords.put(skillId, Arrays.asList(records));
            } catch (IOException e) {
                log.error("[{}] execution_results/{}.json 解析失败", taskId, skillId, e);
                return ValidationResult.error(
                        "execution_results/" + skillId + ".json 格式无效");
            }
        }
        if (executionRecords.isEmpty()) {
            return ValidationResult.error("execution_results 目录中没有有效的执行结果文件");
        }

        // 4. Extract evolved skills if present
        List<SkillDefinition> evolvedSkills = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            if (entry.getKey().startsWith("evolved_skills/")
                    && entry.getKey().endsWith(".json")) {
                try {
                    SkillDefinition skill = objectMapper.readValue(
                            entry.getValue(), SkillDefinition.class);
                    evolvedSkills.add(skill);
                    log.info("[{}] 发现进化 Skill: {}", taskId, skill.getSkillId());
                } catch (IOException e) {
                    log.warn("[{}] evolved_skill 解析失败: {}", taskId, entry.getKey(), e);
                }
            }
        }

        return ValidationResult.success(manifest, fingerprint, executionRecords, evolvedSkills);
    }

    /**
     * Save the uploaded ZIP to disk for traceability.
     */
    public void saveOriginalZip(byte[] zipBytes, String taskId) throws IOException {
        Path zipPath = reportsDir.resolve(taskId + "_offline_result.zip");
        Files.write(zipPath, zipBytes);
        log.info("[{}] 原始 ZIP 已保存: {}", taskId, zipPath);
    }

    /**
     * Trigger async AI replay for offline results.
     */
    public void triggerReplay(DetectionTask task, Map<String, List<ExecutionRecord>> executionRecords,
                              EnvironmentFingerprint fingerprint, List<SkillDefinition> uploadedSkills) {
        // Merge uploaded skills with platform skills
        List<SkillDefinition> mergedSkills = mergeUploadedSkills(uploadedSkills);

        // Resolve skills for this task
        List<String> taskSkillIds = task.getSkillIds();
        List<SkillDefinition> selectedSkills = new ArrayList<>();
        for (String sid : taskSkillIds) {
            // Prefer merged skill if available
            SkillDefinition found = null;
            for (SkillDefinition ms : mergedSkills) {
                if (ms.getSkillId().equals(sid)) {
                    found = ms;
                    break;
                }
            }
            if (found != null) {
                selectedSkills.add(found);
            }
        }

        log.info("[{}] 启动线下回放: {} skills, {} with records",
                task.getTaskId(), selectedSkills.size(), executionRecords.size());

        // Execute replay (async)
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                taskService.updateStatus(task.getTaskId(), TaskStatus.ANALYZING);
                List<SkillReport> reports = orchestrator.executeOfflineReplay(
                        task, selectedSkills, fingerprint, executionRecords);
                reportService.generateAndPersist(task, reports, fingerprint);
                taskService.updateStatus(task.getTaskId(), TaskStatus.COMPLETED);
                log.info("[{}] 线下回放完成", task.getTaskId());
            } catch (Exception e) {
                log.error("[{}] 线下回放失败", task.getTaskId(), e);
                taskService.updateStatus(task.getTaskId(), TaskStatus.FAILED,
                        "回放失败: " + e.getMessage());
                try {
                    reportService.generateAndPersist(task,
                            Collections.singletonList(buildErrorReport(e)), null);
                } catch (Exception ignored) {}
            }
        });
    }

    private List<SkillDefinition> mergeUploadedSkills(List<SkillDefinition> uploadedSkills) {
        List<SkillDefinition> merged = new ArrayList<>();
        Map<String, SkillDefinition> platformSkills = skillLoader.loadLatestSkills();
        for (SkillDefinition uploaded : uploadedSkills) {
            SkillDefinition mergedSkill = skillLoader.mergeUploadedSkill(uploaded);
            merged.add(mergedSkill != null ? mergedSkill : uploaded);
        }
        return merged;
    }

    private Map<String, byte[]> readZipEntries(InputStream zipStream) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(zipStream, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                byte[] content = zis.readAllBytes();
                entries.put(entry.getName(), content);
                zis.closeEntry();
            }
        }
        return entries;
    }

    private SkillReport buildErrorReport(Exception e) {
        return SkillReport.builder()
                .skillId("SYSTEM")
                .skillName("系统错误")
                .finalStatus("FAIL")
                .evolutionType("none")
                .isEvolved(false)
                .executionRecords(Collections.emptyList())
                .testReport(AiReportResponse.TestReport.builder()
                        .summary("回放分析异常: " + (e.getMessage() != null ? e.getMessage() : "未知错误"))
                        .riskLevel("INFO")
                        .evidence("")
                        .build())
                .securityRemediation(AiReportResponse.SecurityRemediation.builder()
                        .strategy("请检查 AI API 配置后重试")
                        .build())
                .build();
    }

    /**
     * Result of ZIP validation.
     */
    public static class ValidationResult {
        private final boolean success;
        private final String errorMessage;
        private final OfflineManifest manifest;
        private final EnvironmentFingerprint fingerprint;
        private final Map<String, List<ExecutionRecord>> executionRecords;
        private final List<SkillDefinition> evolvedSkills;

        private ValidationResult(boolean success, String errorMessage) {
            this.success = success;
            this.errorMessage = errorMessage;
            this.manifest = null;
            this.fingerprint = null;
            this.executionRecords = null;
            this.evolvedSkills = null;
        }

        private ValidationResult(OfflineManifest manifest, EnvironmentFingerprint fingerprint,
                                 Map<String, List<ExecutionRecord>> executionRecords,
                                 List<SkillDefinition> evolvedSkills) {
            this.success = true;
            this.errorMessage = null;
            this.manifest = manifest;
            this.fingerprint = fingerprint;
            this.executionRecords = executionRecords;
            this.evolvedSkills = evolvedSkills;
        }

        public static ValidationResult error(String message) {
            return new ValidationResult(false, message);
        }

        public static ValidationResult success(OfflineManifest manifest,
                                                EnvironmentFingerprint fingerprint,
                                                Map<String, List<ExecutionRecord>> executionRecords,
                                                List<SkillDefinition> evolvedSkills) {
            return new ValidationResult(manifest, fingerprint, executionRecords, evolvedSkills);
        }

        public boolean isSuccess() { return success; }
        public String getErrorMessage() { return errorMessage; }
        public OfflineManifest getManifest() { return manifest; }
        public EnvironmentFingerprint getFingerprint() { return fingerprint; }
        public Map<String, List<ExecutionRecord>> getExecutionRecords() { return executionRecords; }
        public List<SkillDefinition> getEvolvedSkills() { return evolvedSkills; }
    }
}
