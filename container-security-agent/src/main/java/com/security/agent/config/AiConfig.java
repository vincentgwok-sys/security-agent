package com.security.agent.config;

import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class AiConfig {

    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    @Value("${spring.ai.openai.base-url:https://api.openai.com}")
    private String baseUrl;

    /**
     * 显式创建 OpenAiApi Bean，确保 base-url 中包含 /v1 路径。
     * DeepSeek 的 OpenAI 兼容端点: https://api.deepseek.com/v1/chat/completions
     * <p>
     * 使用 @Primary 覆盖 Spring AI 自动配置中的默认 Bean，
     * 但保留 OpenAiChatModel 和 ChatClient 的自动配置（读取 yml 中的 model/temperature 等）。
     */
    @Bean
    @Primary
    public OpenAiApi openAiApi() {
        String url = baseUrl;
        if (!url.endsWith("/v1")) {
            url = url + "/v1";
        }
        return new OpenAiApi(url, apiKey);
    }
}
