package org.example.back.agent.service;

import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;

/**
 * 数据 Agent 的工具权限矩阵。
 *
 * <p>规则必须与现有 Java 代码保持一致，不新增角色权限：</p>
 * <ul>
 *     <li>超管：全部只读工具</li>
 *     <li>仓储管理员：库存不足 + 库存明细</li>
 *     <li>采购管理员：仅库存不足（预警中心）</li>
 *     <li>销售管理员：库存不足（预警中心） + 按商品销售</li>
 *     <li>财务管理员：仅销售概览（统计图表）</li>
 *     <li>员工：无数据工具</li>
 * </ul>
 */
@Service
public class AgentAccessPolicy {

    public static final String TOOL_QUERY_LOW_STOCK = "queryLowStockGoods";
    public static final String TOOL_QUERY_GOODS_STOCK = "queryGoodsStock";
    public static final String TOOL_QUERY_GOODS_SALES = "queryGoodsSales";
    public static final String TOOL_QUERY_SALES_OVERVIEW = "querySalesOverview";

    private static final List<String> SUPER_ADMIN_TOOLS = List.of(
            TOOL_QUERY_LOW_STOCK,
            TOOL_QUERY_GOODS_STOCK,
            TOOL_QUERY_GOODS_SALES,
            TOOL_QUERY_SALES_OVERVIEW
    );

    private static final List<String> WAREHOUSE_TOOLS = List.of(
            TOOL_QUERY_LOW_STOCK,
            TOOL_QUERY_GOODS_STOCK
    );

    private static final List<String> PURCHASE_TOOLS = List.of(
            TOOL_QUERY_LOW_STOCK
    );

    private static final List<String> SALES_TOOLS = List.of(
            TOOL_QUERY_LOW_STOCK,
            TOOL_QUERY_GOODS_SALES
    );

    private static final List<String> FINANCE_TOOLS = List.of(
            TOOL_QUERY_SALES_OVERVIEW
    );

    public List<String> allowedTools(String role, String deptCode) {
        String normalizedRole = normalize(role);
        String normalizedDept = normalize(deptCode);

        if ("superadmin".equals(normalizedRole)) {
            return SUPER_ADMIN_TOOLS;
        }
        if (!"admin".equals(normalizedRole)) {
            return List.of();
        }

        return switch (normalizedDept) {
            case "warehouse" -> WAREHOUSE_TOOLS;
            case "purchase" -> PURCHASE_TOOLS;
            case "sales" -> SALES_TOOLS;
            case "finance" -> FINANCE_TOOLS;
            default -> List.of();
        };
    }

    public boolean canUse(String role, String deptCode, String toolName) {
        return toolName != null && allowedTools(role, deptCode).contains(toolName);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
