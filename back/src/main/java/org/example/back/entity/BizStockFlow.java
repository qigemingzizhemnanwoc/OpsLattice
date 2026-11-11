package org.example.back.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("biz_stock_flow")
public class BizStockFlow {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long goodsId;
    private String goodsName;

    private Integer changeQuantity;
    private Integer beforeStock;
    private Integer afterStock;

    private String bizType;//为什么变
    private Long bizId;//哪张业务单导致的
    private String bizNo;

    private Long operatorId;
    private String operatorName;

    private String remark;
    private LocalDateTime operationTime;
    private LocalDateTime createTime;
}
