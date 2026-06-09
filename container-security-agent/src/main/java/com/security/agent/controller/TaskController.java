package com.security.agent.controller;

import com.security.agent.model.*;
import com.security.agent.service.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

    public TaskController(TaskManagementService taskService,
                          SkillLoaderService skillLoader,
                          DetectionOrchestrator orchestrator,
                          ReportGenerationService reportService) {
        this.taskService = taskService;
        this.skillLoader = skillLoader;
        this.orchestrator = orchestrator;
        this.reportService = reportService;
    }

    @PostMapping
    public ResponseEntity<?> createTask(@Valid @RequestBody CreateTaskRequest request) {
        log.info("收到创建任务请求: targetIp={}, skillIds={}", request.targetIp(), request.skillIds());

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
                request.skillIds());

        // Async launch detection
        CompletableFuture.runAsync(() -> {
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
                taskService.updateStatus(task.getTaskId(), TaskStatus.FAILED, e.getMessage());

                // Still persist whatever partial results we have
                try {
                    reportService.generateAndPersist(task,
                            Collections.singletonList(buildErrorReport(e)),
                            null);
                } catch (Exception ignored) {}
            }
        });

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

    public record CreateTaskRequest(
            @NotBlank String targetIp,
            @NotBlank String sshUser,
            @NotBlank String sshPassword,
            Integer sshPort,
            @NotEmpty List<String> skillIds) {}
}
