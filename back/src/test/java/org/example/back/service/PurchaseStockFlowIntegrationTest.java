package org.example.back.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.SqlSessionFactory;
import org.example.back.common.exception.BusinessException;
import org.example.back.config.MyMetaObjectHandler;
import org.example.back.controller.PurchaseController;
import org.example.back.dto.LoginResponse;
import org.example.back.dto.PurchaseSaveDTO;
import org.example.back.entity.BizStockFlow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real MySQL transactions with simulated identity and explicitly targeted test-only write faults. */
@SpringJUnitConfig(PurchaseStockFlowIntegrationTest.TestConfig.class)
class PurchaseStockFlowIntegrationTest {
    @Autowired private DataSource dataSource;
    @Autowired private PurchaseController controller;
    @Autowired private AuthService authService;
    @Autowired private PurchaseService purchaseService;
    @Autowired private StockFlowInsertFault stockFlowInsertFault;

    @Test
    @DisplayName("TC-02: purchase saves one linked stock flow and increases stock from 10 to 15")
    void tc02_purchasePersistsLinkedStockFlow() throws Exception {
        JdbcTemplate database = new JdbcTemplate(dataSource);
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String goodsCode = "PT" + suffix.substring(0, 18);
        String marker = "psf-TC02-" + suffix;
        database.update("""
                INSERT INTO base_goods (goods_code, goods_name, supplier_id, purchase_price, stock, status)
                VALUES (?, ?, 1, 10.00, 10, 1)
                """, goodsCode, "采购流水测试商品");
        Long goodsId = database.queryForObject("SELECT id FROM base_goods WHERE goods_code = ?", Long.class, goodsCode);

        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(900001L);
        user.setRealName("采购测试员");
        user.setRole("admin");
        user.setDeptCode("purchase");
        when(authService.getUserInfo()).thenReturn(user);

        LocalDateTime started = database.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class).toLocalDateTime();
        MockMvc http = MockMvcBuilders.standaloneSetup(controller).build();
        http.perform(post("/business/purchases").contentType("application/json").content("""
                {"goodsId":%d,"quantity":5,"unitPrice":10.00,"remark":"%s"}
                """.formatted(goodsId, marker)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // The service transaction has ended. These queries use separate connections.
        LocalDateTime finished = database.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class).toLocalDateTime();
        List<Map<String, Object>> purchases = database.queryForList("SELECT * FROM biz_purchase WHERE remark = ?", marker);
        assertEquals(1, purchases.size(), "Exactly one purchase must be committed");
        Map<String, Object> purchase = purchases.get(0);
        List<Map<String, Object>> flows = database.queryForList("SELECT * FROM biz_stock_flow WHERE goods_id = ?", goodsId);
        assertEquals(1, flows.size(), "Exactly one stock flow must be committed");
        Map<String, Object> flow = flows.get(0);
        assertEquals(15, database.queryForObject("SELECT stock FROM base_goods WHERE id = ?", Integer.class, goodsId));
        assertEquals(new BigDecimal("50.00"), purchase.get("total_price"));
        assertEquals(goodsId, purchase.get("goods_id"));
        assertEquals(5, purchase.get("quantity"));
        assertEquals(new BigDecimal("10.00"), purchase.get("unit_price"));
        assertNotNull(flow.get("id"));
        assertEquals(goodsId, flow.get("goods_id"));
        assertEquals("采购流水测试商品", flow.get("goods_name"));
        assertEquals(purchase.get("goods_name"), flow.get("goods_name"));
        assertEquals(5, flow.get("change_quantity"));
        assertEquals(10, flow.get("before_stock"));
        assertEquals(15, flow.get("after_stock"));
        assertEquals("purchase", flow.get("biz_type"));
        assertEquals(purchase.get("id"), flow.get("biz_id"));
        assertEquals(purchase.get("purchase_no"), flow.get("biz_no"));
        assertEquals(900001L, flow.get("operator_id"));
        assertEquals("采购测试员", flow.get("operator_name"));
        assertEquals(purchase.get("operator_id"), flow.get("operator_id"));
        assertEquals(purchase.get("operator_name"), flow.get("operator_name"));
        assertEquals(purchase.get("operation_time"), flow.get("operation_time"));
        assertEquals("采购入库", flow.get("remark"));
        LocalDateTime created = database.queryForObject(
                "SELECT create_time FROM biz_stock_flow WHERE id = ?", Timestamp.class, flow.get("id")).toLocalDateTime();
        assertFalse(created.isBefore(started));
        assertFalse(created.isAfter(finished.plusSeconds(1)));
        System.out.printf("TC-02 evidence: goodsId=%s purchaseId=%s purchaseNo=%s flowId=%s stock=10/+5/15 marker=%s%n",
                goodsId, purchase.get("id"), purchase.get("purchase_no"), flow.get("id"), marker);
    }

    @Test
    @DisplayName("TC-27: a stock flow insert returning zero rolls back the purchase and stock increase")
    void tc27_zeroFlowInsertRollsBackPurchaseAndStock() {
        JdbcTemplate database = new JdbcTemplate(dataSource);
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String goodsCode = "PF" + suffix.substring(0, 18);
        String marker = "psf-TC27-" + suffix;
        database.update("""
                INSERT INTO base_goods (goods_code, goods_name, supplier_id, purchase_price, stock, status)
                VALUES (?, ?, 1, 10.00, 10, 1)
                """, goodsCode, "流水保存失败检查商品");
        Long goodsId = database.queryForObject("SELECT id FROM base_goods WHERE goods_code = ?", Long.class, goodsCode);

        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(900001L);
        user.setRealName("采购测试员");
        user.setRole("admin");
        user.setDeptCode("purchase");
        when(authService.getUserInfo()).thenReturn(user);

        PurchaseSaveDTO request = new PurchaseSaveDTO();
        request.setGoodsId(goodsId);
        request.setQuantity(5);
        request.setUnitPrice(new BigDecimal("10.00"));
        request.setRemark(marker);

        stockFlowInsertFault.goodsId = goodsId;
        stockFlowInsertFault.hits = 0;
        BusinessException failure = null;
        try {
            purchaseService.create(request);
        } catch (BusinessException exception) {
            failure = exception;
        } finally {
            // Restore normal mapper behavior even when the request or assertions fail.
            stockFlowInsertFault.goodsId = null;
        }

        // Outside the service transaction: independent connections observe committed state.
        int stock = database.queryForObject("SELECT stock FROM base_goods WHERE id = ?", Integer.class, goodsId);
        int purchases = database.queryForObject("SELECT COUNT(*) FROM biz_purchase WHERE goods_id = ?", Integer.class, goodsId);
        int flows = database.queryForObject("SELECT COUNT(*) FROM biz_stock_flow WHERE goods_id = ?", Integer.class, goodsId);
        System.out.printf("TC-27 evidence: goodsId=%s marker=%s faultHits=%d businessException=%s stock=%d purchases=%d flows=%d%n",
                goodsId, marker, stockFlowInsertFault.hits, failure != null, stock, purchases, flows);
        final BusinessException observedFailure = failure;
        assertEquals(1, stockFlowInsertFault.hits, "The targeted flow insert must actually return zero");
        assertAll("Failed stock flow persistence must roll back the whole purchase",
                () -> assertNotNull(observedFailure, "Service must report failure instead of returning normally"),
                () -> assertEquals(10, stock, "Stock increase must be rolled back"),
                () -> assertEquals(0, purchases, "Purchase insert must be rolled back"),
                () -> assertEquals(0, flows, "No orphan flow may remain"));
    }

    @Intercepts(@Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}))
    static class StockFlowInsertFault implements Interceptor {
        private Long goodsId;
        boolean failEmployee;
        private int hits;

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
            Object parameter = invocation.getArgs()[1];
            if (failEmployee && statement.getId().startsWith("org.example.back.mapper.SysEmployeeMapper.") && (statement.getId().endsWith("insert") || statement.getId().endsWith("updateById"))) throw new IllegalStateException("test employee write fault");
            if (goodsId != null
                    && statement.getId().equals("org.example.back.mapper.BizStockFlowMapper.insert")
                    && parameter instanceof BizStockFlow flow
                    && goodsId.equals(flow.getGoodsId())) {
                hits++;
                return 0;
            }
            return invocation.proceed();
        }
    }

    @Configuration
    @EnableTransactionManagement
    @MapperScan("org.example.back.mapper")
    @Import({PurchaseService.class, PurchaseController.class, AuthzService.class})
    static class TestConfig {
        @Bean
        DataSource dataSource() throws Exception {
            String url = System.getenv("PURCHASE_TEST_JDBC_URL");
            assertNotNull(url, "Explicit test connection required; never use application.properties");
            assertTrue(url.matches("jdbc:mysql://localhost:3306/warehouse_management_integration_test(?:\\?.*)?"),
                    "Writes are restricted to the dedicated local purchase test database");
            DriverManagerDataSource source = new DriverManagerDataSource(url,
                    System.getenv("PURCHASE_TEST_USERNAME"), System.getenv("PURCHASE_TEST_PASSWORD"));
            try (var connection = source.getConnection()) {
                assertEquals("warehouse_management_integration_test", connection.getCatalog());
            }
            return source;
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource source, StockFlowInsertFault fault) throws Exception {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            GlobalConfig global = new GlobalConfig();
            global.setDbConfig(new GlobalConfig.DbConfig());
            global.setMetaObjectHandler(new MyMetaObjectHandler());
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(source);
            factory.setConfiguration(configuration);
            factory.setGlobalConfig(global);
            factory.setPlugins(fault);
            factory.setMapperLocations(new org.springframework.core.io.support.PathMatchingResourcePatternResolver().getResources("classpath*:mapper/*.xml"));
            return factory.getObject();
        }

        @Bean
        StockFlowInsertFault stockFlowInsertFault() {
            return new StockFlowInsertFault();
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource source) {
            return new DataSourceTransactionManager(source);
        }

        @Bean
        AuthService authService() {
            return mock(AuthService.class);
        }
    }
}
