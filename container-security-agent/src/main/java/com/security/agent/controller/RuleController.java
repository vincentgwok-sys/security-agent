package com.security.agent.controller;

import com.security.agent.model.RuleVerdict;
import com.security.agent.service.CommandRuleService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/rules")
public class RuleController {

    private final CommandRuleService ruleService;

    public RuleController(CommandRuleService ruleService) {
        this.ruleService = ruleService;
    }

    @GetMapping
    public ResponseEntity<?> getActiveRules() {
        CommandRuleService.RuleSet ruleSet = ruleService.getActiveRules();
        if (ruleSet == null) {
            return ResponseEntity.ok(Map.of("rules", List.of(), "defaultAction", "BLOCK"));
        }
        return ResponseEntity.ok(ruleSet);
    }

    @GetMapping("/history")
    public ResponseEntity<?> getHitHistory() {
        List<RuleVerdict> history = ruleService.getHitHistory();
        return ResponseEntity.ok(history);
    }

    @PostMapping("/reload")
    public ResponseEntity<?> reloadRules() {
        CommandRuleService.RuleSet ruleSet = ruleService.reloadRules();
        long blocked = ruleSet.getRules().stream().filter(r -> "BLOCK".equals(r.getAction())).count();
        long warned = ruleSet.getRules().stream().filter(r -> "WARN".equals(r.getAction())).count();
        long allowed = ruleSet.getRules().stream().filter(r -> "ALLOW".equals(r.getAction())).count();

        return ResponseEntity.ok(Map.of(
                "message", "规则已重新加载",
                "total", ruleSet.getRules().size(),
                "defaultAction", ruleSet.getDefaultAction(),
                "blocked", blocked,
                "warned", warned,
                "allowed", allowed));
    }
}
