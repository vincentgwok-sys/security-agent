package com.security.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillReport {

    private String skillId;
    private String skillName;
    private String finalStatus;          // PASS | FAIL
    private String usedContextId;
    private String contextEnvironment;   // 环境描述
    private String evolutionType;        // command | context | none
    private boolean isEvolved;

    private List<ExecutionRecord> executionRecords;

    private AiReportResponse.TestReport testReport;
    private AiReportResponse.SecurityRemediation securityRemediation;
}
