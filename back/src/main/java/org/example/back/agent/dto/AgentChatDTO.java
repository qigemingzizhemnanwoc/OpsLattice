package org.example.back.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AgentChatDTO {

    @NotBlank(message = "问题不能为空")
    @Size(max = 1000, message = "问题不能超过1000个字符")
    private String question;

    private Long conversationId;
}
