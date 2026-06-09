package com.security.agent.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 大模型 JSON 返回值清洗工具。
 * 处理 LLM 输出中的 Markdown 代码块包裹和其他非 JSON 前缀/后缀。
 */
public class JsonCleaner {

    private static final Logger log = LoggerFactory.getLogger(JsonCleaner.class);

    private static final Pattern MARKDOWN_JSON_PATTERN =
            Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)\\s*```", Pattern.CASE_INSENSITIVE);

    private JsonCleaner() {}

    public static String clean(String raw) {
        if (raw == null || raw.isBlank()) {
            log.debug("JsonCleaner: 输入为空");
            return raw;
        }

        String trimmed = raw.trim();

        // 1. 去掉 Markdown ```json ... ``` 包裹
        Matcher m = MARKDOWN_JSON_PATTERN.matcher(trimmed);
        if (m.find()) {
            String inner = m.group(1).trim();
            log.debug("JsonCleaner: 去除 Markdown 包裹成功");
            return inner;
        }

        // 2. 尝试找到第一个 { 和最后一个 } 作为 JSON 边界
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            log.debug("JsonCleaner: 通过 JSON 边界截取 (start={}, end={})", start, end);
            return trimmed.substring(start, end + 1);
        }

        // 3. 尝试找到第一个 [ 和最后一个 ] 作为 JSON 数组边界
        start = trimmed.indexOf('[');
        end = trimmed.lastIndexOf(']');
        if (start >= 0 && end > start) {
            log.debug("JsonCleaner: 通过 JSON 数组边界截取 (start={}, end={})", start, end);
            return trimmed.substring(start, end + 1);
        }

        log.debug("JsonCleaner: 无需清洗，直接返回");
        return trimmed;
    }
}
