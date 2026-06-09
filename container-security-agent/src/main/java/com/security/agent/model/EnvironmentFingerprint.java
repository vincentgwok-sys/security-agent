package com.security.agent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EnvironmentFingerprint {

    private String osType;           // linux | windows
    @Builder.Default
    private List<String> osFlavors = new ArrayList<>();  // debian, ubuntu, alpine, centos, windows-servercore
    private String osVersion;        // 3.19.1, 22.04, ltsc2022
    private String shellType;        // bash | ash | sh | cmd | powershell
    @Builder.Default
    private List<String> requiredTools = new ArrayList<>();
    @Builder.Default
    private List<String> optionalTools = new ArrayList<>();
    private String minKernelVersion;
    @Builder.Default
    private List<String> arch = new ArrayList<>();       // amd64 | arm64
    private String kernelVersion;

    /**
     * 从 SSH 探针命令执行结果解析环境指纹。
     * 期望 result.stdout 中每条探针命令的输出以 "---NEXT_PROBE---" 分隔。
     */
    public static EnvironmentFingerprint fromProbeResult(ExecutionResult result) {
        if (result == null || result.getStdout() == null) {
            return EnvironmentFingerprint.builder().osType("unknown").build();
        }

        String output = result.getStdout();
        String[] sections = output.split("---NEXT_PROBE---");

        EnvironmentFingerprintBuilder builder = EnvironmentFingerprint.builder()
                .osFlavors(new ArrayList<>())
                .requiredTools(new ArrayList<>())
                .optionalTools(new ArrayList<>())
                .arch(new ArrayList<>());

        for (String section : sections) {
            String trimmed = section.trim();
            if (trimmed.isEmpty()) continue;

            // Parse uname -s (OS type)
            if (trimmed.matches("(?i)(Linux|Darwin|MINGW.*|MSYS.*|Windows_NT)")) {
                if (trimmed.equalsIgnoreCase("Linux")) builder.osType("linux");
                else if (trimmed.contains("Windows")) builder.osType("windows");
                else builder.osType(trimmed.toLowerCase());
            }
            // Parse OS release ID
            else if (trimmed.startsWith("ID=") || trimmed.contains("ID=")) {
                String flavor = trimmed.replaceAll(".*ID=\"?([^\n\"]+)\"?.*", "$1").trim().toLowerCase();
                builder.osFlavors(new ArrayList<>(List.of(flavor)));
                // Try to extract version
                if (trimmed.contains("VERSION_ID=")) {
                    String ver = trimmed.replaceAll(".*VERSION_ID=\"?([^\n\"]+)\"?.*", "$1").trim();
                    builder.osVersion(ver);
                }
            }
            // Parse kernel version
            else if (trimmed.matches("\\d+\\.\\d+\\.\\d+.*")) {
                builder.kernelVersion(trimmed);
            }
            // Parse shell
            else if (trimmed.contains("/") && (trimmed.endsWith("bash") || trimmed.endsWith("ash")
                    || trimmed.endsWith("sh") || trimmed.endsWith("zsh") || trimmed.endsWith("cmd"))) {
                if (trimmed.contains("bash")) builder.shellType("bash");
                else if (trimmed.contains("ash")) builder.shellType("ash");
                else if (trimmed.contains("zsh")) builder.shellType("zsh");
                else builder.shellType("sh");
            }
            // Parse which output (available tools)
            else if (trimmed.contains("/")) {
                // which command outputs paths like /usr/bin/cat /usr/bin/grep, etc.
                Set<String> tools = new HashSet<>();
                for (String part : trimmed.split("\\s+")) {
                    if (part.startsWith("/")) {
                        String tool = part.substring(part.lastIndexOf('/') + 1);
                        tools.add(tool);
                    }
                }
                if (!tools.isEmpty()) {
                    builder.requiredTools(new ArrayList<>(tools));
                }
            }
            // Parse arch
            else if (trimmed.matches("(?i)(x86_64|amd64|aarch64|arm64|armv7l)")) {
                String arch = trimmed.toLowerCase();
                if (arch.equals("x86_64")) arch = "amd64";
                if (arch.equals("aarch64")) arch = "arm64";
                builder.arch(new ArrayList<>(List.of(arch)));
            }
        }

        EnvironmentFingerprint fp = builder.build();
        if (fp.getOsType() == null) fp.setOsType("unknown");
        return fp;
    }
}
