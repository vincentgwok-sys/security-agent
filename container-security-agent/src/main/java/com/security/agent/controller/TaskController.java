package com.security.agent.controller;

import com.security.agent.model.*;
import com.security.agent.service.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private static final Logger log = LoggerFactory.getLogger(TaskController.class);

    private final TaskManagementService taskService;
    private final SkillLoaderService skillLoader;
    private final DetectionOrchestrator orchestrator;
    private final ReportGenerationService reportService;
    private final SshExecutionService sshService;
    private final ScriptGenerationService scriptGenerationService;
    private final OfflineResultService offlineResultService;

    @Value("${security-agent.report.output-directory:./reports}")
    private String configuredReportsDir;

    public TaskController(TaskManagementService taskService,
                          SkillLoaderService skillLoader,
                          DetectionOrchestrator orchestrator,
                          ReportGenerationService reportService,
                          SshExecutionService sshService,
                          ScriptGenerationService scriptGenerationService,
                          OfflineResultService offlineResultService) {
        this.taskService = taskService;
        this.skillLoader = skillLoader;
        this.orchestrator = orchestrator;
        this.reportService = reportService;
        this.sshService = sshService;
        this.scriptGenerationService = scriptGenerationService;
        this.offlineResultService = offlineResultService;
    }

    @PostMapping
    public ResponseEntity<?> createTask(@Valid @RequestBody CreateTaskRequest request) {
        log.info("收到创建任务请求: targetIp={}, skillIds={}, parentTaskId={}, connectionType={}",
                request.targetIp(), request.skillIds(), request.parentTaskId(), request.connectionType());

        String connType = request.connectionType() != null ? request.connectionType() : "ssh";

        // Validate SSH credentials for online modes
        if (!"offline".equals(connType)) {
            if (request.targetIp() == null || request.targetIp().isBlank()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "在线模式下 targetIp 为必填项"));
            }
            if (request.sshUser() == null || request.sshUser().isBlank()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "在线模式下 sshUser 为必填项"));
            }
            if (request.sshPassword() == null || request.sshPassword().isBlank()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "在线模式下 sshPassword 为必填项"));
            }
        }

        // Validate skills exist
        Map<String, SkillDefinition> skills = skillLoader.loadLatestSkills();
        for (String skillId : request.skillIds()) {
            if (!skills.containsKey(skillId)) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Skill 不存在: " + skillId));
            }
        }

        DetectionTask task = taskService.createTask(
                request.targetIp(),
                request.sshUser(),
                request.sshPassword(),
                request.sshPort() != null ? request.sshPort() : 22,
                request.skillIds(),
                request.parentTaskId());

        // Set kubectl fields if using jumpbox mode
        if ("kubectl".equals(connType)) {
            task.setConnectionType("kubectl");
            task.setTargetPod(request.targetPod());
            task.setTargetNamespace(request.targetNamespace());
            taskService.persistTask(task);
            log.info("任务使用 kubectl 模式: pod={}, namespace={}", request.targetPod(), request.targetNamespace());
        }

        // Handle offline mode: set connection type, generate script + token, return immediately
        if ("offline".equals(connType)) {
            task.setConnectionType("offline");
            String token = taskService.generateOfflineToken(task.getTaskId());
            // Generate and cache the script
            String script = scriptGenerationService.generateScript(
                    task.getTaskId(), request.skillIds());
            taskService.cacheScript(task.getTaskId(), script);
            taskService.persistTask(task);
            log.info("线下任务已创建: {}, downloadToken 已生成", task.getTaskId());
            return ResponseEntity.accepted()
                    .body(Map.of("taskId", task.getTaskId(), "status", task.getStatus().name(),
                            "downloadToken", token));
        }

        // Check retry from offline task
        if (request.parentTaskId() != null && !request.parentTaskId().isBlank()) {
            var parentTask = taskService.getTask(request.parentTaskId());
            if (parentTask.isPresent() && "offline".equals(parentTask.get().getConnectionType())) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "线下任务不支持重试，请重新创建任务"));
            }
        }

        // Async launch detection (online modes only)
        final String taskConnType = connType;
        CompletableFuture<?> detectionFuture = CompletableFuture.runAsync(() -> {
            try {
                taskService.updateStatus(task.getTaskId(), TaskStatus.RUNNING);
                List<SkillDefinition> selectedSkills = request.skillIds().stream()
                        .map(skills::get)
                        .toList();
                List<SkillReport> reports = orchestrator.executeDetection(selectedSkills, task);

                // Collect target environment from the first report that has one
                EnvironmentFingerprint targetEnv = extractEnvironment(reports, selectedSkills);

                // Persist the report so the frontend can load it
                reportService.generateAndPersist(task, reports, targetEnv);

                taskService.updateStatus(task.getTaskId(), TaskStatus.COMPLETED);
                log.info("任务完成: {}, 报告已持久化", task.getTaskId());
            } catch (Exception e) {
                log.error("任务执行失败: {}", task.getTaskId(), e);
                if (!(e instanceof InterruptedException || e.getCause() instanceof InterruptedException)) {
                    taskService.updateStatus(task.getTaskId(), TaskStatus.FAILED, e.getMessage());
                }

                // Still persist whatever partial results we have
                try {
                    reportService.generateAndPersist(task,
                            Collections.singletonList(buildErrorReport(e)),
                            null);
                } catch (Exception ignored) {}
            }
        });

        // Register for cancellation
        taskService.registerRunningTask(task.getTaskId(), detectionFuture);

        return ResponseEntity.accepted()
                .body(Map.of("taskId", task.getTaskId(), "status", task.getStatus().name()));
    }

    private EnvironmentFingerprint extractEnvironment(List<SkillReport> reports, List<SkillDefinition> skills) {
        // Try to get fingerprint from the first skill's first context
        if (skills != null && !skills.isEmpty()) {
            SkillDefinition first = skills.get(0);
            if (first.getExecutionContexts() != null && !first.getExecutionContexts().isEmpty()) {
                ExecutionContext ctx = first.getExecutionContexts().get(0);
                return ctx.getEnvironmentFingerprint();
            }
        }
        return null;
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
                        .summary("检测流程异常: " + (e.getMessage() != null ? e.getMessage() : "未知错误"))
                        .riskLevel("INFO")
                        .evidence("")
                        .build())
                .securityRemediation(AiReportResponse.SecurityRemediation.builder()
                        .strategy("请检查 LLM API 配置和 SSH 连接后重试")
                        .build())
                .build();
    }

    @GetMapping
    public ResponseEntity<?> listTasks(@RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(taskService.listTasks(page, size));
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<?> getTask(@PathVariable String taskId) {
        return taskService.getTask(taskId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{taskId}/cancel")
    public ResponseEntity<?> cancelTask(@PathVariable String taskId) {
        var task = taskService.getTask(taskId);
        if (task.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        TaskStatus status = task.get().getStatus();
        if (status != TaskStatus.RUNNING && status != TaskStatus.CREATED) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "只有运行中或等待中的任务才能终止"));
        }
        taskService.cancelTask(taskId);
        // Release SSH sessions for the target host
        try {
            sshService.release(task.get().getTargetIp() + ":" + task.get().getSshPort());
        } catch (Exception ignored) {}
        log.info("用户请求终止任务: {}", taskId);
        return ResponseEntity.ok(Map.of("taskId", taskId, "status", "INTERRUPTED"));
    }

    @DeleteMapping("/{taskId}")
    public ResponseEntity<?> deleteTask(@PathVariable String taskId) {
        var task = taskService.getTask(taskId);
        if (task.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        TaskStatus status = task.get().getStatus();
        if (status == TaskStatus.RUNNING) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "运行中的任务无法删除，请先终止"));
        }
        taskService.deleteTask(taskId);
        log.info("用户请求删除任务: {}", taskId);
        return ResponseEntity.ok(Map.of("taskId", taskId, "deleted", true));
    }

    // ──── Offline Script Download ────

    @GetMapping("/{taskId}/script/download")
    public ResponseEntity<?> downloadScript(@PathVariable String taskId,
                                            @RequestParam String token) {
        var taskOpt = taskService.getTask(taskId);
        if (taskOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        DetectionTask task = taskOpt.get();
        if (!"offline".equals(task.getConnectionType())) {
            return ResponseEntity.notFound().build();
        }
        if (!taskService.validateAndConsumeToken(taskId, token)) {
            return ResponseEntity.status(404)
                    .body(Map.of("error", "下载令牌无效或已过期"));
        }

        String script = taskService.getCachedScript(taskId);
        if (script == null) {
            return ResponseEntity.status(410)
                    .body(Map.of("error", "脚本已过期，请重新创建任务"));
        }

        taskService.removeCachedScript(taskId);

        byte[] scriptBytes = script.getBytes(StandardCharsets.UTF_8);
        ByteArrayResource resource = new ByteArrayResource(scriptBytes);

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"security_scan_" + taskId + ".py\"")
                .body(resource);
    }

    // ──── Offline Result Upload ────

    @PostMapping("/{taskId}/results/upload")
    public ResponseEntity<?> uploadResults(@PathVariable String taskId,
                                           @RequestParam("file") MultipartFile file) {
        var taskOpt = taskService.getTask(taskId);
        if (taskOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        DetectionTask task = taskOpt.get();
        if (!"offline".equals(task.getConnectionType())) {
            return ResponseEntity.status(404)
                    .body(Map.of("error", "此端点仅适用于线下执行任务"));
        }
        TaskStatus status = task.getStatus();
        if (status == TaskStatus.COMPLETED || status == TaskStatus.FAILED
                || status == TaskStatus.ANALYZING) {
            return ResponseEntity.status(409)
                    .body(Map.of("error", "任务当前状态不允许上传: " + status));
        }

        if (file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "上传文件不能为空"));
        }

        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".zip")) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "仅支持 ZIP 格式文件"));
        }

        log.info("收到线下结果上传: taskId={}, fileName={}, size={}",
                taskId, filename, file.getSize());

        // Validate ZIP and extract data
        OfflineResultService.ValidationResult validation;
        try {
            validation = offlineResultService.validateAndExtract(
                    file.getInputStream(), taskId);
        } catch (Exception e) {
            log.error("[{}] ZIP 解析失败", taskId, e);
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "无效的 ZIP 文件格式"));
        }

        if (!validation.isSuccess()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", validation.getErrorMessage()));
        }

        // Save original ZIP for traceability
        try {
            offlineResultService.saveOriginalZip(file.getBytes(), taskId);
        } catch (Exception e) {
            log.error("[{}] ZIP 保存失败", taskId, e);
        }

        // Mark uploaded and trigger async replay
        taskService.markResultsUploaded(taskId);

        offlineResultService.triggerReplay(task,
                validation.getExecutionRecords(),
                validation.getFingerprint(),
                validation.getEvolvedSkills());

        return ResponseEntity.ok()
                .body(Map.of("taskId", taskId, "message", "结果已上传，正在进行分析"));
    }

    @GetMapping("/{taskId}/results/download")
    public ResponseEntity<?> downloadResults(@PathVariable String taskId) {
        Path reportsDir = com.security.agent.util.PathResolver.resolve(configuredReportsDir);
        Path zipPath = reportsDir.resolve(taskId + "_offline_result.zip");
        if (!Files.exists(zipPath)) {
            return ResponseEntity.notFound().build();
        }
        try {
            byte[] bytes = Files.readAllBytes(zipPath);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"task_" + taskId + "_result.zip\"")
                    .body(new ByteArrayResource(bytes));
        } catch (IOException e) {
            return ResponseEntity.status(500)
                    .body(Map.of("error", "文件读取失败"));
        }
    }

    public record CreateTaskRequest(
            String targetIp,
            String sshUser,
            String sshPassword,
            Integer sshPort,
            @NotEmpty List<String> skillIds,
            String parentTaskId,
            String connectionType,
            String targetPod,
            String targetNamespace) {}
}
