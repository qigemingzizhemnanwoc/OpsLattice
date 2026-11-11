# 第二项本地验收报告

日期：2026-11-05。结论：员工数据库/API限定范围本地验收通过；浏览器操作未执行，按用户允许的人工检查替代方案列出步骤。不是生产上线验收。

## 实际改造
- EmployeeSaveDTO：手机号、邮箱、长度、部门ID、状态Bean Validation，保留可空状态默认语义。
- EmployeeService.page：移除历史同步写入，保留显式带权限事务方法 repairLegacyProfiles，未自动执行修补。
- SysEmployeeMapper/XML：UNION ALL保留普通员工和负ID只读管理员，数据库过滤、全局排序、计数和分页，每页最多200。
- 003-active-user-phone.sql：有效账号手机号生成列唯一约束，逻辑删除历史保留、删除后手机号可复用。仅隔离库已执行，正式库需先审查重复数据。用户名维持已有全历史唯一规则；员工编号与账号关联维持已有唯一索引。
- GlobalExceptionHandler：数据库唯一冲突返回安全中文消息；EmployeeHttpStatusAdvice限定员工接口HTTP错误状态，避免同时改变其他旧API。

## 最终统一测试
33项通过，失败0、错误0、跳过0：员工相关17项，库存16项；不累计历史批次。证据见 verification/employee。Java17、Maven3.9.12、本机MySQL隔离库 warehouse_management_integration_test，专用账号；未写正式库。
EmployeeAcceptanceTest 三项：五类身份和HTTP校验矩阵；真实Service新增/修改故障回滚及成功更新；HTTP正常新增修改及重复手机号提示。
权限测试实际使用 Sa-Token 会话、SaInterceptor、RequireAdminAspect、AuthService、AuthzService、EmployeeService；普通员工与非人事管理员拒绝，人事和超管允许。测试直接创建Sa-Token会话，不覆盖密码登录接口；消息发送被mock，未mock认证授权服务。防重切面未加载，此处不验收Redis。
员工表insert/update故障由MyBatis测试拦截器抛异常，用户写入与档案变化真实MySQL回滚。正常创建、更新实跑通过。
其他数据库测试覆盖联合分页、排序筛选、真实手机号竞争、逻辑删除复用和冲突消息；并发唯一性是数据库级竞争，不是HTTP双请求端到端。

## HTTP与前端边界
实际MockMvc：200新增/更新、400参数/业务提前查重、401未登录、403越权。数据库唯一竞争映射409已单独验证，未单独通过HTTP注入数据库竞态。
Vue request.js现有401清理登录、403跳转、其他状态读取response.data.msg的分支与响应结构兼容；未启动浏览器，未声称页面联调通过。
人工检查（仅隔离环境）：人事登录→新增合法员工→原手机号修改；提交非法手机/邮箱确认中文提示；新增重复手机号确认提示；退出后访问列表确认登录跳转；销售管理员访问员工页面确认403。检查实际Network状态与提示，不操作正式数据。

## 已知限制
历史修补尚无预览/冲突审批UI，不应直接在正式库调用。用户名删除后不复用，提前查重可能未识别历史行，但数据库会拒绝并提供安全提示。分页count与数据为两次查询，其他事务同时写入时不是固定快照分页。未重新验证新空库全量初始化；正式环境迁移尚未执行。本次验收未包含性能基准；2026-11-07 补充见文末，不将局部数据外推为生产承诺。

## 简历描述
在既有Spring Boot/MyBatis-Plus系统中二次开发员工数据库与API：分离查询和历史维护，使用UNION ALL完成多来源数据库分页，增加Bean Validation及兼容逻辑删除的手机号唯一索引；通过真实MySQL验证并发唯一性、跨表事务回滚，并通过Sa-Token实际拦截链验证角色/部门权限。员工与库存指定回归集合33项通过。原项目基础功能不作为个人原创宣称。

## 2026-11-07 性能基准补充

已补做本机独立 MySQL 基准（10k/50k/100k 合成员工），结论见 [员工分页与 Redis 指纹基准复测](benchmarks/20261107-employee-redis-retest.md)。当前数据库分页在 first/deep 场景比历史内存分页快约 5.5–9 倍，50k/100k 的线程分配从约 0.8/1.6 GB 降到约 0.16 MB；50k filtered 当前慢约 19.7 ms，已在报告中保留，未做进一步优化。无过滤 100k 场景的 count SQL 由约 266–290 ms 降到约 9–11 ms。以上为本地合成数据，不改变本文原有验收范围，也不外推为生产承诺。
