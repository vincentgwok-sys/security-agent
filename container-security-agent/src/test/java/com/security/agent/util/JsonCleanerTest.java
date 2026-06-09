package com.security.agent.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

class JsonCleanerTest {

    @Test
    @DisplayName("清洗 Markdown 包裹的 JSON")
    void shouldStripMarkdownCodeBlock() {
        String input = "```json\n{\"key\": \"value\"}\n```";
        String result = JsonCleaner.clean(input);
        assertTrue(result.contains("\"key\""));
        assertFalse(result.contains("```"));
    }

    @Test
    @DisplayName("清洗无语言标记的 markdown 代码块")
    void shouldStripPlainMarkdownBlock() {
        String input = "```\n{\"key\": \"value\"}\n```";
        String result = JsonCleaner.clean(input);
        assertTrue(result.contains("\"key\""));
        assertFalse(result.contains("```"));
    }

    @Test
    @DisplayName("截取 { 到 } 的 JSON 边界")
    void shouldExtractJsonBoundary() {
        String input = "Some text before {\"name\": \"test\", \"value\": 42} and after";
        String result = JsonCleaner.clean(input);
        assertEquals("{\"name\": \"test\", \"value\": 42}", result);
    }

    @Test
    @DisplayName("处理嵌套 JSON")
    void shouldHandleNestedJson() {
        String input = "```json\n{\"outer\": {\"inner\": [1, 2, 3]}}\n```";
        String result = JsonCleaner.clean(input);
        assertTrue(result.contains("\"outer\""));
        assertTrue(result.contains("\"inner\""));
        assertTrue(result.contains("[1, 2, 3]"));
        assertFalse(result.contains("```"));
    }

    @Test
    @DisplayName("空输入返回原始值")
    void shouldReturnOriginalForEmptyInput() {
        assertNull(JsonCleaner.clean(null));
        assertEquals("", JsonCleaner.clean(""));
        assertEquals("   ", JsonCleaner.clean("   "));
    }

    @Test
    @DisplayName("无花括号的文本返回原文本")
    void shouldReturnTrimmedForNonJson() {
        String input = "This is plain text without braces.";
        String result = JsonCleaner.clean(input);
        assertEquals(input, result);
    }

    @Test
    @DisplayName("AI 回复含有多余说明文本")
    void shouldStripLeadingAndTrailingText() {
        String input = "Here is the result:\n{\"status\": \"PASS\"}\nI hope this helps.";
        String result = JsonCleaner.clean(input);
        assertEquals("{\"status\": \"PASS\"}", result);
    }
}
