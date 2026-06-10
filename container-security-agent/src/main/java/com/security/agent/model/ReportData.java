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
public class ReportData {

    private String taskId;
    private String targetIp;
    private String auditTime;
    private int overallScore;
    private String passRate;
    private EnvironmentFingerprint targetEnvironment;
    private List<SkillReport> skillReports;
    private String connectionType;  // "ssh", "kubectl", or "offline"
}
