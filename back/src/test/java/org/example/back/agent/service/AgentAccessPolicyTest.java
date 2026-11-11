package org.example.back.agent.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentAccessPolicyTest {

    private final AgentAccessPolicy policy = new AgentAccessPolicy();

    @Test
    void superAdminCanUseAllTools() {
        assertEquals(
                List.of("queryLowStockGoods", "queryGoodsStock", "queryGoodsSales", "querySalesOverview"),
                policy.allowedTools("superadmin", null)
        );
    }

    @Test
    void salesAdminCanQueryLowStockAndGoodsSales() {
        assertEquals(
                List.of("queryLowStockGoods", "queryGoodsSales"),
                policy.allowedTools("admin", "sales")
        );
    }

    @Test
    void warehouseAdminCanQueryStockButNotSales() {
        assertEquals(
                List.of("queryLowStockGoods", "queryGoodsStock"),
                policy.allowedTools("admin", "warehouse")
        );
    }

    @Test
    void purchaseAdminCanOnlyQueryLowStock() {
        assertEquals(
                List.of("queryLowStockGoods"),
                policy.allowedTools("admin", "purchase")
        );
    }

    @Test
    void financeAdminCanOnlyQuerySalesOverview() {
        assertEquals(
                List.of("querySalesOverview"),
                policy.allowedTools("admin", "finance")
        );
    }

    @Test
    void employeeHasNoTools() {
        assertTrue(policy.allowedTools("employee", "sales").isEmpty());
    }
}
