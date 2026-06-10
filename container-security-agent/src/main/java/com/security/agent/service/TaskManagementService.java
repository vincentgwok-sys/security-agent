package com.security.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.security.agent.model.DetectionTask;
import com.security.agent.model.TaskStatus;
import com.security.agent.util.PathResolver;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class TaskManagementService {

    private static final Logger log = LoggerFactory.getLogger(TaskManagementService.class);

    @Value("${security-agent.report.output-directory:./reports}")
    private String configuredReportsDir;

    private final ObjectMapper objectMapper;
    private final Map<String, DetectionTask> taskStore = new ConcurrentHashMap<>();
    private final Map<String, java.util.concurrent.CompletableFuture<?>> runningFutures = new ConcurrentHashMap<>();
    private final AtomicInteger dailySeq = new AtomicInteger(0);
    private volatile String currentDate = "";
    private Path reportsDir;

    public TaskManagementService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        this.reportsDir = PathResolver.resolve(configuredReportsDir);

        // Ensure reports directory exists
        if (!Files.exists(reportsDir)) {
            try {
                Files.createDirectories(reportsDir);
                log.info("Reports 目录已创建: {}", reportsDir);
            } catch (IOException e) {
                log.error("无法创建 Reports 目录: {}", reportsDir, e);
                return;
            }
        }

        // Load historical tasks from disk
        try (Stream<Path> files = Files.list(reportsDir)) {
            List<Path> taskFiles = files
                    .filter(p -> p.getFileName().toString().endsWith(".task.json"))
                    .toList();

            int loaded = 0;
            int interrupted = 0;
            for (Path file : taskFiles) {
                try {
                    DetectionTask task = objectMapper.readValue(file.toFile(), DetectionTask.class);
                    // 服务重启后，未完成的任务标记为中断
                    if (task.getStatus() == TaskStatus.RUNNING || task.getStatus() == TaskStatus.CREATED) {
                        task.setStatus(TaskStatus.INTERRUPTED);
                        task.setErrorMessage("服务重启，检测中断");
                        task.setUpdatedAt(LocalDateTime.now());
                        persistTask(task);
                        interrupted++;
                    }
                    taskStore.put(task.getTaskId(), task);
                    loaded++;
                } catch (IOException e) {
                    log.error("任务文件反序列化失败: {}", file.getFileName(), e);
                }
            }

            // Restore daily sequence counter from existing tasks
            String today = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            String prefix = "TASK-" + today + "-";
            long todayCount = taskFiles.stream()
                    .filter(p -> p.getFileName().toString().startsWith(prefix))
                    .count();
            dailySeq.set((int) todayCount);
            currentDate = today;

            log.info("从磁盘恢复 {} 个历史任务 (今天已有 {} 个, 标记中断 {} 个)", loaded, todayCount, interrupted);
        } catch (IOException e) {
            log.error("Reports 目录扫描失败: {}", reportsDir, e);
        }
    }

    /**
     * 生成唯一 taskId：TASK-{yyyyMMdd}-{seq}
     */
    public String generateTaskId() {
        String today = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        synchronized (dailySeq) {
            if (!today.equals(currentDate)) {
                currentDate = today;
                dailySeq.set(0);
            }
            return String.format("TASK-%s-%04d", today, dailySeq.incrementAndGet());
        }
    }

    /**
     * 创建检测任务，立即持久化到磁盘。
     */
    public DetectionTask createTask(String targetIp, String sshUser, String sshPassword,
                                    int sshPort, List<String> skillIds, String parentTaskId) {
        String taskId = generateTaskId();
        DetectionTask task = DetectionTask.builder()
                .taskId(taskId)
                .targetIp(targetIp)
                .sshUser(sshUser)
                .sshPassword(sshPassword)
                .sshPort(sshPort > 0 ? sshPort : 22)
                .skillIds(skillIds)
                .status(TaskStatus.CREATED)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .parentTaskId(parentTaskId)
                .build();

        taskStore.put(taskId, task);
        persistTask(task);
        log.info("任务已创建并持久化: {}", taskId);
        return task;
    }

    /**
     * 更新任务状态，立即覆写持久化文件。
     */
    public DetectionTask updateStatus(String taskId, TaskStatus status) {
        DetectionTask task = taskStore.get(taskId);
        if (task == null) {
            log.warn("任务不存在: {}", taskId);
            return null;
        }
        task.setStatus(status);
        task.setUpdatedAt(LocalDateTime.now());
        persistTask(task);
        return task;
    }

    /**
     * 更新任务状态并记录错误信息，立即覆写持久化文件。
     */
    public DetectionTask updateStatus(String taskId, TaskStatus status, String errorMessage) {
        DetectionTask task = taskStore.get(taskId);
        if (task == null) return null;
        task.setStatus(status);
        task.setErrorMessage(errorMessage);
        task.setUpdatedAt(LocalDateTime.now());
        persistTask(task);
        return task;
    }

    /**
     * 获取任务。
     */
    public Optional<DetectionTask> getTask(String taskId) {
        return Optional.ofNullable(taskStore.get(taskId));
    }

    /**
     * 分页查询任务列表，按创建时间倒序。
     */
    public Map<String, Object> listTasks(int page, int size) {
        List<DetectionTask> sorted = taskStore.values().stream()
                .sorted(Comparator.comparing(DetectionTask::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());

        int total = sorted.size();
        int fromIndex = page * size;
        int toIndex = Math.min(fromIndex + size, total);

        List<DetectionTask> pageItems = fromIndex < total
                ? sorted.subList(fromIndex, toIndex)
                : Collections.emptyList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", pageItems);
        result.put("totalElements", total);
        result.put("totalPages", total > 0 ? (int) Math.ceil((double) total / size) : 0);
        result.put("number", page);
        result.put("size", size);
        return result;
    }

    /**
     * 获取所有任务（不分页）。
     */
    public List<DetectionTask> getAllTasks() {
        return taskStore.values().stream()
                .sorted(Comparator.comparing(DetectionTask::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());
    }

    // ──── Lifecycle ────

    /**
     * Register a running task's CompletableFuture for cancellation.
     */
    public void registerRunningTask(String taskId, java.util.concurrent.CompletableFuture<?> future) {
        runningFutures.put(taskId, future);
    }

    /**
     * Cancel a running task. Triggers future.cancel(true) which sends interrupt
     * to the worker thread, updates status to INTERRUPTED, and releases tracking.
     */
    public void cancelTask(String taskId) {
        DetectionTask task = taskStore.get(taskId);
        if (task == null) return;

        java.util.concurrent.CompletableFuture<?> future = runningFutures.remove(taskId);
        if (future != null) {
            future.cancel(true);
        }

        task.setStatus(TaskStatus.INTERRUPTED);
        task.setErrorMessage("用户手动终止");
        task.setUpdatedAt(LocalDateTime.now());
        persistTask(task);
        log.info("任务已终止: {}", taskId);
    }

    /**
     * Delete a non-running task from the store and its .task.json file.
     * Logs and reports under logs/{taskId}/ and reports/{taskId}.json are preserved.
     */
    public void deleteTask(String taskId) {
        DetectionTask task = taskStore.remove(taskId);
        if (task == null) return;

        // Delete the .task.json metadata file only — logs and reports stay
        Path filePath = reportsDir.resolve(taskId + ".task.json");
        try {
            Files.deleteIfExists(filePath);
            log.info("任务已删除: {} (日志和报告已保留)", taskId);
        } catch (IOException e) {
            log.error("任务文件删除失败: {} — {}", taskId, e.getMessage(), e);
        }
    }

    // ──── Persistence ────

    public void persistTask(DetectionTask task) {
        Path filePath = reportsDir.resolve(task.getTaskId() + ".task.json");
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(filePath.toFile(), task);
            log.debug("任务已持久化: {}", filePath.getFileName());
        } catch (IOException e) {
            log.error("任务持久化失败: {} — {}", task.getTaskId(), e.getMessage(), e);
            // 不抛异常：内存中的任务状态仍然有效
        }
    }
}
