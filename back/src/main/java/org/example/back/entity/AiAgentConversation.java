package org.example.back.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_agent_conversation")
public class AiAgentConversation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String title;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
