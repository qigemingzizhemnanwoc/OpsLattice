package org.example.back.agent.service;

import org.example.back.common.exception.BusinessException;
import org.example.back.common.result.PageResult;
import org.example.back.dto.GoodsQueryDTO;
import org.example.back.dto.SalesChartQueryDTO;
import org.example.back.mapper.AgentSalesQueryMapper;
import org.example.back.mapper.AgentSalesQueryMapper.GoodsSalesAgg;
import org.example.back.service.AuthzService;
import org.example.back.service.GoodsService;
import org.example.back.service.SalesChartService;
import org.example.back.vo.ChartOverviewVO;
import org.example.back.vo.GoodsVO;
import org.example.back.vo.SalesTop5VO;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数据 Agent 的只读查询服务。
 *
 * <p>权限不在这里新造：库存直接复用 GoodsService 的页面级校验，
 * 销售汇总调用与 SalesService 相同的 AuthzService 校验，
 * 财务概览直接复用 SalesChartService 的统计页面校验。</p>
 */
@Service
public class AgentQueryService {

    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final LocalDateTime MIN_TIME = LocalDateTime.of(1970, 1, 1, 0, 0);
    private static final int HARD_MAX_ROWS = 50;

    private final GoodsService goodsService;
    private final AuthzService authzService;
    private final SalesChartService salesChartService;
    private final AgentSalesQueryMapper salesQueryMapper;

    public AgentQueryService(GoodsService goodsService,
                             AuthzService authzService,
                             SalesChartService salesChartService,
                             AgentSalesQueryMapper salesQueryMapper) {
        this.goodsService = goodsService;
        this.authzService = authzService;
        this.salesChartService = salesChartService;
        this.salesQueryMapper = salesQueryMapper;
    }

    public Map<String, Object> queryLowStockGoods(String keyword, Integer limit, String warningType) {
        GoodsQueryDTO dto = new GoodsQueryDTO();
        dto.setPageNum(1L);
        dto.setPageSize((long) normalizeLimit(limit));
        dto.setWarningOnly(true);
        dto.setWarningType("zero".equalsIgnoreCase(warningType) ? "zero" : null);
        if (StringUtils.hasText(keyword)) {
            dto.setGoodsName(keyword.trim());
        }

        PageResult<GoodsVO> page = goodsService.page(dto);
        List<Map<String, Object>> rows = page.getRecords().stream().map(this::toStockRow).toList();

        Map<String, Object> result = baseResult("queryLowStockGoods");
        result.put("definition", "库存不足=当前库存 <= 预警阈值；warningType=zero 时只查零库存");
        result.put("warningType", "zero".equalsIgnoreCase(warningType) ? "zero" : "low");
        result.put("total", page.getTotal());
        result.put("returned", rows.size());
        result.put("rows", rows);
        if (page.getTotal() != null && page.getTotal() > rows.size()) {
            result.put("notice", "结果已按上限截断，仅返回前 " + rows.size() + " 条，共 " + page.getTotal() + " 条");
        }
        return result;
    }

    public Map<String, Object> queryGoodsStock(String goodsName, Integer limit) {
        GoodsQueryDTO dto = new GoodsQueryDTO();
        dto.setPageNum(1L);
        dto.setPageSize((long) normalizeLimit(limit));
        dto.setWarningOnly(false);
        if (StringUtils.hasText(goodsName)) {
            dto.setGoodsName(goodsName.trim());
        }

        PageResult<GoodsVO> page = goodsService.page(dto);
        List<Map<String, Object>> rows = page.getRecords().stream().map(this::toStockRow).toList();

        Map<String, Object> result = baseResult("queryGoodsStock");
        result.put("definition", "商品资料页的当前库存明细");
        result.put("total", page.getTotal());
        result.put("returned", rows.size());
        result.put("rows", rows);
        if (page.getTotal() != null && page.getTotal() > rows.size()) {
            result.put("notice", "结果已按上限截断，仅返回前 " + rows.size() + " 条，共 " + page.getTotal() + " 条");
        }
        return result;
    }

    public Map<String, Object> queryGoodsSales(List<Long> goodsIds,
                                               List<String> goodsNames,
                                               String startDate,
                                               String endDate) {
        // 与 SalesService.requireSalesModuleAccess() 完全相同的权限校验。
        authzService.requireDeptAdminOrSuperAdmin(AuthzService.DEPT_SALES, "仅销售部门管理员可访问销售模块");

        Set<Long> ids = new LinkedHashSet<>();
        if (goodsIds != null) {
            goodsIds.stream().filter(id -> id != null).forEach(ids::add);
        }
        if (goodsNames != null) {
            List<String> names = goodsNames.stream().filter(StringUtils::hasText).map(String::trim).distinct().toList();
            if (!names.isEmpty()) {
                ids.addAll(salesQueryMapper.findGoodsIdsByNames(names));
            }
        }

        Map<String, Object> result = baseResult("queryGoodsSales");
        result.put("definition", "仅统计 biz_status=1 的正常销售单；净额=销售额-退货额");
        DateRange range = resolveDateRange(startDate, endDate);
        result.put("range", range.label());

        if (ids.isEmpty()) {
            result.put("rows", List.of());
            result.put("total", 0);
            result.put("message", "未找到匹配的商品，请先查询库存或提供准确的商品ID/名称");
            return result;
        }

        LocalDateTime startTime = range.start().atStartOfDay();
        LocalDateTime endTime = range.end().plusDays(1).atStartOfDay();
        List<Long> idList = new ArrayList<>(ids);
        List<GoodsSalesAgg> sales = salesQueryMapper.summarizeSalesByGoods(idList, startTime, endTime);
        List<GoodsSalesAgg> returns = salesQueryMapper.summarizeReturnsByGoods(idList, startTime, endTime);

        Map<Long, GoodsSalesAgg> salesMap = new LinkedHashMap<>();
        sales.forEach(item -> salesMap.put(item.getGoodsId(), item));
        Map<Long, GoodsSalesAgg> returnMap = new LinkedHashMap<>();
        returns.forEach(item -> returnMap.put(item.getGoodsId(), item));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Long id : idList) {
            GoodsSalesAgg sale = salesMap.get(id);
            GoodsSalesAgg ret = returnMap.get(id);
            long salesQty = sale == null || sale.getQuantity() == null ? 0L : sale.getQuantity();
            long returnQty = ret == null || ret.getQuantity() == null ? 0L : ret.getQuantity();
            BigDecimal salesAmount = sale == null || sale.getAmount() == null ? BigDecimal.ZERO : sale.getAmount();
            BigDecimal returnAmount = ret == null || ret.getAmount() == null ? BigDecimal.ZERO : ret.getAmount();

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("goodsId", id);
            row.put("goodsName", sale != null ? sale.getGoodsName() : (ret != null ? ret.getGoodsName() : null));
            row.put("orderCount", sale == null || sale.getOrderCount() == null ? 0L : sale.getOrderCount());
            row.put("salesQuantity", salesQty);
            row.put("returnQuantity", returnQty);
            row.put("netQuantity", salesQty - returnQty);
            row.put("salesAmount", salesAmount);
            row.put("returnAmount", returnAmount);
            row.put("netAmount", salesAmount.subtract(returnAmount));
            row.put("lastSaleTime", sale != null && sale.getLastSaleTime() != null
                    ? sale.getLastSaleTime().format(DATE_TIME_FORMAT) : null);
            rows.add(row);
        }

        result.put("total", salesMap.size());
        result.put("rows", rows);
        return result;
    }

    public Map<String, Object> querySalesOverview(String startDate, String endDate) {
        DateRange range = resolveDateRange(startDate, endDate);
        SalesChartQueryDTO dto = new SalesChartQueryDTO();
        dto.setStartDate(range.start());
        dto.setEndDate(range.end());

        // 与销售统计图表页面完全相同的权限校验与统计口径。
        ChartOverviewVO overview = salesChartService.getOverview(dto);
        SalesTop5VO top5 = salesChartService.getTop5(dto);

        Map<String, Object> result = baseResult("querySalesOverview");
        result.put("definition", "与销售统计图表页面同口径");
        result.put("range", range.label());
        result.put("salesAmount", overview.getSalesAmount());
        result.put("returnAmount", overview.getReturnAmount());
        result.put("netSalesAmount", overview.getNetSalesAmount());
        result.put("salesQuantity", overview.getSalesQuantity());
        result.put("returnQuantity", overview.getReturnQuantity());
        result.put("topGoodsNames", top5.getNameList());
        result.put("topGoodsQuantities", top5.getDataList());
        return result;
    }

    private Map<String, Object> baseResult(String tool) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tool", tool);
        result.put("generatedAt", LocalDateTime.now().format(DATE_TIME_FORMAT));
        return result;
    }

    private Map<String, Object> toStockRow(GoodsVO goods) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("goodsId", goods.getId());
        row.put("goodsCode", goods.getGoodsCode());
        row.put("goodsName", goods.getGoodsName());
        row.put("category", goods.getCategory());
        row.put("brand", goods.getBrand());
        row.put("stock", goods.getStock());
        row.put("warningStock", goods.getWarningStock());
        row.put("gap", (goods.getStock() == null ? 0 : goods.getStock())
                - (goods.getWarningStock() == null ? 0 : goods.getWarningStock()));
        row.put("unit", goods.getUnit());
        row.put("status", goods.getStatus());
        return row;
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null) {
            return 20;
        }
        return Math.max(1, Math.min(limit, HARD_MAX_ROWS));
    }

    private LocalDate parseDate(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw BusinessException.validateFail("日期格式应为 yyyy-MM-dd");
        }
    }

    private DateRange resolveDateRange(String startDate, String endDate) {
        LocalDate start = parseDate(startDate);
        LocalDate end = parseDate(endDate);
        boolean noDates = start == null && end == null;
        LocalDate actualStart = start == null ? MIN_TIME.toLocalDate() : start;
        LocalDate actualEnd = end == null ? LocalDate.now() : end;
        if (actualStart.isAfter(actualEnd)) {
            LocalDate temp = actualStart;
            actualStart = actualEnd;
            actualEnd = temp;
        }
        String label = noDates
                ? "全部数据（" + actualStart + " ~ " + actualEnd + "）"
                : actualStart + " ~ " + actualEnd;
        return new DateRange(actualStart, actualEnd, label);
    }

    private record DateRange(LocalDate start, LocalDate end, String label) {
    }
}
