package com.security.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.security.agent.config.JacksonConfig;
import com.security.agent.model.*;
import com.security.agent.util.JsonCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AiClientServiceTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new JacksonConfig().objectMapper();
    }

    @Test
    @DisplayName("正常 JSON 解析 AiVerdict")
    void shouldParseValidAiVerdictJson() throws Exception {
        String jsonResponse = "{\"status\":\"PASS\",\"reasoning\":\"防御生效\",\"nextCommand\":\"\",\"evidence\":\"capsh: Operation not permitted\",\"riskJustification\":\"\"}";

        String cleaned = JsonCleaner.clean(jsonResponse);
        AiVerdict verdict = mapper.readValue(cleaned, AiVerdict.class);
        assertEquals("PASS", verdict.getStatus());
        assertEquals("capsh: Operation not permitted", verdict.getEvidence());
    }

    @Test
    @DisplayName("清洗后解析：JSON 被 markdown 包裹")
    void shouldParseAfterCleaningMarkdown() {
        String response = "```json\n{\"status\":\"FAIL\",\"reasoning\":\"特权未限制\",\"nextCommand\":\"\",\"evidence\":\"CapEff: 0000003fffffffff\",\"riskJustification\":\"HIGH\"}\n```";

        String cleaned = JsonCleaner.clean(response);
        try {
            AiVerdict verdict = mapper.readValue(cleaned, AiVerdict.class);
            assertEquals("FAIL", verdict.getStatus());
            assertTrue(verdict.getEvidence().contains("CapEff"));
        } catch (Exception e) {
            fail("Should parse after cleaning", e);
        }
    }

    @Test
    @DisplayName("解析 EVOLVE 状态的 AiVerdict")
    void shouldParseEvolveVerdict() {
        String json = "{\"status\":\"EVOLVE\",\"reasoning\":\"capsh not found\",\"nextCommand\":\"cat /proc/1/status | grep CapEff\",\"evidence\":\"\",\"riskJustification\":\"\"}";

        try {
            AiVerdict verdict = mapper.readValue(json, AiVerdict.class);
            assertEquals("EVOLVE", verdict.getStatus());
            assertEquals("cat /proc/1/status | grep CapEff", verdict.getNextCommand());
        } catch (Exception e) {
            fail("Should parse EVOLVE", e);
        }
    }

    @Test
    @DisplayName("解析 ENV_MISMATCH 状态的 AiVerdict")
    void shouldParseEnvMismatchVerdict() {
        String json = "{\"status\":\"ENV_MISMATCH\",\"reasoning\":\"/proc filesystem not found\",\"nextCommand\":\"\",\"evidence\":\"ls: /proc: No such file or directory\",\"riskJustification\":\"Windows Container 不支持 Linux Context\"}";

        try {
            AiVerdict verdict = mapper.readValue(json, AiVerdict.class);
            assertEquals("ENV_MISMATCH", verdict.getStatus());
            assertTrue(verdict.getEvidence().contains("/proc"));
        } catch (Exception e) {
            fail("Should parse ENV_MISMATCH", e);
        }
    }

    @Test
    @DisplayName("解析 ContextMatchResult")
    void shouldParseContextMatchResult() {
        String json = "{\"matchResult\":\"MATCHED\",\"selectedContextId\":\"linux-debian\",\"matchReasoning\":\"Exact match on osType and flavor\",\"environmentSummary\":\"Debian 12, bash, tools available\"}";

        try {
            ContextMatchResult result = mapper.readValue(json, ContextMatchResult.class);
            assertEquals("MATCHED", result.getMatchResult());
            assertEquals("linux-debian", result.getSelectedContextId());
        } catch (Exception e) {
            fail("Should parse ContextMatchResult", e);
        }
    }

    @Test
    @DisplayName("解析 AiReportResponse")
    void shouldParseReportResponse() {
        String json = """
            {
              "testReport": {
                "summary": "容器存在特权模式风险",
                "riskLevel": "CRITICAL",
                "evidence": "CapEff: 0000003fffffffff",
                "affectedEnvironment": "Debian 12 Container"
              },
              "securityRemediation": {
                "strategy": "移除所有 capabilities 并按需添加",
                "k8sYamlPatch": "securityContext:\\\\n  capabilities:\\\\n    drop: [ALL]",
                "alternativeAdvice": "使用 seccomp profile 限制",
                "environmentSpecificNotes": "非 root 用户运行"
              }
            }
            """;

        try {
            AiReportResponse report = mapper.readValue(json, AiReportResponse.class);
            assertNotNull(report.getTestReport());
            assertEquals("CRITICAL", report.getTestReport().getRiskLevel());
            assertNotNull(report.getSecurityRemediation());
            assertTrue(report.getSecurityRemediation().getStrategy().contains("capabilities"));
        } catch (Exception e) {
            fail("Should parse AiReportResponse", e);
        }
    }

    @Test
    @DisplayName("损坏 JSON 字符串仍无法解析（无 AI 时）应抛出异常")
    void shouldThrowOnUnrecoverableJson() {
        String broken = "{status: PASS, not valid json at all!!!";

        String cleaned = JsonCleaner.clean(broken);
        assertThrows(Exception.class, () -> mapper.readValue(cleaned, AiVerdict.class));
    }
}
