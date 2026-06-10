package com.security.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DetectionTask {

    private String taskId;
    private String targetIp;
    private String sshUser;
    private String sshPassword;
    private int sshPort;
    private List<String> skillIds;
    private TaskStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String errorMessage;
    private String parentTaskId;
    private String connectionType;   // "ssh" (default) or "kubectl"
    private String targetPod;        // for kubectl mode: pod name
    private String targetNamespace;  // for kubectl mode: namespace
    private String offlineDownloadToken;  // one-time token for script download
    private LocalDateTime scriptDownloadedAt;
    private LocalDateTime resultUploadedAt;
}
