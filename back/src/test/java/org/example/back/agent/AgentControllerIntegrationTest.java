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
import org.example.back.config.SaTokenConfig;
import org.example.back.mapper.AgentSalesQueryMapper;
import org.example.back.mapper.AiAgentConversationMapper;
import org.example.back.mapper.AiAgentMessageMapper;
import org.example.back.mapper.AiAgentToolCallMapper;
import org.example.back.mapper.SysErrorLogMapper;
import org.example.back.service.AuthzService;
import org.example.back.service.GoodsService;
import org.example.back.service.SalesChartService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = AgentControllerIntegrationTest.TestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:agent_controller;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password="
        }
)
@AutoConfigureMockMvc
class AgentControllerIntegrationTest {

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
    }

    private static final AtomicLong USER_SEQUENCE = new AtomicLong(80000L);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
    }

    @Test
    void statusAndTools_shouldFollowExistingRolePermissions() throws Exception {
        LoginSession sales = loginWithRole("admin", "sales");
        mockMvc.perform(get("/assistant/agent/status")
                        .header("Authorization", "Bearer " + sales.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.configured").value(false));

        mockMvc.perform(get("/assistant/agent/tools")
                        .header("Authorization", "Bearer " + sales.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[*].name", containsInAnyOrder("queryLowStockGoods", "queryGoodsSales")));

        LoginSession warehouse = loginWithRole("admin", "warehouse");
        mockMvc.perform(get("/assistant/agent/tools")
                        .header("Authorization", "Bearer " + warehouse.token()))
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[*].name", containsInAnyOrder("queryLowStockGoods", "queryGoodsStock")));

        LoginSession purchase = loginWithRole("admin", "purchase");
        mockMvc.perform(get("/assistant/agent/tools")
                        .header("Authorization", "Bearer " + purchase.token()))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].name").value("queryLowStockGoods"));

        LoginSession finance = loginWithRole("admin", "finance");
        mockMvc.perform(get("/assistant/agent/tools")
                        .header("Authorization", "Bearer " + finance.token()))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].name").value("querySalesOverview"));

        LoginSession employee = loginWithRole("employee", "sales");
        mockMvc.perform(get("/assistant/agent/tools")
                        .header("Authorization", "Bearer " + employee.token()))
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    void chatWithoutKey_shouldPersistMessagesAndReturnNotConfigured() throws Exception {
        LoginSession sales = loginWithRole("admin", "sales");
        mockMvc.perform(post("/assistant/agent/chat")
                        .header("Authorization", "Bearer " + sales.token())
                        .contentType("application/json")
                        .content("{\"question\":\"哪些商品库存不足？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.hitType").value("not-configured"))
                .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.containsString("DEEPSEEK_API_KEY")))
                .andExpect(jsonPath("$.data.conversationId").isNumber());

        Integer messages = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_agent_message", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(2, messages);
    }

    @Test
    void conversationMessages_shouldBeIsolatedByUser() throws Exception {
        LoginSession owner = loginWithRole("admin", "sales");
        String body = mockMvc.perform(post("/assistant/agent/chat")
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType("application/json")
                        .content("{\"question\":\"哪些商品库存不足？\"}"))
                .andReturn().getResponse().getContentAsString();
        Long conversationId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(body).path("data").path("conversationId").asLong();

        LoginSession other = loginWithRole("admin", "sales");
        mockMvc.perform(get("/assistant/agent/conversations/" + conversationId + "/messages")
                        .header("Authorization", "Bearer " + other.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void chatWithOtherConversation_shouldReturnForbidden() throws Exception {
        LoginSession owner = loginWithRole("admin", "sales");
        String body = mockMvc.perform(post("/assistant/agent/chat")
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType("application/json")
                        .content("{\"question\":\"哪些商品库存不足？\"}"))
                .andReturn().getResponse().getContentAsString();
        Long conversationId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(body).path("data").path("conversationId").asLong();

        LoginSession other = loginWithRole("admin", "sales");
        mockMvc.perform(post("/assistant/agent/chat")
                        .header("Authorization", "Bearer " + other.token())
                        .contentType("application/json")
                        .content("{\"question\":\"继续\",\"conversationId\":" + conversationId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void deleteOtherConversation_shouldReturnForbidden() throws Exception {
        LoginSession owner = loginWithRole("admin", "sales");
        String body = mockMvc.perform(post("/assistant/agent/chat")
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType("application/json")
                        .content("{\"question\":\"哪些商品库存不足？\"}"))
                .andReturn().getResponse().getContentAsString();
        Long conversationId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(body).path("data").path("conversationId").asLong();

        LoginSession other = loginWithRole("admin", "sales");
        mockMvc.perform(delete("/assistant/agent/conversations/" + conversationId)
                        .header("Authorization", "Bearer " + other.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));

        Integer remaining = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_agent_conversation WHERE id=?", Integer.class, conversationId);
        assertEquals(1, remaining);
    }

    @Test
    void questionLength_shouldRejectOver500Chars() throws Exception {
        LoginSession sales = loginWithRole("admin", "sales");
        String fiveHundred = "a".repeat(500);
        String fiveHundredOne = "a".repeat(501);

        mockMvc.perform(post("/assistant/agent/chat")
                        .header("Authorization", "Bearer " + sales.token())
                        .contentType("application/json")
                        .content("{\"question\":\"" + fiveHundred + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(post("/assistant/agent/chat")
                        .header("Authorization", "Bearer " + sales.token())
                        .contentType("application/json")
                        .content("{\"question\":\"" + fiveHundredOne + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void listConversations_shouldExcludeOlderThan30Days() {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("INSERT INTO ai_agent_conversation (id,user_id,title,created_at,updated_at) VALUES (?,?,?,?,?)",
                9002L, 90001L, "old", now.minusDays(31), now.minusDays(31));
        jdbcTemplate.update("INSERT INTO ai_agent_conversation (id,user_id,title,created_at,updated_at) VALUES (?,?,?,?,?)",
                9003L, 90001L, "new", now, now);

        var page = conversationService.listConversations(90001L, 1, 10);

        assertEquals(1, page.getRecords().size());
        assertEquals("new", page.getRecords().get(0).getTitle());
    }

    @Test
    void cleanupExpired_shouldDeleteMessagesAndToolCalls() {
        LocalDateTime old = LocalDateTime.now().minusDays(31);
        jdbcTemplate.update("INSERT INTO ai_agent_conversation (id,user_id,title,created_at,updated_at) VALUES (?,?,?,?,?)",
                9100L, 90002L, "expired", old, old);
        jdbcTemplate.update("INSERT INTO ai_agent_message (id,conversation_id,role,content,created_at) VALUES (?,?,?,?,?)",
                9101L, 9100L, "user", "old question", old);
        jdbcTemplate.update("INSERT INTO ai_agent_tool_call "
                        + "(id,conversation_id,message_id,seq,tool_name,arguments_json,result_summary_json,row_count,success,created_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?)",
                9102L, 9100L, 9101L, 1, "queryLowStockGoods", "{}", "{}", 0, true, old);

        int removed = conversationService.cleanupExpired();

        assertEquals(1, removed);
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_agent_conversation WHERE id=9100", Integer.class));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_agent_message WHERE conversation_id=9100", Integer.class));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_agent_tool_call WHERE conversation_id=9100", Integer.class));
    }

    private LoginSession loginWithRole(String role, String deptCode) {
        long userId = USER_SEQUENCE.incrementAndGet();
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
