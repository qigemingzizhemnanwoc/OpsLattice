package org.example.back.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.back.agent.trace.AgentToolTraceRecorder.RecordedCall;
import org.example.back.common.exception.BusinessException;
import org.example.back.entity.AiAgentConversation;
import org.example.back.entity.AiAgentMessage;
import org.example.back.entity.AiAgentToolCall;
import org.example.back.mapper.AiAgentConversationMapper;
import org.example.back.mapper.AiAgentMessageMapper;
import org.example.back.mapper.AiAgentToolCallMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
public class AgentConversationService {

    private final AiAgentConversationMapper conversationMapper;
    private final AiAgentMessageMapper messageMapper;
    private final AiAgentToolCallMapper toolCallMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AgentConversationService(AiAgentConversationMapper conversationMapper,
                                    AiAgentMessageMapper messageMapper,
                                    AiAgentToolCallMapper toolCallMapper) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.toolCallMapper = toolCallMapper;
    }

    public AiAgentConversation createConversation(Long userId, String title) {
        AiAgentConversation conversation = new AiAgentConversation();
        conversation.setUserId(userId);
        conversation.setTitle(truncate(title == null || title.isBlank() ? "新对话" : title.trim(), 100));
        conversation.setCreatedAt(LocalDateTime.now());
        conversation.setUpdatedAt(conversation.getCreatedAt());
        conversationMapper.insert(conversation);
        return conversation;
    }

    public Page<AiAgentConversation> listConversations(Long userId, int pageNum, int pageSize) {
        Page<AiAgentConversation> page = new Page<>(Math.max(1, pageNum), Math.max(1, Math.min(pageSize, 100)));
        LambdaQueryWrapper<AiAgentConversation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AiAgentConversation::getUserId, userId)
                .ge(AiAgentConversation::getCreatedAt, LocalDateTime.now().minusDays(30))
                .orderByDesc(AiAgentConversation::getUpdatedAt)
                .orderByDesc(AiAgentConversation::getId);
        return conversationMapper.selectPage(page, wrapper);
    }

    public AiAgentConversation requireConversation(Long conversationId, Long userId) {
        AiAgentConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || !userId.equals(conversation.getUserId())) {
            throw BusinessException.forbidden("会话不存在或无权操作");
        }
        return conversation;
    }

    public List<AiAgentMessage> getMessages(Long conversationId, Long userId) {
        requireConversation(conversationId, userId);
        LambdaQueryWrapper<AiAgentMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AiAgentMessage::getConversationId, conversationId)
                .orderByAsc(AiAgentMessage::getCreatedAt)
                .orderByAsc(AiAgentMessage::getId);
        return messageMapper.selectList(wrapper);
    }

    @Transactional
    public boolean deleteConversation(Long conversationId, Long userId) {
        AiAgentConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || !userId.equals(conversation.getUserId())) {
            return false;
        }
        toolCallMapper.delete(new LambdaQueryWrapper<AiAgentToolCall>()
                .eq(AiAgentToolCall::getConversationId, conversationId));
        messageMapper.delete(new LambdaQueryWrapper<AiAgentMessage>()
                .eq(AiAgentMessage::getConversationId, conversationId));
        conversationMapper.deleteById(conversationId);
        return true;
    }

    public List<AiAgentMessage> loadRecentHistory(Long conversationId, Long userId, int limit) {
        requireConversation(conversationId, userId);
        LambdaQueryWrapper<AiAgentMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AiAgentMessage::getConversationId, conversationId)
                .orderByDesc(AiAgentMessage::getId)
                .last("LIMIT " + Math.max(1, limit));
        List<AiAgentMessage> rows = new ArrayList<>(messageMapper.selectList(wrapper));
        Collections.reverse(rows);
        return rows;
    }

    public AiAgentMessage saveUserMessage(Long conversationId, String content) {
        AiAgentMessage message = new AiAgentMessage();
        message.setConversationId(conversationId);
        message.setRole("user");
        message.setContent(content);
        message.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(message);
        touch(conversationId);
        return message;
    }

    public AiAgentMessage saveAssistantMessage(Long conversationId, String content) {
        AiAgentMessage message = new AiAgentMessage();
        message.setConversationId(conversationId);
        message.setRole("assistant");
        message.setContent(content);
        message.setCreatedAt(LocalDateTime.now().plusNanos(1000));
        messageMapper.insert(message);
        touch(conversationId);
        return message;
    }

    @Transactional
    public void saveToolCalls(Long conversationId, Long messageId, List<RecordedCall> calls) {
        if (calls == null || calls.isEmpty()) {
            return;
        }
        int seq = 1;
        for (RecordedCall call : calls) {
            AiAgentToolCall entity = new AiAgentToolCall();
            entity.setConversationId(conversationId);
            entity.setMessageId(messageId);
            entity.setSeq(seq++);
            entity.setToolName(call.getToolName());
            entity.setArgumentsJson(call.getArguments());
            entity.setResultSummaryJson(call.getResultSummary());
            entity.setRowCount(call.getRowCount());
            entity.setSuccess(Boolean.TRUE.equals(call.getSuccess()));
            entity.setErrorMessage(call.getErrorMessage());
            entity.setLatencyMs(call.getLatencyMs());
            entity.setCreatedAt(LocalDateTime.now());
            toolCallMapper.insert(entity);
        }
    }

    /**
     * 读取本会话最近一次成功的库存查询结果，供下一轮“这些商品”指代使用。
     */
    public String findLastLowStockContext(Long conversationId, Long userId) {
        requireConversation(conversationId, userId);
        LambdaQueryWrapper<AiAgentToolCall> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AiAgentToolCall::getConversationId, conversationId)
                .eq(AiAgentToolCall::getToolName, AgentAccessPolicy.TOOL_QUERY_LOW_STOCK)
                .eq(AiAgentToolCall::getSuccess, true)
                .orderByDesc(AiAgentToolCall::getId)
                .last("LIMIT 1");
        AiAgentToolCall call = toolCallMapper.selectOne(wrapper);
        if (call == null || call.getResultSummaryJson() == null || call.getResultSummaryJson().isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(call.getResultSummaryJson());
            JsonNode rows = root.path("rows");
            if (!rows.isArray() || rows.isEmpty()) {
                return null;
            }
            List<String> items = new ArrayList<>();
            for (JsonNode row : rows) {
                String id = row.path("goodsId").asText("");
                String name = row.path("goodsName").asText("");
                String stock = row.path("stock").asText("");
                String warning = row.path("warningStock").asText("");
                if (!id.isBlank()) {
                    items.add("ID=" + id + " " + name + "（库存" + stock + "/阈值" + warning + "）");
                }
            }
            return items.isEmpty() ? null : "上一轮库存不足商品：" + String.join("；", items);
        } catch (Exception e) {
            log.warn("解析上一轮库存结果失败, conversationId={}", conversationId, e);
            return null;
        }
    }

    @Transactional
    public int cleanupExpired() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(30);
        LambdaQueryWrapper<AiAgentConversation> wrapper = new LambdaQueryWrapper<>();
        wrapper.lt(AiAgentConversation::getCreatedAt, cutoff);
        List<AiAgentConversation> expired = conversationMapper.selectList(wrapper);
        if (expired.isEmpty()) {
            return 0;
        }
        List<Long> conversationIds = expired.stream().map(AiAgentConversation::getId).toList();
        toolCallMapper.delete(new LambdaQueryWrapper<AiAgentToolCall>()
                .in(AiAgentToolCall::getConversationId, conversationIds));
        messageMapper.delete(new LambdaQueryWrapper<AiAgentMessage>()
                .in(AiAgentMessage::getConversationId, conversationIds));
        conversationMapper.delete(wrapper);
        return conversationIds.size();
    }

    private void touch(Long conversationId) {
        AiAgentConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation != null) {
            conversation.setUpdatedAt(LocalDateTime.now());
            conversationMapper.updateById(conversation);
        }
    }

    private String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
