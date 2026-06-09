package com.security.agent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 单次 AI 调用的完整审计记录。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiLogEntry(
        String logId,
        String taskId,
        String skillId,
        String phase,         // phase0 | phase1 | context-evolution | phase2 | json-repair
        String systemPrompt,
        String userPrompt,
        String rawResponse,
        String cleanedJson,
        String parseResult,   // SUCCESS | REPAIR_SUCCESS | FAILED
        String targetClass,
        long costMs,
        long timestamp
) {
    public AiLogEntry {
        if (logId == null || logId.isBlank()) {
            throw new IllegalArgumentException("logId is required");
        }
    }
}
