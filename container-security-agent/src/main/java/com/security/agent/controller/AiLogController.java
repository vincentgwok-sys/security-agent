package com.security.agent.controller;

import com.security.agent.service.AiLogService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/logs")
public class AiLogController {

    private final AiLogService aiLogService;

    public AiLogController(AiLogService aiLogService) {
        this.aiLogService = aiLogService;
    }

    @GetMapping("/ai")
    public ResponseEntity<Map<String, Object>> queryAiLogs(
            @RequestParam String taskId,
            @RequestParam(required = false) String skillId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(aiLogService.query(taskId, skillId, page, size));
    }
}
