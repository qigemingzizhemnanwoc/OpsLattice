package org.example.back.agent.task;

import lombok.extern.slf4j.Slf4j;
import org.example.back.agent.service.AgentConversationService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 数据 Agent 会话保留策略：每天 03:00 清理 30 天前的会话、消息和工具轨迹。
 */
@Slf4j
@Component
public class AgentConversationCleanupTask {

    private final AgentConversationService conversationService;

    public AgentConversationCleanupTask(AgentConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanup() {
        int removed = conversationService.cleanupExpired();
        if (removed > 0) {
            log.info("数据 Agent 会话清理完成，删除 {} 个 30 天前会话", removed);
        }
    }
}
