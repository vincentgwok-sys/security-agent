package com.security.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Represents the manifest.json inside an offline execution result ZIP.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OfflineManifest {

    private String taskId;
    private String scriptVersion;
    private String executionStartedAt;
    private String executionEndedAt;
    private String hostname;
    private String pythonVersion;
    private List<String> skillIds;
    private List<SkillResultEntry> skillResults;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkillResultEntry {
        private String skillId;
        private String status; // COMPLETED, SKIPPED, ERROR
        private String errorMessage;
    }
}
