package org.example.back.agent.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.Valid;
import org.example.back.agent.config.AgentProperties;
import org.example.back.agent.dto.AgentChatDTO;
import org.example.back.agent.service.AgentAccessPolicy;
import org.example.back.agent.service.AgentChatService;
import org.example.back.agent.service.AgentConversationService;
import org.example.back.agent.vo.AgentAnswerVO;
import org.example.back.common.result.Result;
import org.example.back.entity.AiAgentConversation;
import org.example.back.entity.AiAgentMessage;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/assistant/agent")
public class AgentController {

    private static final Map<String, String> TOOL_LABELS = Map.of(
            AgentAccessPolicy.TOOL_QUERY_LOW_STOCK, "查询库存不足商品",
            AgentAccessPolicy.TOOL_QUERY_GOODS_STOCK, "查询商品库存明细",
            AgentAccessPolicy.TOOL_QUERY_GOODS_SALES, "查询商品销售情况",
            AgentAccessPolicy.TOOL_QUERY_SALES_OVERVIEW, "查询销售统计概览"
    );

    private final AgentChatService chatService;
    private final AgentConversationService conversationService;
    private final AgentAccessPolicy accessPolicy;
    private final AgentProperties properties;

    public AgentController(AgentChatService chatService,
                           AgentConversationService conversationService,
                           AgentAccessPolicy accessPolicy,
                           AgentProperties properties) {
        this.chatService = chatService;
        this.conversationService = conversationService;
        this.accessPolicy = accessPolicy;
        this.properties = properties;
    }

    @PostMapping("/chat")
    public Result<AgentAnswerVO> chat(@Valid @RequestBody AgentChatDTO dto) {
        Long userId = getCurrentUserId();
        AgentAnswerVO answer = chatService.chat(
                userId,
                currentRole(),
                currentDeptCode(),
                dto.getQuestion(),
                dto.getConversationId()
        );
        return Result.success(answer);
    }

    @GetMapping("/conversations")
    public Result<Page<AiAgentConversation>> conversations(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(conversationService.listConversations(getCurrentUserId(), pageNum, pageSize));
    }

    @GetMapping("/conversations/{id}/messages")
    public Result<List<AiAgentMessage>> messages(@PathVariable Long id) {
        return Result.success(conversationService.getMessages(id, getCurrentUserId()));
    }

    @DeleteMapping("/conversations/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        boolean deleted = conversationService.deleteConversation(id, getCurrentUserId());
        return deleted ? Result.success() : Result.forbidden("会话不存在或无权删除");
    }

    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", properties.isEnabled());
        status.put("configured", chatService.isConfigured());
        status.put("model", properties.getDeepseek().getModel());
        status.put("allowedTools", accessPolicy.allowedTools(currentRole(), currentDeptCode()));
        return Result.success(status);
    }

    @GetMapping("/tools")
    public Result<List<Map<String, Object>>> tools() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String tool : accessPolicy.allowedTools(currentRole(), currentDeptCode())) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", tool);
            item.put("label", TOOL_LABELS.getOrDefault(tool, tool));
            result.add(item);
        }
        return Result.success(result);
    }

    private Long getCurrentUserId() {
        return StpUtil.getLoginIdAsLong();
    }

    private String currentRole() {
        Object role = StpUtil.getSession().get("role");
        return role == null ? "employee" : role.toString().trim().toLowerCase();
    }

    private String currentDeptCode() {
        Object deptCode = StpUtil.getSession().get("deptCode");
        return deptCode == null ? null : deptCode.toString().trim().toLowerCase();
    }
}
