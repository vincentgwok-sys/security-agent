package com.security.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.security.agent.model.CommandRule;
import com.security.agent.model.RuleVerdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CommandRuleServiceTest {

    private CommandRuleService ruleService;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        ruleService = new CommandRuleService(mapper);

        // Manually build a rule set for testing
        CommandRuleService.RuleSet ruleSet = new CommandRuleService.RuleSet();
        ruleSet.setRuleSetId("test-rule-set");
        ruleSet.setDefaultAction("BLOCK");

        CommandRule r1 = CommandRule.builder()
                .ruleId("R-001")
                .category("DESTRUCTIVE_DELETE")
                .description("拦截 rm -rf 等危险删除命令")
                .pattern("rm\\s+-[a-zA-Z]*[rf]")
                .matchType("REGEX")
                .action("BLOCK")
                .message("危险命令 {{command}} 被拦截")
                .build();
        r1.compile();

        CommandRule r2 = CommandRule.builder()
                .ruleId("R-002")
                .category("SERVICE_CONTROL")
                .description("服务控制操作需警告")
                .pattern("systemctl")
                .matchType("PREFIX")
                .action("WARN")
                .message("服务控制命令 {{command}}")
                .build();

        CommandRule r3 = CommandRule.builder()
                .ruleId("R-003")
                .category("ALLOWLIST_SAFE_READ")
                .description("安全读取命令")
                .pattern("cat /proc/1/status")
                .matchType("PREFIX")
                .action("ALLOW")
                .message("")
                .build();

        CommandRule r4 = CommandRule.builder()
                .ruleId("R-004")
                .category("EXACT_MATCH")
                .description("精确匹配 whoami")
                .pattern("whoami")
                .matchType("EXACT")
                .action("ALLOW")
                .message("")
                .build();

        ruleSet.setRules(List.of(r1, r2, r3, r4));

        // Inject active rules via reflection
        try {
            java.lang.reflect.Field f = CommandRuleService.class.getDeclaredField("activeRuleSet");
            f.setAccessible(true);
            f.set(ruleService, ruleSet);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("REGEX 匹配：rm -rf /etc 被 BLOCK")
    void shouldBlockDangerousRmCommand() {
        RuleVerdict v = ruleService.filter("rm -rf /etc/config", "TASK-001", "SKILL-001");
        assertEquals("BLOCK", v.getVerdict());
        assertEquals("R-001", v.getMatchedRuleId());
    }

    @Test
    @DisplayName("PREFIX 匹配：systemctl stop docker 被 WARN")
    void shouldWarnServiceControlCommand() {
        RuleVerdict v = ruleService.filter("systemctl stop docker", "TASK-001", "SKILL-001");
        assertEquals("WARN", v.getVerdict());
        assertEquals("R-002", v.getMatchedRuleId());
    }

    @Test
    @DisplayName("PREFIX 匹配：cat /proc/1/status 被 ALLOW")
    void shouldAllowSafeReadCommand() {
        RuleVerdict v = ruleService.filter("cat /proc/1/status", "TASK-001", "SKILL-001");
        assertEquals("ALLOW", v.getVerdict());
    }

    @Test
    @DisplayName("EXACT 匹配：whoami 被 ALLOW")
    void shouldMatchExactCommand() {
        RuleVerdict v = ruleService.filter("whoami", "TASK-001", "SKILL-001");
        assertEquals("ALLOW", v.getVerdict());
        assertEquals("R-004", v.getMatchedRuleId());
    }

    @Test
    @DisplayName("未匹配命令 + defaultAction=BLOCK → 被拦截")
    void shouldBlockUnmatchedCommandWithDefaultBlock() {
        RuleVerdict v = ruleService.filter("some_unknown_command --flag", "TASK-001", "SKILL-001");
        assertEquals("BLOCK", v.getVerdict());
        assertNull(v.getMatchedRuleId());
    }

    @Test
    @DisplayName("多规则命中取最严格判定：同时命中 WARN 和 ALLOW → WARN")
    void shouldPickStrictestWhenMultipleMatches() {
        // "systemctl cat /proc/1/status" hits R-002 (PREFIX) and R-003 (PREFIX)
        // R-002 WARN > R-003 ALLOW → WARN
        RuleVerdict v = ruleService.filter("systemctl cat /proc/1/status", "TASK-001", "SKILL-001");
        assertEquals("WARN", v.getVerdict());
    }

    @Test
    @DisplayName("记录命中日志：BLOCK 规则检查 log 格式")
    void shouldRecordHitHistory() {
        ruleService.filter("rm -rf /tmp", "TASK-001", "SKILL-001");
        List<RuleVerdict> history = ruleService.getHitHistory();
        assertFalse(history.isEmpty());
        RuleVerdict last = history.get(history.size() - 1);
        assertEquals("BLOCK", last.getVerdict());
        assertEquals("rm -rf /tmp", last.getOriginalCommand());
    }
}
