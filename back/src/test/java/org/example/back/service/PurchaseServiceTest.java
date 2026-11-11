package org.example.back.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.example.back.common.exception.BusinessException;
import org.example.back.dto.LoginResponse;
import org.example.back.dto.PurchaseSaveDTO;
import org.example.back.entity.BaseGoods;
import org.example.back.entity.BizPurchase;
import org.example.back.mapper.BaseGoodsMapper;
import org.example.back.mapper.BizPurchaseMapper;
import org.example.back.mapper.BizStockFlowMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Unit-level failure guards only; does not prove database rollback. */
@ExtendWith(MockitoExtension.class)
class PurchaseServiceTest {
    @Mock private BizPurchaseMapper bizPurchaseMapper;
    @Mock private BaseGoodsMapper baseGoodsMapper;
    @Mock private BizStockFlowMapper bizStockFlowMapper;
    @Mock private AuthService authService;
    @Mock private AuthzService authzService;
    @InjectMocks private PurchaseService purchaseService;

    @Test
    @DisplayName("TC-25: a purchase insert returning zero must stop before any inventory write")
    void tc25_failedPurchaseInsertStopsInventoryWrites() {
        BaseGoods goods = new BaseGoods();
        goods.setId(101L);
        goods.setGoodsName("采购失败检查商品");
        goods.setStatus(1);
        goods.setStock(10);
        goods.setPurchasePrice(new BigDecimal("10.00"));
        when(baseGoodsMapper.selectById(101L)).thenReturn(goods);

        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(900001L);
        user.setRealName("采购测试员");
        when(authService.getUserInfo()).thenReturn(user);
        when(bizPurchaseMapper.insert(any(BizPurchase.class))).thenReturn(0);

        PurchaseSaveDTO request = new PurchaseSaveDTO();
        request.setGoodsId(101L);
        request.setQuantity(5);
        request.setUnitPrice(new BigDecimal("10.00"));

        assertThrows(BusinessException.class, () -> purchaseService.create(request));
        verify(baseGoodsMapper, never()).update(isNull(), any(Wrapper.class));
        verifyNoInteractions(bizStockFlowMapper);
    }

    @Test
    @DisplayName("TC-28: a purchase without its generated ID must stop before any inventory write")
    void tc28_missingPurchaseIdStopsInventoryWrites() {
        BaseGoods goods = new BaseGoods();
        goods.setId(102L);
        goods.setGoodsName("采购主键检查商品");
        goods.setStatus(1);
        goods.setStock(10);
        goods.setPurchasePrice(new BigDecimal("10.00"));
        when(baseGoodsMapper.selectById(102L)).thenReturn(goods);

        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(900001L);
        user.setRealName("采购测试员");
        when(authService.getUserInfo()).thenReturn(user);
        // Simulate a successful insert whose generated ID was not returned to the purchase object.
        when(bizPurchaseMapper.insert(any(BizPurchase.class))).thenReturn(1);

        PurchaseSaveDTO request = new PurchaseSaveDTO();
        request.setGoodsId(102L);
        request.setQuantity(5);
        request.setUnitPrice(new BigDecimal("10.00"));

        assertThrows(BusinessException.class, () -> purchaseService.create(request));
        verify(baseGoodsMapper, never()).update(isNull(), any(Wrapper.class));
        verifyNoInteractions(bizStockFlowMapper);
    }
}
