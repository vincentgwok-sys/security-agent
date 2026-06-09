package com.security.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuleVerdict {

    private String verdict;         // ALLOW | BLOCK | WARN
    private String matchedRuleId;   // 命中的规则 ID，未命中为 null
    private String message;         // 拦截/警告原因
    private String originalCommand; // 原始命令
}
