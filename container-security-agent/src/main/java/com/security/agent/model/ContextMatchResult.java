package com.security.agent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ContextMatchResult {

    private String matchResult;       // MATCHED | PARTIAL | NONE
    private String selectedContextId;
    private String matchReasoning;
    private String environmentSummary;

    private NewContextSuggestion newContextSuggestion;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class NewContextSuggestion {
        @Builder.Default
        private boolean included = false;
        private String contextId;
        private EnvironmentFingerprint environmentFingerprint;
        private java.util.List<String> envCheckCommands;
        private ExecutionContext.ExecutionLogic executionLogic;
    }
}
