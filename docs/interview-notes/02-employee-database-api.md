# 员工数据库与接口优化（开发中）

当前仅完成输入校验子项，第二项未验收。

EmployeeSaveDTO 增加手机号 1[3-9][0-9]{9}、邮箱格式及100字符上限、用户名/姓名/职位50字符上限、部门正数、状态0或1校验。新增和修改共用完整保存 DTO；status 仍可为空，保留新增默认启用、修改保留原状态的语义。Controller 已有 @Valid，校验在 Service 前发生。Bean Validation 单独验证不代表 HTTP 权限测试通过。

核心文件：back/src/main/java/org/example/back/dto/EmployeeSaveDTO.java；back/src/test/java/org/example/back/service/EmployeeValidationTest.java。

2026-11-05 执行 EmployeeValidationTest、EmployeeServiceTest、AuthServiceTest、UserManageServiceTest，共11项通过，失败/错误/跳过均0。都是本轮指定单元测试，不含真实 MySQL 员工事务、并发唯一性或认证拦截测试。

已核实 AuthService.register 在事务中创建用户及员工；UserManageService 写入时同步档案。EmployeeService.page 仍调用历史同步，仍为内存合并分页，尚未修改。管理员使用负ID只读展示，数据库分页不能遗漏该来源。历史孤立档案需要独立审查迁移，不能在 GET 中继续修复，也不能静默丢弃。

待开发：只读查询与历史迁移、联合数据源分页、真实唯一索引和逻辑删除政策、约束错误及HTTP状态、真实权限链路、MySQL事务/并发测试。不得将这些写成已完成成果。

面试问题：为什么 @Valid 不代替唯一索引？为什么 nullable status 的 Min/Max 不拒绝 null？为什么 Mockito 无法证明事务回滚？为什么 UNION 分页必须全局排序？为什么历史修复不应藏在 GET 中？
练习：运行 EmployeeValidationTest，将手机号改为短字符串，查看 violation 的 propertyPath；恢复后再运行。数据库分页与SQL说明待实际落地后补齐。

## 查询职责与分页实现进展
EmployeeService.page 已移除同步写入，改为 directoryCount/directoryPage 两次查询。SysEmployeeMapper.xml 以 UNION ALL 合并员工档案与管理员展示记录，保留管理员负ID、只读标志和系统管理部展示规则；筛选、全局排序及 LIMIT/OFFSET 在数据库执行。每页上限200，溢出参数拒绝。
历史修补保留为显式 repairLegacyProfiles 事务方法，执行原人事权限检查，不暴露自动触发的 GET/启动任务。当前未提供公开 HTTP 维护入口，也未在任何数据库执行修补；正式修补前仍需备份、预览与冲突审核。
最终本轮指定测试 EmployeeReadOnlyTest、EmployeeValidationTest、EmployeeServiceTest、AuthServiceTest、UserManageServiceTest 共12项通过，失败/错误/跳过0。只读测试验证 page 仅调用两个目录查询，无额外 Mapper 写入。数据库 SQL 尚未经过真实 MySQL 分页验收，不能宣称已验证筛选、排序和总数。
仍待唯一约束、真实事务/权限测试、HTTP状态及前端联调；第二项仍未验收。

## 真实 MySQL 分页验证及索引检查
新增 EmployeeDirectoryIntegrationTest，实际经过 MyBatis XML 和隔离 MySQL，验证员工与只读管理员合并、负ID、总数、分页、时间排序、状态/部门筛选及查询未创建额外档案。本轮统一执行29项测试全部通过，失败/错误/跳过0：员工相关13项（含1项真实数据库测试）、库存16项。未重复累计此前批次。
真实索引检查确认 sys_user.uk_username、sys_employee.uk_emp_code/uk_user_id 已存在；没有手机号唯一索引。隔离库有效非空手机号重复组为0。提供003-active-user-phone.sql草案：生成列只对未逻辑删除的非空规范化手机号取值，其余为NULL，唯一索引允许历史删除记录保留。脚本尚未执行，尚未进行并发唯一性验证。用户名继续保留历史不可复用的现有约束，但业务提前查重仍需与之对齐。
测试配置显式加载 mapper XML，库存16项回归通过。未验证权限过滤的真实认证链路，也未完成HTTP状态/前端联调或员工事务故障注入。第二项仍未达到验收条件。

## 手机号迁移与错误映射验证
003 已仅在 warehouse_management_integration_test 执行成功。真实双线程 JDBC 写入相同手机号，只保留一个有效账号；竞争失败产生 DuplicateKeyException，映射为409及固定中文提示。逻辑删除后同手机号可复用，旧行保留。该测试是数据库竞争测试，不是两个HTTP请求的全链路测试。
GlobalExceptionHandler 增加唯一冲突安全映射，不向客户端返回SQL或异常堆栈。EmployeeHttpStatusAdvice 仅对员工路径将 Result 错误码设置为HTTP状态，避免同时改变所有旧接口。前端 request.js 已有401清理登录、403跳转及其他错误读取data.msg的分支；尚未运行浏览器联调，不能声称已验证用户交互。
db.sql 同步新增 active_phone 生成列及唯一索引；最新初始化脚本尚未在新空库重新执行，现有隔离库已通过增量迁移。
最后一次统一回归30项通过，失败0、错误0、跳过0；员工14项、库存16项。剩余：员工Service新增/修改故障回滚、实际五类角色认证授权、HTTP传输层断言与前端交互。第二项仍未验收。

## 最终验收状态（覆盖前文阶段性“未完成”说明）
第二项限定范围本地验收通过，最终统一33项通过（员工17、库存16），失败/错误/跳过0。以 docs/employee-api-acceptance.md 和 docs/verification/employee 为准。
新增 EmployeeAcceptanceTest 使用真实EmployeeService事务、MyBatis、MySQL，员工insert/update时注入异常，断言用户及档案回滚，随后正常新增修改通过。权限使用真实Sa-Token会话、拦截器、权限切面和AuthService/AuthzService；消息服务被mock，权限服务未mock。登录会话直接由测试创建，不代表密码登录端到端。
HTTP验证员工200/400/401/403及重复手机号中文提示；唯一索引竞争的409映射单独验证。Vue保持原request.js分支，浏览器未执行，人工检查步骤见验收报告。
现场练习入口：EmployeeAcceptanceTest 的 createAndUpdateRollBackAfterUserWrite；查看故障开关前后两个表如何恢复；actualTokenAndAuthorizationMatrix 可改变deptCode验证权限；EmployeeDirectoryIntegrationTest可调整页大小及同一手机号并发竞争。务必仅运行隔离库。
