package com.security.agent.controller;

import com.security.agent.model.ReportData;
import com.security.agent.model.SkillDefinition;
import com.security.agent.service.ReportGenerationService;
import com.security.agent.service.SkillLoaderService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tasks/{taskId}/report")
public class ReportController {

    private final ReportGenerationService reportService;
    private final SkillLoaderService skillLoader;

    public ReportController(ReportGenerationService reportService,
                            SkillLoaderService skillLoader) {
        this.reportService = reportService;
        this.skillLoader = skillLoader;
    }

    @GetMapping(produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> getHtmlReport(@PathVariable String taskId) {
        ReportData report = reportService.loadReport(taskId);
        if (report == null) {
            return ResponseEntity.status(404)
                    .contentType(MediaType.TEXT_HTML)
                    .body("<html><body><h1>报告未找到</h1><p>任务 " + taskId + " 的报告尚不存在或检测尚未完成。</p></body></html>");
        }
        String html = reportService.renderHtmlReport(report);
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
    }

    @GetMapping("/json")
    public ResponseEntity<?> getJsonReport(@PathVariable String taskId) {
        ReportData report = reportService.loadReport(taskId);
        if (report == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(report);
    }

    @GetMapping("/skills")
    public ResponseEntity<?> getSkillReports(@PathVariable String taskId) {
        ReportData report = reportService.loadReport(taskId);
        if (report == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(report.getSkillReports() != null ? report.getSkillReports() : List.of());
    }
}
