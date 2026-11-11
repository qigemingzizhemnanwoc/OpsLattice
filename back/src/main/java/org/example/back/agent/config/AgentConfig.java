package org.example.back.agent.config;

import io.micrometer.observation.ObservationRegistry;
import org.example.back.agent.config.AgentProperties;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;

/**
 * 手工创建 DeepSeek ChatModel。
 *
 * <p>刻意不使用 Spring AI starter 的自动配置：没有 Key 时不创建 Bean，
 * 避免影响应用启动、现有测试和其他 AI 助手链路。</p>
 */
@Configuration
public class AgentConfig {

    @Bean
    @ConditionalOnProperty(prefix = "assistant.agent", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnExpression("'${assistant.agent.deepseek.api-key:}' != ''")
    @ConditionalOnMissingBean(ChatModel.class)
    public ChatModel agentChatModel(AgentProperties properties) {
        AgentProperties.DeepSeek deepSeek = properties.getDeepseek();

        DeepSeekApi api = DeepSeekApi.builder()
                .baseUrl(deepSeek.getBaseUrl())
                .apiKey(deepSeek.getApiKey())
                .build();

        DeepSeekChatOptions options = DeepSeekChatOptions.builder()
                .model(deepSeek.getModel())
                .temperature(deepSeek.getTemperature())
                .maxTokens(deepSeek.getMaxTokens())
                .build();

        return DeepSeekChatModel.builder()
                .deepSeekApi(api)
                .defaultOptions(options)
                .toolCallingManager(ToolCallingManager.builder().build())
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
                .observationRegistry(ObservationRegistry.NOOP)
                .build();
    }
}
