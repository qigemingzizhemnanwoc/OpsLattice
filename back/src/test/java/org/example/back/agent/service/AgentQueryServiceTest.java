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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentQueryServiceTest {

    @Mock
    private GoodsService goodsService;

    @Mock
    private AuthzService authzService;

    @Mock
    private SalesChartService salesChartService;

    @Mock
    private AgentSalesQueryMapper salesQueryMapper;

    @InjectMocks
    private AgentQueryService service;

    @Test
    void lowStock_shouldReuseGoodsServiceWarningQueryAndClampLimit() {
        GoodsVO goods = new GoodsVO();
        goods.setId(1L);
        goods.setGoodsCode("GD001");
        goods.setGoodsName("电阻10K");
        goods.setStock(2);
        goods.setWarningStock(10);
        goods.setUnit("个");
        goods.setStatus(1);
        when(goodsService.page(any(GoodsQueryDTO.class)))
                .thenReturn(new PageResult<>(List.of(goods), 1L, 1L, 20L, 1L));

        Map<String, Object> result = service.queryLowStockGoods("电阻", 99, "low");

        ArgumentCaptor<GoodsQueryDTO> captor = ArgumentCaptor.forClass(GoodsQueryDTO.class);
        verify(goodsService).page(captor.capture());
        GoodsQueryDTO query = captor.getValue();
        assertTrue(Boolean.TRUE.equals(query.getWarningOnly()));
        assertEquals(50L, query.getPageSize());
        assertEquals("电阻", query.getGoodsName());
        assertEquals(1L, result.get("total"));
        assertEquals(1, result.get("returned"));
        List<?> rows = (List<?>) result.get("rows");
        Map<?, ?> row = (Map<?, ?>) rows.get(0);
        assertEquals(1L, row.get("goodsId"));
        assertEquals("电阻10K", row.get("goodsName"));
        assertEquals(-8, row.get("gap"));
    }

    @Test
    void lowStockZero_shouldPassZeroWarningType() {
        when(goodsService.page(any(GoodsQueryDTO.class)))
                .thenReturn(new PageResult<>(List.of(), 0L, 1L, 20L, 0L));

        service.queryLowStockGoods(null, 20, "zero");

        ArgumentCaptor<GoodsQueryDTO> captor = ArgumentCaptor.forClass(GoodsQueryDTO.class);
        verify(goodsService).page(captor.capture());
        assertEquals("zero", captor.getValue().getWarningType());
    }

    @Test
    void goodsSales_shouldReturnNetQuantityAndAmountForRequestedGoods() {
        doNothing().when(authzService)
                .requireDeptAdminOrSuperAdmin(eq(AuthzService.DEPT_SALES), anyString());

        GoodsSalesAgg sale = new GoodsSalesAgg();
        sale.setGoodsId(1L);
        sale.setGoodsName("电阻10K");
        sale.setOrderCount(2L);
        sale.setQuantity(10L);
        sale.setAmount(new BigDecimal("100.00"));
        sale.setLastSaleTime(LocalDateTime.of(2026, 11, 1, 10, 0));
        when(salesQueryMapper.summarizeSalesByGoods(anyList(), any(), any())).thenReturn(List.of(sale));

        GoodsSalesAgg ret = new GoodsSalesAgg();
        ret.setGoodsId(1L);
        ret.setQuantity(2L);
        ret.setAmount(new BigDecimal("20.00"));
        when(salesQueryMapper.summarizeReturnsByGoods(anyList(), any(), any())).thenReturn(List.of(ret));

        Map<String, Object> result = service.queryGoodsSales(List.of(1L), null, null, null);

        List<?> rows = (List<?>) result.get("rows");
        Map<?, ?> row = (Map<?, ?>) rows.get(0);
        assertEquals(10L, row.get("salesQuantity"));
        assertEquals(2L, row.get("returnQuantity"));
        assertEquals(8L, row.get("netQuantity"));
        assertEquals(new BigDecimal("80.00"), row.get("netAmount"));
        assertEquals("2026-11-01 10:00:00", row.get("lastSaleTime"));
        assertEquals(1, result.get("total"));
        verify(authzService).requireDeptAdminOrSuperAdmin(eq(AuthzService.DEPT_SALES), anyString());
    }

    @Test
    void goodsSales_shouldKeepZeroRowsForGoodsWithoutSales() {
        doNothing().when(authzService)
                .requireDeptAdminOrSuperAdmin(eq(AuthzService.DEPT_SALES), anyString());
        when(salesQueryMapper.summarizeSalesByGoods(anyList(), any(), any())).thenReturn(List.of());
        when(salesQueryMapper.summarizeReturnsByGoods(anyList(), any(), any())).thenReturn(List.of());

        Map<String, Object> result = service.queryGoodsSales(List.of(1L), null, null, null);

        List<?> rows = (List<?>) result.get("rows");
        Map<?, ?> row = (Map<?, ?>) rows.get(0);
        assertEquals(1L, row.get("goodsId"));
        assertEquals(0L, row.get("salesQuantity"));
        assertEquals(BigDecimal.ZERO, row.get("salesAmount"));
        assertEquals(0, result.get("total"));
    }

    @Test
    void goodsSales_shouldPropagateExistingSalesPermissionDenial() {
        doThrow(BusinessException.forbidden("仅销售部门管理员可访问销售模块"))
                .when(authzService)
                .requireDeptAdminOrSuperAdmin(eq(AuthzService.DEPT_SALES), anyString());

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.queryGoodsSales(List.of(1L), null, null, null));

        assertEquals(403, error.getCode());
        verifyNoInteractions(salesQueryMapper);
    }

    @Test
    void salesOverview_shouldReuseSalesChartService() {
        ChartOverviewVO overview = new ChartOverviewVO();
        overview.setSalesAmount(new BigDecimal("1000.00"));
        overview.setReturnAmount(new BigDecimal("100.00"));
        overview.setNetSalesAmount(new BigDecimal("900.00"));
        overview.setSalesQuantity(10L);
        overview.setReturnQuantity(1L);
        when(salesChartService.getOverview(any())).thenReturn(overview);

        SalesTop5VO top5 = new SalesTop5VO();
        top5.setNameList(List.of("电阻10K"));
        top5.setDataList(List.of(10L));
        when(salesChartService.getTop5(any())).thenReturn(top5);

        Map<String, Object> result = service.querySalesOverview(null, null);

        assertEquals(new BigDecimal("1000.00"), result.get("salesAmount"));
        assertEquals(new BigDecimal("900.00"), result.get("netSalesAmount"));
        assertEquals(List.of("电阻10K"), result.get("topGoodsNames"));
    }

    @Test
    void salesOverview_withOnlyEndDate_shouldUseExplicitActualRange() {
        when(salesChartService.getOverview(any())).thenReturn(new ChartOverviewVO());
        when(salesChartService.getTop5(any())).thenReturn(new SalesTop5VO());

        Map<String, Object> result = service.querySalesOverview(null, "2026-11-05");

        ArgumentCaptor<SalesChartQueryDTO> captor = ArgumentCaptor.forClass(SalesChartQueryDTO.class);
        verify(salesChartService).getOverview(captor.capture());
        assertEquals(LocalDate.of(1970, 1, 1), captor.getValue().getStartDate());
        assertEquals(LocalDate.of(2026, 11, 5), captor.getValue().getEndDate());
        assertEquals("1970-01-01 ~ 2026-11-05", result.get("range"));
    }

    @Test
    void salesOverview_withStartAfterEnd_shouldSwapAndLabelActualRange() {
        when(salesChartService.getOverview(any())).thenReturn(new ChartOverviewVO());
        when(salesChartService.getTop5(any())).thenReturn(new SalesTop5VO());

        Map<String, Object> result = service.querySalesOverview("2026-11-10", "2026-11-01");

        ArgumentCaptor<SalesChartQueryDTO> captor = ArgumentCaptor.forClass(SalesChartQueryDTO.class);
        verify(salesChartService).getOverview(captor.capture());
        assertEquals(LocalDate.of(2026, 11, 1), captor.getValue().getStartDate());
        assertEquals(LocalDate.of(2026, 11, 10), captor.getValue().getEndDate());
        assertEquals("2026-11-01 ~ 2026-11-10", result.get("range"));
    }

    @Test
    void goodsSales_withOnlyEndDate_shouldUseSameRangeRule() {
        doNothing().when(authzService)
                .requireDeptAdminOrSuperAdmin(eq(AuthzService.DEPT_SALES), anyString());
        when(salesQueryMapper.summarizeSalesByGoods(anyList(), any(), any())).thenReturn(List.of());
        when(salesQueryMapper.summarizeReturnsByGoods(anyList(), any(), any())).thenReturn(List.of());

        Map<String, Object> result = service.queryGoodsSales(List.of(1L), null, null, "2026-11-05");

        ArgumentCaptor<LocalDateTime> startCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> endCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(salesQueryMapper).summarizeSalesByGoods(anyList(), startCaptor.capture(), endCaptor.capture());
        assertEquals(LocalDate.of(1970, 1, 1).atStartOfDay(), startCaptor.getValue());
        assertEquals(LocalDate.of(2026, 11, 6).atStartOfDay(), endCaptor.getValue());
        assertEquals("1970-01-01 ~ 2026-11-05", result.get("range"));
    }

    @Test
    void goodsSales_withStartAfterEnd_shouldSwapActualRange() {
        doNothing().when(authzService)
                .requireDeptAdminOrSuperAdmin(eq(AuthzService.DEPT_SALES), anyString());
        when(salesQueryMapper.summarizeSalesByGoods(anyList(), any(), any())).thenReturn(List.of());
        when(salesQueryMapper.summarizeReturnsByGoods(anyList(), any(), any())).thenReturn(List.of());

        Map<String, Object> result = service.queryGoodsSales(List.of(1L), null, "2026-11-10", "2026-11-01");

        ArgumentCaptor<LocalDateTime> startCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> endCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(salesQueryMapper).summarizeSalesByGoods(anyList(), startCaptor.capture(), endCaptor.capture());
        assertEquals(LocalDate.of(2026, 11, 1).atStartOfDay(), startCaptor.getValue());
        assertEquals(LocalDate.of(2026, 11, 11).atStartOfDay(), endCaptor.getValue());
        assertEquals("2026-11-01 ~ 2026-11-10", result.get("range"));
    }
}
