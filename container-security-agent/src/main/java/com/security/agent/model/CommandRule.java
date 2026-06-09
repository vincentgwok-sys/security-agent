package com.security.agent.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.regex.Pattern;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CommandRule {

    private String ruleId;
    private String category;       // DESTRUCTIVE_DELETE | DESTRUCTIVE_FORMAT | etc.
    private String description;
    private String pattern;        // REGEX pattern string, or exact string, or prefix string
    private String matchType;      // REGEX | EXACT | PREFIX
    private String action;         // ALLOW | WARN | BLOCK
    private String message;        // 拦截/警告消息模板，支持 {{command}} 占位符

    @JsonIgnore
    private Pattern compiledPattern;

    /**
     * 获取预编译的 Pattern 对象（仅对 REGEX 类型）。
     * 加载规则后由 CommandRuleService 调用 compile() 填充。
     */
    @JsonIgnore
    public Pattern getCompiledPattern() {
        if (compiledPattern == null && "REGEX".equals(matchType) && pattern != null) {
            compiledPattern = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
        }
        return compiledPattern;
    }

    public void compile() {
        if ("REGEX".equals(matchType) && pattern != null) {
            this.compiledPattern = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
        }
    }
}
