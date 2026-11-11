package org.example.back.agent;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.example.back.agent.config.AgentProperties;
import org.example.back.agent.controller.AgentController;
import org.example.back.agent.service.AgentAccessPolicy;
import org.example.back.agent.service.AgentChatService;
import org.example.back.agent.service.AgentConversationService;
import org.example.back.agent.service.AgentQueryService;
import org.example.back.common.exception.GlobalExceptionHandler;
import org.example.back.common.result.PageResult;
import org.example.back.config.SaTokenConfig;
import org.example.back.entity.AiAgentConversation;
import org.example.back.entity.AiAgentMessage;
import org.example.back.entity.AiAgentToolCall;
import org.example.back.mapper.AgentSalesQueryMapper;
import org.example.back.mapper.AiAgentConversationMapper;
import org.example.back.mapper.AiAgentMessageMapper;
import org.example.back.mapper.AiAgentToolCallMapper;
import org.example.back.mapper.SysErrorLogMapper;
import org.example.back.service.AuthzService;
import org.example.back.service.GoodsService;
import org.example.back.service.SalesChartService;
import org.example.back.vo.GoodsVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = AgentChatFlowIntegrationTest.TestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:agent_flow;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password="
        }
)
@AutoConfigureMockMvc
class AgentChatFlowIntegrationTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
            RedisAutoConfiguration.class,
            RedisRepositoriesAutoConfiguration.class
    })
    @EnableConfigurationProperties(AgentProperties.class)
    @org.mybatis.spring.annotation.MapperScan(basePackageClasses = {
            AiAgentConversationMapper.class,
            AiAgentMessageMapper.class,
            AiAgentToolCallMapper.class
    })
    @Import({
            AgentController.class,
            AgentChatService.class,
            AgentConversationService.class,
            AgentQueryService.class,
            AgentAccessPolicy.class,
            SaTokenConfig.class,
            GlobalExceptionHandler.class
    })
    static class TestApp {

        @Bean
        MybatisPlusInterceptor mybatisPlusInterceptor() {
            MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
            interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.H2));
            return interceptor;
        }

        @Bean
        ScriptedChatModel scriptedChatModel() {
            return new ScriptedChatModel();
        }
    }

    static class ScriptedChatModel implements ChatModel {

        private final AtomicInteger calls = new AtomicInteger();
        private final List<Prompt> prompts = new ArrayList<>();
        private volatile boolean directAnswer;
        private volatile boolean alwaysToolCall;

        void reset() {
            calls.set(0);
            prompts.clear();
            directAnswer = false;
            alwaysToolCall = false;
        }

        void setDirectAnswer(boolean directAnswer) {
            this.directAnswer = directAnswer;
        }

        void setAlwaysToolCall(boolean alwaysToolCall) {
            this.alwaysToolCall = alwaysToolCall;
        }

        List<Prompt> prompts() {
            return List.copyOf(prompts);
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            int n = calls.incrementAndGet();
            if (directAnswer) {
                return answer("当前没有上一轮库存上下文。");
            }
            if (alwaysToolCall) {
                return toolCall("queryLowStockGoods", "{\"limit\":1}");
            }
            return switch (n) {
                case 1 -> toolCall("queryLowStockGoods", "{\"limit\":20}");
                case 2 -> answer("库存不足商品：电阻10K（库存2，阈值10）。");
                case 3 -> salesToolCallFromContext(prompt);
                default -> answer("电阻10K 销量10，销售额100.00元。");
            };
        }

        private ChatResponse salesToolCallFromContext(Prompt prompt) {
            String system = prompt.getSystemMessage() == null ? "" : prompt.getSystemMessage().getText();
            Matcher matcher = Pattern.compile("ID=(\\d+)").matcher(system);
            if (!matcher.find()) {
                return answer("MISSING_STOCK_CONTEXT");
            }
            return toolCall("queryGoodsSales", "{\"goodsIds\":[" + matcher.group(1) + "]}");
        }

        private ChatResponse toolCall(String name, String arguments) {
            AssistantMessage message = new AssistantMessage(
                    "",
                    Map.of(),
                    List.of(new AssistantMessage.ToolCall("call-" + calls.get(), "function", name, arguments))
            );
            return new ChatResponse(List.of(new Generation(message)));
        }

        private ChatResponse answer(String text) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ScriptedChatModel chatModel;

    @Autowired
    private AgentConversationService conversationService;

    @MockBean
    private GoodsService goodsService;

    @MockBean
    private AuthzService authzService;

    @MockBean
    private SalesChartService salesChartService;

    @MockBean
    private AgentSalesQueryMapper salesQueryMapper;

    @MockBean
    private SysErrorLogMapper sysErrorLogMapper;

    @BeforeEach
    void setUp() {
        chatModel.reset();
        jdbcTemplate.execute("DROP TABLE IF EXISTS ai_agent_tool_call");
        jdbcTemplate.execute("DROP TABLE IF EXISTS ai_agent_message");
        jdbcTemplate.execute("DROP TABLE IF EXISTS ai_agent_conversation");
        jdbcTemplate.execute("""
                CREATE TABLE ai_agent_conversation (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    title VARCHAR(100),
                    created_at TIMESTAMP,
                    updated_at TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE ai_agent_message (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    conversation_id BIGINT NOT NULL,
                    role VARCHAR(20) NOT NULL,
                    content TEXT NOT NULL,
                    created_at TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE ai_agent_tool_call (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    conversation_id BIGINT NOT NULL,
                    message_id BIGINT,
                    seq INT NOT NULL,
                    tool_name VARCHAR(64) NOT NULL,
                    arguments_json TEXT,
                    result_summary_json TEXT,
                    row_count INT,
                    success BOOLEAN,
                    error_message VARCHAR(500),
                    latency_ms BIGINT,
                    created_at TIMESTAMP
                )
                """);

        GoodsVO goods = new GoodsVO();
        goods.setId(1L);
        goods.setGoodsCode("GD001");
        goods.setGoodsName("电阻10K");
        goods.setStock(2);
        goods.setWarningStock(10);
        goods.setUnit("个");
        goods.setStatus(1);
        when(goodsService.page(any())).thenReturn(
                new PageResult<>(List.of(goods), 1L, 1L, 20L, 1L));

        doNothing().when(authzService)
                .requireDeptAdminOrSuperAdmin(eq(AuthzService.DEPT_SALES), anyString());
        AgentSalesQueryMapper.GoodsSalesAgg sale = new AgentSalesQueryMapper.GoodsSalesAgg();
        sale.setGoodsId(1L);
        sale.setGoodsName("电阻10K");
        sale.setOrderCount(2L);
        sale.setQuantity(10L);
        sale.setAmount(new BigDecimal("100.00"));
        when(salesQueryMapper.summarizeSalesByGoods(anyList(), any(), any())).thenReturn(List.of(sale));
        when(salesQueryMapper.summarizeReturnsByGoods(anyList(), any(), any())).thenReturn(List.of());
    }

    @Test
    void twoTurnFlow_shouldCallLowStockThenSalesWithPreviousGoodsIds() throws Exception {
        LoginSession loginSession = loginWithRole("admin", "sales");

        String firstResponse = mockMvc.perform(post("/assistant/agent/chat")
                        .header("Authorization", "Bearer " + loginSession.token())
                        .contentType("application/json")
                        .content("{\"question\":\"哪些商品库存不足？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.toolCalls[0].toolName").value("queryLowStockGoods"))
                .andExpect(jsonPath("$.data.answer").value(containsString("电阻10K")))
                .andReturn().getResponse().getContentAsString();

        Long conversationId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(firstResponse).path("data").path("conversationId").asLong();

        mockMvc.perform(post("/assistant/agent/chat")
                        .header("Authorization", "Bearer " + loginSession.token())
                        .contentType("application/json")
                        .content("{\"question\":\"这些商品的销售情况怎么样？\",\"conversationId\":" + conversationId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.toolCalls[0].toolName").value("queryGoodsSales"))
                .andExpect(jsonPath("$.data.toolCalls[0].arguments").value(containsString("\"goodsIds\":[1]")))
                .andExpect(jsonPath("$.data.answer").value(containsString("销量10")));

        Integer messageCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_agent_message WHERE conversation_id=?", Integer.class, conversationId);
        Integer toolCallCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_agent_tool_call WHERE conversation_id=?", Integer.class, conversationId);
        assertEquals(4, messageCount);
        assertEquals(2, toolCallCount);
        List<AiAgentToolCall> toolCalls = jdbcTemplate.query(
                "SELECT tool_name FROM ai_agent_tool_call WHERE conversation_id=? ORDER BY id",
                (rs, rowNum) -> {
                    AiAgentToolCall call = new AiAgentToolCall();
                    call.setToolName(rs.getString(1));
                    return call;
                }, conversationId);
        assertEquals(List.of("queryLowStockGoods", "queryGoodsSales"),
                toolCalls.stream().map(AiAgentToolCall::getToolName).toList());

        assertTrue(chatModel.prompts().size() >= 3);
        String secondTurnSystem = chatModel.prompts().get(2).getSystemMessage().getText();
        assertTrue(secondTurnSystem.contains("上一轮库存不足商品："));
        assertTrue(secondTurnSystem.contains("ID=1"));
    }

    @Test
    void followUpWithoutPreviousStock_shouldNotInjectStockContext() throws Exception {
        chatModel.reset();
        chatModel.setDirectAnswer(true);
        LoginSession loginSession = loginWithRole("admin", "sales");

        mockMvc.perform(post("/assistant/agent/chat")
                        .header("Authorization", "Bearer " + loginSession.token())
                        .contentType("application/json")
                        .content("{\"question\":\"这些商品的销售情况怎么样？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toolCalls").isEmpty())
                .andExpect(jsonPath("$.data.answer").value(containsString("没有上一轮库存上下文")));

        String system = chatModel.prompts().get(0).getSystemMessage().getText();
        assertFalse(system.contains("上一轮库存不足商品："));
    }

    @Test
    void employeeChat_shouldReturnNoToolsWithoutCallingModel() throws Exception {
        chatModel.reset();
        LoginSession loginSession = loginWithRole("employee", "sales");

        mockMvc.perform(post("/assistant/agent/chat")
                        .header("Authorization", "Bearer " + loginSession.token())
                        .contentType("application/json")
                        .content("{\"question\":\"哪些商品库存不足？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hitType").value("no-tools"))
                .andExpect(jsonPath("$.data.toolCalls").isEmpty());

        assertTrue(chatModel.prompts().isEmpty());
    }

    @Test
    void toolRoundsExceedLimit_shouldReturnAgentErrorWithRecordedCalls() throws Exception {
        chatModel.reset();
        chatModel.setAlwaysToolCall(true);
        LoginSession loginSession = loginWithRole("admin", "sales");

        mockMvc.perform(post("/assistant/agent/chat")
                        .header("Authorization", "Bearer " + loginSession.token())
                        .contentType("application/json")
                        .content("{\"question\":\"哪些商品库存不足？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hitType").value("agent-error"))
                .andExpect(jsonPath("$.data.toolCalls.length()").value(6));
    }

    @Test
    void largeLowStockResult_shouldStillProvidePreviousGoodsContext() {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("INSERT INTO ai_agent_conversation (user_id,title,created_at,updated_at) VALUES (?,?,?,?)",
                70001L, "长结果上下文", now, now);
        Long conversationId = jdbcTemplate.queryForObject(
                "SELECT MAX(id) FROM ai_agent_conversation", Long.class);
        jdbcTemplate.update("INSERT INTO ai_agent_tool_call "
                        + "(conversation_id,message_id,seq,tool_name,arguments_json,result_summary_json,row_count,success,created_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?)",
                conversationId, null, 1, "queryLowStockGoods", "{}", largeResult(50), 50, true, now);

        String context = conversationService.findLastLowStockContext(conversationId, 70001L);

        assertTrue(context != null);
        assertTrue(context.contains("ID=1"));
        assertTrue(context.contains("ID=50"));
    }

    private String largeResult(int rows) {
        List<String> items = new ArrayList<>();
        for (int i = 1; i <= rows; i++) {
            items.add("{\"goodsId\":" + i + ",\"goodsCode\":\"AGT-" + i
                    + "\",\"goodsName\":\"Agent演示商品名称用于测试截断边界" + i
                    + "\",\"stock\":2,\"warningStock\":10,\"gap\":-8,\"unit\":\"个\",\"status\":1}");
        }
        return "{\"tool\":\"queryLowStockGoods\",\"total\":" + rows
                + ",\"rows\":[" + String.join(",", items) + "]}";
    }

    private LoginSession loginWithRole(String role, String deptCode) {
        long userId = 70001L;
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));
        try {
            StpUtil.login(userId, "PC");
            String token = StpUtil.getTokenValueByLoginId(userId, "PC");
            StpUtil.getSessionByLoginId(userId).set("role", role);
            StpUtil.getSessionByLoginId(userId).set("deptCode", deptCode);
            return new LoginSession(token);
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private record LoginSession(String token) {
    }
}
