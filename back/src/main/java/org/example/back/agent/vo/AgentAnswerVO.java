package org.example.back.agent.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class AgentAnswerVO {

    private String question;

    private String answer;

    private Long conversationId;

    private String hitType;

    private String providerCode = "deepseek";

    private String modelCode;

    private Long latencyMs;

    private LocalDateTime createdAt;

    private List<ToolCallVO> toolCalls = new ArrayList<>();

    @Data
    public static class ToolCallVO {
        private Long id;
        private Integer seq;
        private String toolName;
        private String arguments;
        private String resultSummary;
        private Integer rowCount;
        private Boolean success;
        private Long latencyMs;
        private String errorMessage;
    }
}
