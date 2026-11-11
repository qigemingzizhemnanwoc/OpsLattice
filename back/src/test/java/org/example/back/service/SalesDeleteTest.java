package org.example.back.service;

import org.example.back.common.exception.BusinessException;
import org.example.back.entity.BizSales;
import org.example.back.mapper.BaseGoodsMapper;
import org.example.back.mapper.BizSalesMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class SalesDeleteTest {
    @Test
    void losingDeleteMustNotRestoreStock() {
        SalesService service = new SalesService();
        BizSalesMapper sales = mock(BizSalesMapper.class);
        BaseGoodsMapper goods = mock(BaseGoodsMapper.class);
        ReflectionTestUtils.setField(service, "bizSalesMapper", sales);
        ReflectionTestUtils.setField(service, "baseGoodsMapper", goods);
        ReflectionTestUtils.setField(service, "authzService", mock(AuthzService.class));
        ReflectionTestUtils.setField(service, "bizSalesReturnMapper", mock(org.example.back.mapper.BizSalesReturnMapper.class));
        BizSales order = new BizSales();
        order.setId(1L);
        order.setGoodsId(2L);
        order.setQuantity(3);
        order.setBizStatus(1);
        order.setOperationTime(LocalDateTime.now());
        when(sales.selectById(1L)).thenReturn(order);
        // Another transaction already changed/deleted the row after our read.
        when(sales.delete(any())).thenReturn(0);
        assertThrows(BusinessException.class, () -> service.delete(1L));
        verifyNoInteractions(goods);
    }
}
