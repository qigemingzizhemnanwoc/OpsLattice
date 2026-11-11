# 库存并发与事务优化验收（2026-11-05）

结论：本次限定的四类单据库存变更范围通过本地隔离 MySQL 验收；不是全系统上线验收。未操作正式数据库，未补录历史流水，未公开推送。

## 实际实现
- 保留原有 stock >= quantity 的条件扣减，数据库原子更新防止超卖。
- 四类删除先条件逻辑删除，影响一行才变更库存；事务失败撤销状态变化。
- 两类退货锁定来源单据，并以当前读统计有效退货额度，避免并发超退和旧快照。
- 采购、销售来源撤销与退货使用同一来源行锁；有效退货存在时拒绝删除/作废，先撤销退货。
- 四类单据新增和删除/作废均在原事务内写流水。新增事件为 purchase/sales/purchase_return/sales_return；撤销事件统一加 _reverse。红冲记录本身不重复改变库存，不另加一次库存流水。
- 流水读取变更后商品库存使用当前读，记录 before/after/change。biz_type、biz_id 唯一索引限制同一单据同一事件重复落库。脚本 002 已在隔离库执行，正式库仅提供脚本，不自动执行；历史重复记录需人工审查，不能删除后强行建索引。
- 实测发现并修复退货逻辑删除 SQL 被错误附加 FOR UPDATE 的问题。

## 最终证据
最后一次指定测试集合：16 项，失败 0，错误 0，跳过 0，BUILD SUCCESS。原始 Surefire 文本见 verification/stock。此前中间失败不隐藏：新增真实删除测试曾暴露 SQL 语法错误，修复后重新执行最终集合通过。
- SalesConcurrencyIntegrationTest：8 项真实数据库测试（其中一个用例循环三个删除场景，只计一项）。
- PurchaseStockFlowIntegrationTest：2 项真实数据库测试。
- 四个 DeleteTest：4 项单元测试。
- PurchaseServiceTest：2 项单元测试。

验证包括 8 线程争抢 5 件库存；两类退货各 4 线程争抢 5 件额度；四类删除竞争；销售来源作废与退货竞争；重复作废；采购和销售流水故障回滚；销售库存恢复失败回滚；四类新增/作废完整生命周期对账。
数据库竞争测试使用入口屏障，不声称穷举全部事务交错。采购来源竞争沿用与销售相同的保护方式，但未单独运行该交错场景。

## 对账规则与限制
明确记录起始库存 B，只核对基准建立之后的流水：当前库存 = B + SUM(change_quantity)。生命周期用例 B=10，八条流水净变化为零，最终库存 10，并逐条核对前后库存连续。历史无流水不能推断为零库存；无自动历史补录。
本次不覆盖管理员直接改商品库存、导入、人工 SQL 等绕开四类业务 Service 的路径；正式使用应限制这些入口或另行审计。新建业务请求重复提交仍可能创建不同单号，短时防重不是持久幂等；唯一流水事件键不能代替客户端请求幂等键。死锁/锁等待异常会失败回滚，不承诺自动重试。未做性能基准、HTTP 权限全链路或生产部署验收。

## 文件与技术
核心文件：PurchaseService、SalesService、PurchaseReturnService、SalesReturnService；SalesConcurrencyIntegrationTest、PurchaseStockFlowIntegrationTest、四个删除回归测试；db.sql；docs/migrations/002-stock-flow-event-unique.sql。
技术：Java 17、ExecutorService/CyclicBarrier、JUnit 5、Spring 事务代理与异常回滚、MyBatis-Plus 条件更新/逻辑删除、MySQL InnoDB 行锁/当前读/唯一索引。

## 可用于简历的表述
在既有 Spring Boot/MyBatis-Plus 系统上二次开发库存一致性能力：保留条件扣减防超卖，通过来源行锁控制并发退货额度，以条件状态转换防止重复撤销；补齐四类单据库存流水及事件唯一约束，并在独立 MySQL 数据库通过 16 项单元与集成测试，验证并发、回滚和基准库存对账。不宣称原项目全部由本人原创，不宣称性能提升。
