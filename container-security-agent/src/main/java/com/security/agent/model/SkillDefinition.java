package com.security.agent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SkillDefinition {

    private String skillId;
    private String skillName;
    private long versionTimestamp;
    private String riskLevel;       // CRITICAL | HIGH | LOW | INFO
    private String description;
    private int evolutionCount;

    private ReportMetadata reportMetadata;

    @Builder.Default
    private List<ExecutionContext> executionContexts = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ReportMetadata {
        private String remediationFocus;
    }
}
