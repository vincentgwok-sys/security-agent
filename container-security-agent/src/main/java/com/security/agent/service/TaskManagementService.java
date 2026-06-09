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
            for (Path file : taskFiles) {
                try {
                    DetectionTask task = objectMapper.readValue(file.toFile(), DetectionTask.class);
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

            log.info("从磁盘恢复 {} 个历史任务 (今天已有 {} 个)", loaded, todayCount);
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
                                    int sshPort, List<String> skillIds) {
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

    // ──── Persistence ────

    private void persistTask(DetectionTask task) {
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
