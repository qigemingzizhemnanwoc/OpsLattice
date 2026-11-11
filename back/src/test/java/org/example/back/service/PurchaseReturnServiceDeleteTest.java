package org.example.back.service;

import org.example.back.common.exception.BusinessException;
import org.example.back.entity.BizPurchaseReturn;
import org.example.back.mapper.BaseGoodsMapper;
import org.example.back.mapper.BizPurchaseReturnMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class PurchaseReturnServiceDeleteTest {
    @Test
    void losingDeleteMustNotRestoreStock() {
        PurchaseReturnService service = new PurchaseReturnService();
        BizPurchaseReturnMapper sales = mock(BizPurchaseReturnMapper.class);
        BaseGoodsMapper goods = mock(BaseGoodsMapper.class);
        ReflectionTestUtils.setField(service, "bizPurchaseReturnMapper", sales);
        ReflectionTestUtils.setField(service, "baseGoodsMapper", goods);
        ReflectionTestUtils.setField(service, "authzService", mock(AuthzService.class));
        BizPurchaseReturn order = new BizPurchaseReturn();
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
