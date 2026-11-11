package org.example.back.agent.tool;

import org.example.back.agent.service.AgentAccessPolicy;
import org.example.back.agent.service.AgentQueryService;
import org.example.back.common.exception.BusinessException;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 暴露给 DeepSeek 的只读数据工具。
 *
 * <p>工具实例按请求创建，角色和部门在创建时固定，避免依赖工具执行线程的登录上下文。
 * 工具内部仍会再次校验权限；即使模型越权调用，也不会返回数据。</p>
 */
public class AgentDataTools {

    private final AgentQueryService queryService;
    private final AgentAccessPolicy accessPolicy;
    private final String role;
    private final String deptCode;

    public AgentDataTools(AgentQueryService queryService,
                          AgentAccessPolicy accessPolicy,
                          String role,
                          String deptCode) {
        this.queryService = queryService;
        this.accessPolicy = accessPolicy;
        this.role = role;
        this.deptCode = deptCode;
    }

    @Tool(name = AgentAccessPolicy.TOOL_QUERY_LOW_STOCK,
            description = "查询库存不足的商品。与预警中心同口径：当前库存小于等于预警阈值；"
                    + "warningType=zero 时只查零库存。返回商品ID、名称、当前库存、预警阈值、缺口和上下架状态。"
                    + "当用户问哪些商品库存不足、需要补货、库存预警时使用。")
    public Map<String, Object> queryLowStockGoods(
            @ToolParam(required = false, description = "商品名称关键字，可选") String keyword,
            @ToolParam(required = false, description = "返回条数，1-50，默认20") Integer limit,
            @ToolParam(required = false, description = "low=低库存（默认），zero=零库存") String warningType) {
        if (!accessPolicy.canUse(role, deptCode, AgentAccessPolicy.TOOL_QUERY_LOW_STOCK)) {
            return forbidden("当前角色无权查询库存不足商品");
        }
        try {
            return queryService.queryLowStockGoods(keyword, limit, warningType);
        } catch (BusinessException e) {
            return error(e);
        } catch (Exception e) {
            return error("库存查询失败：" + e.getMessage());
        }
    }

    @Tool(name = AgentAccessPolicy.TOOL_QUERY_GOODS_STOCK,
            description = "按商品名称查询当前库存明细，返回商品ID、名称、当前库存、预警阈值和上下架状态。"
                    + "当用户问某个商品还有多少库存、库存明细时使用。")
    public Map<String, Object> queryGoodsStock(
            @ToolParam(required = false, description = "商品名称关键字，可选；为空时返回库存明细列表") String goodsName,
            @ToolParam(required = false, description = "返回条数，1-50，默认20") Integer limit) {
        if (!accessPolicy.canUse(role, deptCode, AgentAccessPolicy.TOOL_QUERY_GOODS_STOCK)) {
            return forbidden("当前角色无权查询商品库存明细");
        }
        try {
            return queryService.queryGoodsStock(goodsName, limit);
        } catch (BusinessException e) {
            return error(e);
        } catch (Exception e) {
            return error("库存明细查询失败：" + e.getMessage());
        }
    }

    @Tool(name = AgentAccessPolicy.TOOL_QUERY_GOODS_SALES,
            description = "按商品ID或商品名称查询销售情况。返回每个商品的正常销售单数、销量、销售额、"
                    + "退货数量、退货金额、净销量、净额和最近销售时间。仅统计正常销售单和正常退货单。"
                    + "当用户追问这些商品卖得怎么样、销量、销售额、退货情况时使用。"
                    + "优先使用上一轮库存结果中的商品ID。")
    public Map<String, Object> queryGoodsSales(
            @ToolParam(required = false, description = "商品ID列表，优先使用上一轮库存结果中的ID") List<Long> goodsIds,
            @ToolParam(required = false, description = "商品名称列表，仅在拿不到ID时使用") List<String> goodsNames,
            @ToolParam(required = false, description = "开始日期，yyyy-MM-dd；不传表示全部数据") String startDate,
            @ToolParam(required = false, description = "结束日期，yyyy-MM-dd；不传表示截至今天") String endDate) {
        if (!accessPolicy.canUse(role, deptCode, AgentAccessPolicy.TOOL_QUERY_GOODS_SALES)) {
            return forbidden("当前角色无权查询商品销售数据");
        }
        try {
            return queryService.queryGoodsSales(goodsIds, goodsNames, startDate, endDate);
        } catch (BusinessException e) {
            return error(e);
        } catch (Exception e) {
            return error("销售查询失败：" + e.getMessage());
        }
    }

    @Tool(name = AgentAccessPolicy.TOOL_QUERY_SALES_OVERVIEW,
            description = "查询销售统计概览，返回销售额、退货额、净销售额、销量、退货量和销量前五商品。"
                    + "与销售统计图表页面同口径。当用户问整体销售情况、销售趋势或销量排名时使用。")
    public Map<String, Object> querySalesOverview(
            @ToolParam(required = false, description = "开始日期，yyyy-MM-dd；不传表示全部数据") String startDate,
            @ToolParam(required = false, description = "结束日期，yyyy-MM-dd；不传表示截至今天") String endDate) {
        if (!accessPolicy.canUse(role, deptCode, AgentAccessPolicy.TOOL_QUERY_SALES_OVERVIEW)) {
            return forbidden("当前角色无权查询销售统计概览");
        }
        try {
            return queryService.querySalesOverview(startDate, endDate);
        } catch (BusinessException e) {
            return error(e);
        } catch (Exception e) {
            return error("销售概览查询失败：" + e.getMessage());
        }
    }

    private Map<String, Object> forbidden(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("error", "FORBIDDEN");
        result.put("message", message);
        return result;
    }

    private Map<String, Object> error(BusinessException e) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("error", e.getCode() != null && e.getCode() == 403 ? "FORBIDDEN" : "ERROR");
        result.put("message", e.getMessage());
        return result;
    }

    private Map<String, Object> error(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("error", "ERROR");
        result.put("message", message);
        return result;
    }
}
