package com.security.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionResult {

    private String stdout;
    private String stderr;
    private int exitCode;

    @Builder.Default
    private boolean blocked = false;

    public boolean isBlocked() {
        return blocked;
    }
}
