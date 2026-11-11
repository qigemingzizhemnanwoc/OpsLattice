-- Additive migration. Select the intended database explicitly before execution.
-- Stop if the table already exists; compare its schema instead. Never drop existing data.
CREATE TABLE `biz_stock_flow` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `goods_id` bigint NOT NULL COMMENT '商品ID',
  `goods_name` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '商品名称',
  `change_quantity` int NOT NULL COMMENT '库存变化量，正数增加，负数减少',
  `before_stock` int NOT NULL COMMENT '变更前库存',
  `after_stock` int NOT NULL COMMENT '变更后库存',
  `biz_type` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '业务类型: purchase/purchase_return/sales/sales_return/purchase_void等',
  `biz_id` bigint DEFAULT NULL COMMENT '来源业务单ID',
  `biz_no` varchar(30) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '来源业务单号',
  `operator_id` bigint DEFAULT NULL COMMENT '操作人ID',
  `operator_name` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '操作人姓名',
  `remark` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  `operation_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '业务发生时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_goods_id` (`goods_id`),
  KEY `idx_goods_time` (`goods_id`,`operation_time`),
  KEY `idx_biz` (`biz_type`,`biz_id`),
  KEY `idx_operator_id` (`operator_id`),
  KEY `idx_operation_time` (`operation_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='库存变更流水表';
