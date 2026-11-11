-- 数据 Agent 会话、消息和工具调用轨迹
-- 仅新增表，不修改现有 AI 助手表

CREATE TABLE IF NOT EXISTS `ai_agent_conversation` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `title` VARCHAR(100) NOT NULL COMMENT '会话标题',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_agent_user_updated` (`user_id`, `updated_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据Agent会话表';

CREATE TABLE IF NOT EXISTS `ai_agent_message` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `conversation_id` BIGINT NOT NULL COMMENT '所属会话ID',
    `role` VARCHAR(20) NOT NULL COMMENT '消息角色: user/assistant',
    `content` TEXT NOT NULL COMMENT '消息内容',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_agent_message_conv` (`conversation_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据Agent消息表';

CREATE TABLE IF NOT EXISTS `ai_agent_tool_call` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `conversation_id` BIGINT NOT NULL COMMENT '所属会话ID',
    `message_id` BIGINT DEFAULT NULL COMMENT '关联的assistant消息ID',
    `seq` INT NOT NULL COMMENT '本次请求内的调用顺序',
    `tool_name` VARCHAR(64) NOT NULL COMMENT '工具名称',
    `arguments_json` TEXT DEFAULT NULL COMMENT '模型传入的参数JSON',
    `result_summary_json` TEXT DEFAULT NULL COMMENT '工具返回结果摘要JSON',
    `row_count` INT DEFAULT NULL COMMENT '结果行数',
    `success` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否成功',
    `error_message` VARCHAR(500) DEFAULT NULL COMMENT '错误信息',
    `latency_ms` BIGINT DEFAULT NULL COMMENT '工具耗时（毫秒）',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_agent_tool_conv` (`conversation_id`, `id`),
    KEY `idx_agent_tool_name` (`conversation_id`, `tool_name`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据Agent工具调用轨迹表';
