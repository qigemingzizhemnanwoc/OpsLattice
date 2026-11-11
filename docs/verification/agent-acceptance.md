# 数据 Agent 验收记录

## 1. 状态

- 自动化与无 Key 冒烟：已通过。
- 真实 DeepSeek Key 两轮验收：已通过（会话 `conversationId=5`）。
- 测试库：`warehouse_management_integration_test`；未使用 `warehouse_management` 业务库。
- 日期：2026-11-09。

## 2. 环境

| 项 | 值 |
|---|---|
| JDK | 17.0.17（Microsoft） |
| Spring Boot | 3.4.13 |
| Spring AI | 1.0.9（`spring-ai-deepseek`） |
| 数据库 | MySQL 8，测试库 `warehouse_management_integration_test` |
| Redis | 本地 `redis-server` 5.0.14，端口 16379 |
| 前端 | Vue 3 + Vite 5.4.21 |
| 模型 | DeepSeek `deepseek-chat`（响应元数据返回 `deepseek-flash`） |

## 3. 自动化测试

命令（在仓库根目录执行，测试库参数从本地 `back/integration.local` 注入）：

```text
mvn -B -f back/pom.xml -Dtest=ContainerAdminInitializerTest,RedisDuplicateIntegrationTest,EmployeeAcceptanceTest,EmployeeDirectoryIntegrationTest,EmployeeReadOnlyTest,EmployeeValidationTest,EmployeeServiceTest,AuthServiceTest,UserManageServiceTest,SalesConcurrencyIntegrationTest,PurchaseStockFlowIntegrationTest,*DeleteTest,PurchaseServiceTest,AgentAccessPolicyTest,AgentQueryServiceTest,AgentChatFlowIntegrationTest,AgentControllerIntegrationTest test
```

结果：

```text
Tests run: 60, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

新增的 16 项 Agent 测试：

| 测试类 | 项数 | 覆盖 |
|---|---:|---|
| `AgentAccessPolicyTest` | 6 | 6 个角色的工具权限矩阵 |
| `AgentQueryServiceTest` | 6 | 库存不足/零库存、销售净额、无销售商品、权限拒绝、财务概览 |
| `AgentChatFlowIntegrationTest` | 1 | 假模型驱动两轮：库存不足 → 用上一轮商品 ID 查销售 |
| `AgentControllerIntegrationTest` | 3 | 无 Key 状态、各角色工具列表、会话归属隔离 |

## 4. 无 Key 冒烟

- 后端使用测试库启动，未设置 `DEEPSEEK_API_KEY`。
- `GET /api/v3/api-docs`：HTTP 200，OpenAPI 文档正常返回。
- `GET /api/doc.html`：HTTP 200。
- `POST /api/auth/login`（`sales_admin`）：HTTP 200，返回 token。
- `GET /api/assistant/agent/status`：`configured=false`，`allowedTools=[queryLowStockGoods, queryGoodsSales]`。
- `POST /api/assistant/agent/chat`：HTTP 200，`hitType=not-configured`，回答提示配置 `DEEPSEEK_API_KEY`；会话与消息正常落库。

## 5. 权限矩阵

| 工具 | 超管 | 仓储 | 采购 | 销售 | 财务 | 员工 |
|---|---|---|---|---|---|---|
| `queryLowStockGoods` | 可用 | 可用 | 可用 | 可用 | 不可用 | 不可用 |
| `queryGoodsStock` | 可用 | 可用 | 不可用 | 不可用 | 不可用 | 不可用 |
| `queryGoodsSales` | 可用 | 不可用 | 不可用 | 可用 | 不可用 | 不可用 |
| `querySalesOverview` | 可用 | 不可用 | 不可用 | 不可用 | 可用 | 不可用 |

权限依据：库存工具直接调用 `GoodsService.page`；销售工具调用与 `SalesService` 相同的 `AuthzService` 校验；销售概览直接调用 `SalesChartService`。

## 6. 真实 Key 两轮验收（已通过）

准备：

1. 在 `back/assistant-llm.env` 填写 `DEEPSEEK_API_KEY`。
2. 使用测试库启动后端，登录 `sales_admin`（测试库默认密码 `123456`）。
3. 在测试库插入 2 个低库存商品及对应销售记录（脚本执行后保留记录，便于复核）。

演示数据 SQL（仅测试库；测试库已有其他测试数据，用“Agent演示”关键字过滤）：

```sql
DELETE FROM biz_sales WHERE sales_no LIKE 'AGS-DEMO-%';
DELETE FROM base_goods WHERE goods_code LIKE 'AGT-DEMO-%';

INSERT INTO base_goods
    (goods_code, goods_name, category, brand, supplier_id, purchase_price, sale_price,
     stock, warning_stock, unit, status, description)
SELECT 'AGT-DEMO-001', 'Agent演示电阻', '电子配件', '演示品牌', s.id, 1.00, 2.00,
       2, 10, '个', 1, '数据Agent验收演示数据'
FROM base_supplier s ORDER BY s.id LIMIT 1;

INSERT INTO base_goods
    (goods_code, goods_name, category, brand, supplier_id, purchase_price, sale_price,
     stock, warning_stock, unit, status, description)
SELECT 'AGT-DEMO-002', 'Agent演示电容', '电子配件', '演示品牌', s.id, 1.00, 2.00,
       5, 10, '个', 1, '数据Agent验收演示数据'
FROM base_supplier s ORDER BY s.id LIMIT 1;

INSERT INTO biz_sales
    (sales_no, goods_id, goods_name, quantity, unit_price, total_price,
     operator_id, operator_name, operation_time, remark, biz_status)
SELECT 'AGS-DEMO-001', g.id, g.goods_name, 10, 2.00, 20.00,
       u.id, u.real_name, '2026-11-01 10:00:00', '数据Agent验收演示', 1
FROM base_goods g JOIN sys_user u ON u.username = 'sales_admin'
WHERE g.goods_code = 'AGT-DEMO-001';

INSERT INTO biz_sales
    (sales_no, goods_id, goods_name, quantity, unit_price, total_price,
     operator_id, operator_name, operation_time, remark, biz_status)
SELECT 'AGS-DEMO-002', g.id, g.goods_name, 4, 2.00, 8.00,
       u.id, u.real_name, '2026-11-02 10:00:00', '数据Agent验收演示', 1
FROM base_goods g JOIN sys_user u ON u.username = 'sales_admin'
WHERE g.goods_code = 'AGT-DEMO-002';
```

本次已插入的演示商品 ID：`370`（Agent演示电阻，库存 2/阈值 10）、`371`（Agent演示电容，库存 5/阈值 10）；销售记录：`370` 销量 10、销售额 20.00，`371` 销量 4、销售额 8.00。

第一轮问题（用关键字过滤测试库其他数据）：

```text
哪些 Agent演示商品的库存不足？
```

第一轮结果（`conversationId=5`，`hitType=agent`，`modelCode=deepseek-flash`，`latencyMs=2907`）：

```json
{
  "toolCalls": [
    {
      "seq": 1,
      "toolName": "queryLowStockGoods",
      "arguments": "{\"keyword\": \"Agent演示\"}",
      "rowCount": 2,
      "success": true,
      "latencyMs": 8
    }
  ]
}
```

回答原文：

```text
**结论：共查到 2 个“Agent演示”系列商品库存不足，均处于上架状态，需要补货。**

统计口径：当前库存 ≤ 预警阈值（低库存预警，与预警中心同口径）；数据生成时间 2026-11-09 22:22:53。

| 商品ID | 商品编码 | 商品名称 | 当前库存 | 预警阈值 | 缺口 | 状态 |
|---|---|---|---|---|---|---|
| 370 | AGT-DEMO-001 | Agent演示电阻 | 2 个 | 10 个 | -8 | 上架 |
| 371 | AGT-DEMO-002 | Agent演示电容 | 5 个 | 10 个 | -5 | 上架 |

其中 Agent演示电阻 库存最紧张，仅剩 2 个，缺口 8 个。如需查看这两个商品的销量和退货情况，可以继续告诉我。
```

第二轮问题：

```text
这些商品的销售情况怎么样？
```

第二轮结果（同一 `conversationId=5`，`hitType=agent`，`modelCode=deepseek-flash`，`latencyMs=4057`）：

```json
{
  "toolCalls": [
    {
      "seq": 1,
      "toolName": "queryGoodsSales",
      "arguments": "{\"goodsIds\": [370, 371]}",
      "rowCount": 2,
      "success": true,
      "latencyMs": 9
    }
  ]
}
```

回答原文：

```text
**结论：这两个商品销量都很小，各只有 1 笔正常销售单，且均无退货；结合当前库存看，Agent演示电阻已卖出 10 个、库存仅剩 2 个，补货优先级更高。**

统计口径：仅统计正常销售单和正常退货单（biz_status=1）；时间范围为全部数据，截至 2026-11-09；净销量=销量-退货量，净额=销售额-退货额。

| 商品ID | 商品名称 | 销售单数 | 销量 | 销售额 | 退货量 | 退货额 | 净销量 | 净额 | 最近销售时间 |
|---|---|---|---|---|---|---|---|---|---|
| 370 | Agent演示电阻 | 1 | 10 | 20.00 元 | 0 | 0.00 元 | 10 | 20.00 元 | 2026-11-01 10:00:00 |
| 371 | Agent演示电容 | 1 | 4 | 8.00 元 | 0 | 0.00 元 | 4 | 8.00 元 | 2026-11-02 10:00:00 |
| 合计 | — | 2 | 14 | 28.00 元 | 0 | 0.00 元 | 14 | 28.00 元 | — |

补充说明：
- 两个商品均无退货，净销量与销量一致。
- 最近销售时间距今已超过一周（11-01、11-02），近期没有新的成交记录。
- 单价约 2.00 元/个，属于低值演示类商品；建议按缺口补货（电阻 8 个、电容 5 个）即可，无需大批量备货。
```

SQL 对账（测试库，原始输出）：

```text
connected catalog=warehouse_management_integration_test
SQL> SELECT id, goods_code, stock, warning_stock, status FROM base_goods WHERE is_deleted=0 AND goods_code LIKE 'AGT-DEMO-%' ORDER BY goods_code
id=370 | goods_code=AGT-DEMO-001 | stock=2 | warning_stock=10 | status=1
id=371 | goods_code=AGT-DEMO-002 | stock=5 | warning_stock=10 | status=1

SQL> SELECT goods_id, COUNT(*) AS order_count, COALESCE(SUM(quantity),0) AS qty, COALESCE(SUM(total_price),0) AS amount FROM biz_sales WHERE is_deleted=0 AND biz_status=1 AND goods_id IN (370,371) GROUP BY goods_id ORDER BY goods_id
goods_id=370 | order_count=1 | qty=10 | amount=20.00
goods_id=371 | order_count=1 | qty=4 | amount=8.00

SQL> SELECT goods_id, COALESCE(SUM(quantity),0) AS return_qty, COALESCE(SUM(total_price),0) AS return_amount FROM biz_sales_return WHERE is_deleted=0 AND biz_status=1 AND goods_id IN (370,371) GROUP BY goods_id ORDER BY goods_id
（无退货记录）

SQL> SELECT id, seq, tool_name, row_count, success, latency_ms FROM ai_agent_tool_call WHERE conversation_id=5 ORDER BY id
id=4 | seq=1 | tool_name=queryLowStockGoods | row_count=2 | success=true | latency_ms=8
id=5 | seq=1 | tool_name=queryGoodsSales | row_count=2 | success=true | latency_ms=9

SQL> SELECT id, role, CHAR_LENGTH(content) AS content_length FROM ai_agent_message WHERE conversation_id=5 ORDER BY id
id=9 | role=user | content_length=18
id=10 | role=assistant | content_length=362
id=11 | role=user | content_length=13
id=12 | role=assistant | content_length=635
```

结论：

- 第一轮只调用了 `queryLowStockGoods`，参数带关键字，返回 370/371，回答库存数字与 SQL 一致。
- 第二轮只调用了 `queryGoodsSales`，参数是上一轮的 `goodsIds=[370,371]`，不是重新查库存；销量/销售额与 SQL 一致。
- 两轮共 4 条消息、2 条工具轨迹，均落库；测试库业务数据未被修改（仅使用验收演示数据）。
- 测试库中演示商品名称曾因本地脚本编码问题显示异常，修正测试数据后使用会话 5 完成最终验收；这不涉及业务库或代码。



## 7. 已知限制

- 财务只能查询销售概览，不能查库存或按商品销售明细；这是现有页面权限决定的。
- 仓储/采购不能查销售；员工没有数据工具。
- 工具只读，不执行新增、修改、删除、作废。
- 历史会话回看只恢复文本，不重新展示历史工具轨迹；实时问答会展示轨迹。
