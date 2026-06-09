package com.security.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.security.agent.model.*;
import com.security.agent.util.PathResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class ReportGenerationService {

    private static final Logger log = LoggerFactory.getLogger(ReportGenerationService.class);

    @Value("${security-agent.report.output-directory:./reports}")
    private String reportsDir;

    private final ObjectMapper objectMapper;

    public ReportGenerationService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        this.reportsDir = PathResolver.resolve(reportsDir).toString();
    }

    /**
     * 收集所有 SkillReport → 计算 overallScore/passRate → 组装 ReportData
     * → Jackson 序列化 JSON → 持久化到 reports/ 目录
     */
    public ReportData generateAndPersist(DetectionTask task, List<SkillReport> skillReports,
                                         EnvironmentFingerprint targetEnv) {
        ReportData report = buildReportData(task, skillReports, targetEnv);

        // Persist JSON
        Path dir = Path.of(reportsDir);
        try {
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
            Path jsonPath = dir.resolve(task.getTaskId() + ".json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(jsonPath.toFile(), report);
            log.info("报告 JSON 已持久化: {}", jsonPath);
        } catch (IOException e) {
            log.error("报告持久化失败: {}", task.getTaskId(), e);
        }

        return report;
    }

    /**
     * 构建 ReportData，计算分数。
     */
    public ReportData buildReportData(DetectionTask task, List<SkillReport> skillReports,
                                      EnvironmentFingerprint targetEnv) {
        long passCount = skillReports.stream()
                .filter(r -> "PASS".equals(r.getFinalStatus()))
                .count();
        int total = skillReports.size();
        int overallScore = total > 0 ? (int) Math.round((double) passCount / total * 100) : 0;
        String passRate = passCount + "/" + total;

        return ReportData.builder()
                .taskId(task.getTaskId())
                .targetIp(task.getTargetIp())
                .auditTime(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .overallScore(overallScore)
                .passRate(passRate)
                .targetEnvironment(targetEnv)
                .skillReports(skillReports)
                .build();
    }

    /**
     * 根据 taskId 加载已持久化的报告 JSON。
     */
    public ReportData loadReport(String taskId) {
        Path jsonPath = Path.of(reportsDir, taskId + ".json");
        if (!Files.exists(jsonPath)) {
            log.warn("报告文件不存在: {}", jsonPath);
            return null;
        }
        try {
            return objectMapper.readValue(jsonPath.toFile(), ReportData.class);
        } catch (IOException e) {
            log.error("报告加载失败: {}", taskId, e);
            return null;
        }
    }

    /**
     * 生成内联 CSS 的 HTML 报告页面。
     */
    public String renderHtmlReport(ReportData report) {
        if (report == null) return "<html><body><h1>报告未找到</h1></body></html>";

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n");
        html.append("<meta charset=\"UTF-8\">\n");
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        html.append("<title>安全审计报告 - ").append(escapeHtml(report.getTaskId())).append("</title>\n");
        html.append("<style>\n");
        html.append(getEmbeddedCss());
        html.append("</style>\n</head>\n<body>\n");

        // Header
        html.append("<div class=\"report-container\">\n");
        html.append("<div class=\"header\">\n");
        html.append("<h1>🔒 容器安全审计报告</h1>\n");
        html.append("<div class=\"meta\">\n");
        html.append("<span><strong>任务 ID：</strong>").append(escapeHtml(report.getTaskId())).append("</span>\n");
        html.append("<span><strong>目标 IP：</strong>").append(escapeHtml(report.getTargetIp())).append("</span>\n");
        html.append("<span><strong>审计时间：</strong>").append(escapeHtml(report.getAuditTime())).append("</span>\n");
        html.append("</div>\n</div>\n");

        // Score ring
        html.append("<div class=\"score-section\">\n");
        html.append("<div class=\"score-ring-container\">\n");
        html.append("<svg class=\"score-ring\" viewBox=\"0 0 120 120\">\n");
        html.append("<circle cx=\"60\" cy=\"60\" r=\"52\" fill=\"none\" stroke=\"#e0e0e0\" stroke-width=\"12\" />\n");
        int score = report.getOverallScore();
        double circumference = 2 * Math.PI * 52;
        double dashOffset = circumference * (1 - score / 100.0);
        String color = score >= 80 ? "#22c55e" : score >= 50 ? "#f59e0b" : "#ef4444";
        html.append("<circle cx=\"60\" cy=\"60\" r=\"52\" fill=\"none\" stroke=\"").append(color)
                .append("\" stroke-width=\"12\" stroke-dasharray=\"").append(String.format("%.1f", circumference))
                .append("\" stroke-dashoffset=\"").append(String.format("%.1f", dashOffset))
                .append("\" stroke-linecap=\"round\" transform=\"rotate(-90 60 60)\" />\n");
        html.append("<text x=\"60\" y=\"55\" text-anchor=\"middle\" font-size=\"28\" font-weight=\"bold\" fill=\"#1f2937\">")
                .append(score).append("</text>\n");
        html.append("<text x=\"60\" y=\"72\" text-anchor=\"middle\" font-size=\"11\" fill=\"#6b7280\">分</text>\n");
        html.append("</svg>\n</div>\n");

        html.append("<div class=\"score-info\">\n");
        html.append("<div class=\"stat\"><span class=\"stat-label\">通过率</span><span class=\"stat-value\">")
                .append(escapeHtml(report.getPassRate())).append("</span></div>\n");

        if (report.getTargetEnvironment() != null) {
            html.append("<div class=\"stat\"><span class=\"stat-label\">目标环境</span><span class=\"stat-value\">")
                    .append(escapeHtml(report.getTargetEnvironment().getOsType() != null
                            ? report.getTargetEnvironment().getOsType() : "Unknown"))
                    .append("</span></div>\n");
        }
        html.append("</div>\n</div>\n");

        // Skill reports
        html.append("<h2>检测结果明细</h2>\n");
        html.append("<div class=\"skill-cards\">\n");

        if (report.getSkillReports() != null) {
            for (SkillReport sr : report.getSkillReports()) {
                renderSkillCard(html, sr);
            }
        }

        html.append("</div>\n");

        // Timeline
        html.append("<h2>执行时间线</h2>\n");
        html.append("<div class=\"timeline\">\n");
        if (report.getSkillReports() != null) {
            for (SkillReport sr : report.getSkillReports()) {
                if (sr.getExecutionRecords() != null) {
                    for (int i = 0; i < sr.getExecutionRecords().size(); i++) {
                        ExecutionRecord rec = sr.getExecutionRecords().get(i);
                        html.append("<div class=\"timeline-item\">\n");
                        html.append("<div class=\"timeline-dot ").append(getStatusClass(rec.getVerdict() != null
                                ? rec.getVerdict().getStatus() : "")).append("\"></div>\n");
                        html.append("<div class=\"timeline-content\">\n");
                        html.append("<div class=\"timeline-skill\">").append(escapeHtml(sr.getSkillName())).append("</div>\n");
                        html.append("<code class=\"timeline-cmd\">").append(escapeHtml(rec.getCommand() != null
                                ? rec.getCommand() : "")).append("</code>\n");
                        html.append("<span class=\"timeline-status\">→ ")
                                .append(escapeHtml(rec.getVerdict() != null ? rec.getVerdict().getStatus() : "?"))
                                .append("</span>\n");
                        html.append("</div>\n</div>\n");
                    }
                }
            }
        }
        html.append("</div>\n");

        html.append("</div>\n"); // .report-container
        html.append("<script>\n");
        html.append("window.onload=function(){window.print()};");
        html.append("</script>\n");
        html.append("</body>\n</html>");

        return html.toString();
    }

    private void renderSkillCard(StringBuilder html, SkillReport sr) {
        String statusColor = "PASS".equals(sr.getFinalStatus()) ? "#22c55e" : "#ef4444";
        html.append("<div class=\"skill-card\">\n");
        html.append("<div class=\"skill-card-header\" style=\"border-left: 4px solid ").append(statusColor).append(";\">\n");
        html.append("<div>\n");
        html.append("<span class=\"skill-badge ").append("PASS".equals(sr.getFinalStatus()) ? "pass" : "fail")
                .append("\">").append(sr.getFinalStatus()).append("</span>\n");
        html.append("<strong>").append(escapeHtml(sr.getSkillName())).append("</strong>\n");
        html.append("<span class=\"skill-id\">").append(escapeHtml(sr.getSkillId())).append("</span>\n");
        html.append("</div>\n");
        html.append("<span class=\"context-tag\">").append(escapeHtml(sr.getContextEnvironment())).append("</span>\n");
        html.append("</div>\n");

        html.append("<div class=\"skill-card-body\">\n");

        // Test Report
        if (sr.getTestReport() != null) {
            html.append("<div class=\"report-block\">\n");
            html.append("<h4>📋 检测分析</h4>\n");
            html.append("<p>").append(escapeHtml(sr.getTestReport().getSummary() != null
                    ? sr.getTestReport().getSummary() : "")).append("</p>\n");

            if (sr.getTestReport().getRiskLevel() != null) {
                html.append("<span class=\"risk-tag risk-").append(sr.getTestReport().getRiskLevel().toLowerCase())
                        .append("\">").append(sr.getTestReport().getRiskLevel()).append("</span>\n");
            }

            if (sr.getTestReport().getEvidence() != null && !sr.getTestReport().getEvidence().isEmpty()) {
                html.append("<div class=\"evidence\">\n");
                html.append("<h5>🔍 关键证据</h5>\n");
                html.append("<pre>").append(escapeHtml(sr.getTestReport().getEvidence())).append("</pre>\n");
                html.append("</div>\n");
            }

            if (sr.getTestReport().getAffectedEnvironment() != null) {
                html.append("<p class=\"env-note\">影响环境：")
                        .append(escapeHtml(sr.getTestReport().getAffectedEnvironment())).append("</p>\n");
            }
            html.append("</div>\n");
        }

        // Security Remediation
        if (sr.getSecurityRemediation() != null) {
            html.append("<div class=\"report-block remediation\">\n");
            html.append("<h4>🛡️ 修复建议</h4>\n");

            if (sr.getSecurityRemediation().getStrategy() != null
                    && !sr.getSecurityRemediation().getStrategy().isEmpty()) {
                html.append("<p><strong>策略：</strong>")
                        .append(escapeHtml(sr.getSecurityRemediation().getStrategy())).append("</p>\n");
            }

            if (sr.getSecurityRemediation().getK8sYamlPatch() != null
                    && !sr.getSecurityRemediation().getK8sYamlPatch().isEmpty()) {
                html.append("<h5>K8s YAML 补丁</h5>\n");
                html.append("<pre class=\"yaml-block\">")
                        .append(escapeHtml(sr.getSecurityRemediation().getK8sYamlPatch()))
                        .append("</pre>\n");
            }

            if (sr.getSecurityRemediation().getAlternativeAdvice() != null
                    && !sr.getSecurityRemediation().getAlternativeAdvice().isEmpty()) {
                html.append("<p><strong>替代方案：</strong>")
                        .append(escapeHtml(sr.getSecurityRemediation().getAlternativeAdvice())).append("</p>\n");
            }

            if (sr.getSecurityRemediation().getEnvironmentSpecificNotes() != null
                    && !sr.getSecurityRemediation().getEnvironmentSpecificNotes().isEmpty()) {
                html.append("<p class=\"env-note\"><strong>环境备注：</strong>")
                        .append(escapeHtml(sr.getSecurityRemediation().getEnvironmentSpecificNotes())).append("</p>\n");
            }
            html.append("</div>\n");
        }

        // Evolution info
        if (sr.isEvolved()) {
            html.append("<div class=\"evolution-note\">⚡ 此 Skill 在检测过程中发生了")
                    .append("context".equals(sr.getEvolutionType()) ? "上下文级" : "命令级")
                    .append("进化</div>\n");
        }

        // Execution Records (collapsible)
        if (sr.getExecutionRecords() != null && !sr.getExecutionRecords().isEmpty()) {
            html.append("<details class=\"exec-details\">\n");
            html.append("<summary>执行记录 (").append(sr.getExecutionRecords().size()).append(" 条命令)</summary>\n");
            for (ExecutionRecord rec : sr.getExecutionRecords()) {
                html.append("<div class=\"exec-record\">\n");
                html.append("<code class=\"exec-cmd\">$ ").append(escapeHtml(rec.getCommand())).append("</code>\n");
                if (rec.getResult() != null) {
                    html.append("<div class=\"exec-output\">\n");
                    if (rec.getResult().getExitCode() != 0) {
                        html.append("<span class=\"exit-code\">exit: ").append(rec.getResult().getExitCode()).append("</span>\n");
                    }
                    if (rec.getResult().getStdout() != null && !rec.getResult().getStdout().isEmpty()) {
                        html.append("<pre class=\"stdout\">").append(escapeHtml(rec.getResult().getStdout())).append("</pre>\n");
                    }
                    if (rec.getResult().getStderr() != null && !rec.getResult().getStderr().isEmpty()) {
                        html.append("<pre class=\"stderr\">").append(escapeHtml(rec.getResult().getStderr())).append("</pre>\n");
                    }
                    if (rec.getResult().isBlocked()) {
                        html.append("<span class=\"blocked-tag\">已拦截</span>\n");
                    }
                    html.append("</div>\n");
                }
                if (rec.getVerdict() != null) {
                    html.append("<span class=\"verdict-tag verdict-").append(rec.getVerdict().getStatus().toLowerCase())
                            .append("\">").append(rec.getVerdict().getStatus()).append("</span>\n");
                }
                html.append("</div>\n");
            }
            html.append("</details>\n");
        }

        html.append("</div>\n"); // .skill-card-body
        html.append("</div>\n"); // .skill-card
    }

    private String getStatusClass(String status) {
        return switch (status) {
            case "PASS" -> "tl-pass";
            case "FAIL" -> "tl-fail";
            case "EVOLVE" -> "tl-evolve";
            default -> "tl-unknown";
        };
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private String getEmbeddedCss() {
        return """
            * { margin: 0; padding: 0; box-sizing: border-box; }
            body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; background: #f5f5f5; color: #1f2937; line-height: 1.6; }
            .report-container { max-width: 960px; margin: 0 auto; padding: 32px 24px; }
            .header { background: #fff; border-radius: 12px; padding: 32px; margin-bottom: 24px; box-shadow: 0 1px 3px rgba(0,0,0,.1); }
            .header h1 { font-size: 24px; margin-bottom: 16px; }
            .meta { display: flex; flex-wrap: wrap; gap: 24px; font-size: 14px; color: #6b7280; }
            .score-section { display: flex; align-items: center; gap: 40px; background: #fff; border-radius: 12px; padding: 32px; margin-bottom: 24px; box-shadow: 0 1px 3px rgba(0,0,0,.1); }
            .score-ring-container { flex-shrink: 0; }
            .score-ring { width: 140px; height: 140px; }
            .score-info { display: flex; flex-direction: column; gap: 16px; }
            .stat { display: flex; flex-direction: column; }
            .stat-label { font-size: 12px; color: #6b7280; text-transform: uppercase; letter-spacing: .05em; }
            .stat-value { font-size: 20px; font-weight: 600; }
            h2 { font-size: 20px; margin: 32px 0 16px; }
            .skill-cards { display: flex; flex-direction: column; gap: 16px; }
            .skill-card { background: #fff; border-radius: 12px; overflow: hidden; box-shadow: 0 1px 3px rgba(0,0,0,.1); }
            .skill-card-header { display: flex; justify-content: space-between; align-items: center; padding: 16px 20px; background: #fafafa; }
            .skill-card-body { padding: 20px; }
            .skill-badge { display: inline-block; padding: 2px 10px; border-radius: 999px; font-size: 12px; font-weight: 600; margin-right: 8px; }
            .skill-badge.pass { background: #dcfce7; color: #166534; }
            .skill-badge.fail { background: #fef2f2; color: #991b1b; }
            .skill-id { font-size: 12px; color: #9ca3af; margin-left: 8px; }
            .context-tag { font-size: 12px; background: #eff6ff; color: #1e40af; padding: 2px 8px; border-radius: 4px; }
            .report-block { margin-bottom: 16px; }
            .report-block h4 { font-size: 15px; margin-bottom: 8px; }
            .report-block h5 { font-size: 13px; margin: 8px 0 4px; }
            .evidence pre, .yaml-block { background: #1e293b; color: #e2e8f0; padding: 12px; border-radius: 8px; overflow-x: auto; font-size: 13px; }
            .evidence pre { white-space: pre-wrap; word-break: break-all; }
            .remediation { background: #fffbeb; border: 1px solid #fde68a; border-radius: 8px; padding: 16px; }
            .risk-tag { display: inline-block; padding: 2px 8px; border-radius: 4px; font-size: 11px; font-weight: 600; margin-top: 4px; }
            .risk-critical { background: #fef2f2; color: #991b1b; }
            .risk-high { background: #fff7ed; color: #c2410c; }
            .risk-low { background: #fefce8; color: #a16207; }
            .risk-info { background: #eff6ff; color: #1e40af; }
            .env-note { font-size: 13px; color: #6b7280; margin-top: 8px; }
            .evolution-note { background: #f0fdf4; border: 1px solid #86efac; border-radius: 6px; padding: 8px 12px; font-size: 13px; margin-top: 12px; }
            .exec-details { margin-top: 12px; }
            .exec-details summary { cursor: pointer; font-size: 13px; color: #6b7280; padding: 4px 0; }
            .exec-record { padding: 8px 0; border-bottom: 1px solid #f3f4f6; }
            .exec-cmd { display: block; background: #f3f4f6; padding: 6px 10px; border-radius: 4px; font-size: 13px; word-break: break-all; }
            .exec-output { margin: 4px 0 4px 16px; }
            .exec-output pre { font-size: 12px; padding: 6px; margin: 4px 0; border-radius: 4px; }
            .stdout { background: #f0fdf4; color: #166534; max-height: 120px; overflow: auto; }
            .stderr { background: #fef2f2; color: #991b1b; max-height: 120px; overflow: auto; }
            .exit-code { font-size: 12px; color: #6b7280; }
            .blocked-tag { font-size: 11px; background: #fef2f2; color: #dc2626; padding: 1px 6px; border-radius: 3px; }
            .verdict-tag { display: inline-block; font-size: 11px; padding: 1px 6px; border-radius: 3px; margin-top: 4px; }
            .verdict-pass { background: #dcfce7; color: #166534; }
            .verdict-fail { background: #fef2f2; color: #991b1b; }
            .verdict-evolve { background: #fefce8; color: #a16207; }
            .verdict-env_mismatch { background: #ede9fe; color: #6b21a8; }
            .timeline { position: relative; padding-left: 24px; }
            .timeline::before { content: ""; position: absolute; left: 8px; top: 0; bottom: 0; width: 2px; background: #e5e7eb; }
            .timeline-item { position: relative; margin-bottom: 12px; padding-left: 20px; }
            .timeline-dot { position: absolute; left: -18px; top: 6px; width: 14px; height: 14px; border-radius: 50%; border: 2px solid #fff; }
            .tl-pass { background: #22c55e; }
            .tl-fail { background: #ef4444; }
            .tl-evolve { background: #f59e0b; }
            .tl-unknown { background: #9ca3af; }
            .timeline-content { background: #fff; padding: 10px 14px; border-radius: 8px; box-shadow: 0 1px 2px rgba(0,0,0,.05); }
            .timeline-skill { font-size: 12px; color: #6b7280; margin-bottom: 2px; }
            .timeline-cmd { font-size: 13px; word-break: break-all; }
            .timeline-status { font-size: 13px; color: #6b7280; margin-left: 4px; }
            @media print {
              body { background: #fff; }
              .report-container { max-width: 100%; }
              .skill-card, .header, .score-section { box-shadow: none; border: 1px solid #e5e7eb; }
            }
            """;
    }
}
