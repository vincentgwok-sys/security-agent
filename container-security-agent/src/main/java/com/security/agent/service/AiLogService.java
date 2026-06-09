package com.security.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.security.agent.model.AiLogEntry;
import com.security.agent.util.PathResolver;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.stream.Stream;

@Service
public class AiLogService {

    private static final Logger log = LoggerFactory.getLogger(AiLogService.class);

    @Value("${security-agent.logs.directory:./logs}")
    private String configuredLogsDir;

    private final ObjectMapper objectMapper;
    private Path logsDir;

    public AiLogService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        this.logsDir = PathResolver.resolve(configuredLogsDir);
        if (!Files.exists(logsDir)) {
            try {
                Files.createDirectories(logsDir);
                log.info("Logs 目录已创建: {}", logsDir);
            } catch (IOException e) {
                log.error("无法创建 Logs 目录: {}", logsDir, e);
            }
        }
    }

    /**
     * 记录一条 AI 日志，以 NDJSON 追加写入 logs/{taskId}/{skillId}.jsonl。
     */
    public void log(AiLogEntry entry) {
        Path taskDir = logsDir.resolve(entry.taskId());
        try {
            if (!Files.exists(taskDir)) {
                Files.createDirectories(taskDir);
            }
        } catch (IOException e) {
            log.error("无法创建日志目录: {}", taskDir, e);
            return;
        }

        Path logFile = taskDir.resolve(entry.skillId() + ".jsonl");
        try {
            String line = objectMapper.writeValueAsString(entry) + "\n";
            Files.writeString(logFile, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            log.debug("AI 日志已写入: {}", logFile.getFileName());
        } catch (IOException e) {
            log.error("AI 日志写入失败: {} — {}", logFile, e.getMessage(), e);
        }
    }

    /**
     * 查询 AI 日志，支持按 taskId（必填）和 skillId（可选）过滤，分页返回。
     */
    public Map<String, Object> query(String taskId, String skillId, int page, int size) {
        Path taskDir = logsDir.resolve(taskId);
        if (!Files.exists(taskDir)) {
            return emptyPage(page, size);
        }

        List<AiLogEntry> allEntries = new ArrayList<>();

        try (Stream<Path> files = Files.list(taskDir)) {
            List<Path> jsonlFiles;
            if (skillId != null && !skillId.isBlank()) {
                jsonlFiles = files
                        .filter(p -> p.getFileName().toString().equals(skillId + ".jsonl"))
                        .toList();
            } else {
                jsonlFiles = files
                        .filter(p -> p.toString().endsWith(".jsonl"))
                        .toList();
            }

            for (Path file : jsonlFiles) {
                try {
                    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    for (String line : lines) {
                        if (line.isBlank()) continue;
                        try {
                            AiLogEntry entry = objectMapper.readValue(line, AiLogEntry.class);
                            allEntries.add(entry);
                        } catch (IOException e) {
                            log.warn("日志行解析失败: {}", line.substring(0, Math.min(80, line.length())));
                        }
                    }
                } catch (IOException e) {
                    log.warn("日志文件读取失败: {}", file, e);
                }
            }
        } catch (IOException e) {
            log.error("日志目录扫描失败: {}", taskDir, e);
            return emptyPage(page, size);
        }

        // Sort by timestamp descending
        allEntries.sort(Comparator.comparingLong(AiLogEntry::timestamp).reversed());

        int total = allEntries.size();
        int fromIndex = page * size;
        int toIndex = Math.min(fromIndex + size, total);
        List<AiLogEntry> pageItems = fromIndex < total
                ? allEntries.subList(fromIndex, toIndex)
                : Collections.emptyList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", pageItems);
        result.put("totalElements", total);
        result.put("totalPages", total > 0 ? (int) Math.ceil((double) total / size) : 0);
        result.put("number", page);
        result.put("size", size);
        return result;
    }

    private Map<String, Object> emptyPage(int page, int size) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", Collections.emptyList());
        result.put("totalElements", 0);
        result.put("totalPages", 0);
        result.put("number", page);
        result.put("size", size);
        return result;
    }
}
