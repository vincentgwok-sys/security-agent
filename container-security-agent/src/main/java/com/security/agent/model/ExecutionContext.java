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
public class ExecutionContext {

    private String contextId;
    private EnvironmentFingerprint environmentFingerprint;

    @Builder.Default
    private List<String> envCheckCommands = new ArrayList<>();

    private ExecutionLogic executionLogic;

    private String evolvedFrom;    // null = 初始预置, contextId = 进化来源, "ai-generated" = AI 生成
    private Long evolvedAt;        // Unix 毫秒时间戳

    @Builder.Default
    private boolean deprecated = false;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ExecutionLogic {
        private String expectedBehavior;

        @Builder.Default
        private List<String> detectionCommands = new ArrayList<>();
    }
}
