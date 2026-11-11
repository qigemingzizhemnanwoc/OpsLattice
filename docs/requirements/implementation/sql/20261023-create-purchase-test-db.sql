-- Prepared from read-only SHOW CREATE TABLE on 2026-10-23.

-- Executed successfully on 2026-10-23. Retained for review; do not rerun on the existing schema.

-- Run with a client that stops on the first error; do not use --force.

-- No IF NOT EXISTS: an existing schema must stop execution for review.

-- MySQL DDL commits independently; report a partial failure without automatic cleanup.

CREATE DATABASE `warehouse_management_purchase_test` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE `warehouse_management_purchase_test`.`base_goods` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `goods_code` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '商品编码',
  `goods_name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '商品名称',
  `category` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '商品类别',
  `brand` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '商品品牌(用于图表聚合)',
  `supplier_id` bigint NOT NULL COMMENT '供应商ID',
  `purchase_price` decimal(10,2) DEFAULT NULL COMMENT '进价',
  `sale_price` decimal(10,2) DEFAULT NULL COMMENT '售价',
  `stock` int NOT NULL DEFAULT '0' COMMENT '当前库存量',
  `warning_stock` int NOT NULL DEFAULT '10' COMMENT '库存预警阈值',
  `unit` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '单位',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态: 1-上架, 0-下架',
  `description` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '描述',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除: 0-正常, 1-删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_goods_code` (`goods_code`),
  KEY `idx_supplier_id` (`supplier_id`),
  KEY `idx_supplier_status` (`supplier_id`,`status`),
  KEY `idx_brand` (`brand`),
  KEY `idx_stock` (`stock`),
  KEY `idx_warning_stock` (`warning_stock`),
  KEY `idx_category` (`category`),
  KEY `idx_is_deleted` (`is_deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商品表';

CREATE TABLE `warehouse_management_purchase_test`.`biz_purchase` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `purchase_no` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '进货单号',
  `goods_id` bigint NOT NULL COMMENT '商品ID',
  `goods_name` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '商品名称(冗余字段)',
  `quantity` int NOT NULL COMMENT '进货数量',
  `unit_price` decimal(10,2) NOT NULL COMMENT '进货单价',
  `total_price` decimal(10,2) NOT NULL COMMENT '总金额',
  `operator_id` bigint DEFAULT NULL COMMENT '操作人ID',
  `operator_name` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '操作人姓名(冗余字段)',
  `operation_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作发生时间',
  `remark` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  `biz_status` tinyint NOT NULL DEFAULT '1' COMMENT '业务状态: 1-正常, 2-已作废, 3-红冲单',
  `source_id` bigint DEFAULT NULL COMMENT '红冲来源单ID',
  `void_time` datetime DEFAULT NULL COMMENT '作废时间',
  `void_reason` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '作废原因',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除: 0-正常, 1-删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_purchase_no` (`purchase_no`),
  KEY `idx_goods_id` (`goods_id`),
  KEY `idx_goods_time` (`goods_id`,`operation_time`),
  KEY `idx_goods_status_time` (`goods_id`,`biz_status`,`is_deleted`,`operation_time`,`id`),
  KEY `idx_biz_status` (`biz_status`),
  KEY `idx_source_id` (`source_id`),
  KEY `idx_operator_id` (`operator_id`),
  KEY `idx_operation_time` (`operation_time`),
  KEY `idx_is_deleted` (`is_deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='进货表';

CREATE TABLE `warehouse_management_purchase_test`.`biz_stock_flow` (
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
