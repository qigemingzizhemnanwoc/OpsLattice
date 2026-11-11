package org.example.back.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_agent_tool_call")
public class AiAgentToolCall {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long conversationId;

    private Long messageId;

    private Integer seq;

    private String toolName;

    private String argumentsJson;

    private String resultSummaryJson;

    private Integer rowCount;

    private Boolean success;

    private String errorMessage;

    private Long latencyMs;

    private LocalDateTime createdAt;
}
