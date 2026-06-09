package com.security.agent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiReportResponse {

    private TestReport testReport;
    private SecurityRemediation securityRemediation;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TestReport {
        private String summary;             // 80字以内中文总结
        private String riskLevel;           // CRITICAL | HIGH | LOW | INFO
        private String evidence;            // 关键证据文本
        private String affectedEnvironment; // 受影响的环境描述
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SecurityRemediation {
        private String strategy;                // 修复策略描述
        private String k8sYamlPatch;            // K8s YAML 补丁
        private String alternativeAdvice;       // 替代建议
        private String environmentSpecificNotes; // 环境特定注意事项
    }
}
