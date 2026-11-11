package org.example.back.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "assistant.agent")
public class AgentProperties {

    private boolean enabled = true;

    private int maxHistoryMessages = 8;

    private int maxToolCalls = 6;

    private int maxRows = 20;

    private int maxQuestionLength = 500;

    private DeepSeek deepseek = new DeepSeek();

    @Data
    public static class DeepSeek {
        private String baseUrl = "https://api.deepseek.com";
        private String apiKey = "";
        private String model = "deepseek-chat";
        private double temperature = 0.2;
        private int maxTokens = 1200;
    }
}
