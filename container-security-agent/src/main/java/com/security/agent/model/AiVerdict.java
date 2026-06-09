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
public class AiVerdict {

    private String status;          // PASS | FAIL | EVOLVE | ENV_MISMATCH
    private String reasoning;
    private String nextCommand;     // EVOLVE 时填写替代命令
    private String evidence;        // 从回显提取的关键证据
    private String riskJustification; // FAIL 时的风险说明
}
