package com.security.agent.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
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

    @Value("${spring.ai.openai.chat.options.model:deepseek-v4-pro}")
    private String model;

    @Value("${spring.ai.openai.chat.options.temperature:0.1}")
    private Double temperature;

    @Bean
    @Primary
    public OpenAiApi openAiApi() {
        // DeepSeek 端点: https://api.deepseek.com/v1/chat/completions
        String url = baseUrl;
        if (!url.endsWith("/v1")) {
            url = url + "/v1";
        }
        return new OpenAiApi(url, apiKey);
    }

    @Bean
    @Primary
    public OpenAiChatOptions openAiChatOptions() {
        OpenAiChatOptions options = new OpenAiChatOptions();
        options.setModel(model);
        options.setTemperature(temperature);
        return options;
    }

    @Bean
    @Primary
    public OpenAiChatModel openAiChatModel(OpenAiApi api, OpenAiChatOptions options) {
        return new OpenAiChatModel(api, options);
    }

    @Bean
    @Primary
    public ChatClient chatClient(OpenAiChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }
}
