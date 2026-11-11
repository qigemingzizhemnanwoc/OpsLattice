package org.example.back.mapper;

import lombok.Data;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 数据 Agent 的销售只读聚合查询。
 *
 * <p>权限校验不在这里做：调用方必须先执行与 SalesService 相同的
 * AuthzService 校验，再调用本 Mapper。</p>
 */
public interface AgentSalesQueryMapper {

    @Select("""
            <script>
            SELECT s.goods_id AS goodsId,
                   MAX(s.goods_name) AS goodsName,
                   COUNT(*) AS orderCount,
                   COALESCE(SUM(s.quantity), 0) AS quantity,
                   COALESCE(SUM(s.total_price), 0) AS amount,
                   MAX(s.operation_time) AS lastSaleTime
            FROM biz_sales s
            WHERE s.is_deleted = 0
              AND s.biz_status = 1
              AND s.operation_time <![CDATA[>=]]> #{startTime}
              AND s.operation_time <![CDATA[<]]> #{endTime}
              AND s.goods_id IN
              <foreach collection="goodsIds" item="id" open="(" separator="," close=")">
                #{id}
              </foreach>
            GROUP BY s.goods_id
            </script>
            """)
    List<GoodsSalesAgg> summarizeSalesByGoods(@Param("goodsIds") List<Long> goodsIds,
                                              @Param("startTime") LocalDateTime startTime,
                                              @Param("endTime") LocalDateTime endTime);

    @Select("""
            <script>
            SELECT r.goods_id AS goodsId,
                   MAX(r.goods_name) AS goodsName,
                   COUNT(*) AS orderCount,
                   COALESCE(SUM(r.quantity), 0) AS quantity,
                   COALESCE(SUM(r.total_price), 0) AS amount,
                   MAX(r.operation_time) AS lastSaleTime
            FROM biz_sales_return r
            WHERE r.is_deleted = 0
              AND r.biz_status = 1
              AND r.operation_time <![CDATA[>=]]> #{startTime}
              AND r.operation_time <![CDATA[<]]> #{endTime}
              AND r.goods_id IN
              <foreach collection="goodsIds" item="id" open="(" separator="," close=")">
                #{id}
              </foreach>
            GROUP BY r.goods_id
            </script>
            """)
    List<GoodsSalesAgg> summarizeReturnsByGoods(@Param("goodsIds") List<Long> goodsIds,
                                                @Param("startTime") LocalDateTime startTime,
                                                @Param("endTime") LocalDateTime endTime);

    @Select("""
            <script>
            SELECT DISTINCT s.goods_id
            FROM biz_sales s
            WHERE s.is_deleted = 0
              AND s.goods_name IN
              <foreach collection="goodsNames" item="name" open="(" separator="," close=")">
                #{name}
              </foreach>
            </script>
            """)
    List<Long> findGoodsIdsByNames(@Param("goodsNames") List<String> goodsNames);

    @Data
    class GoodsSalesAgg {
        private Long goodsId;
        private String goodsName;
        private Long orderCount;
        private Long quantity;
        private BigDecimal amount;
        private LocalDateTime lastSaleTime;
    }
}
