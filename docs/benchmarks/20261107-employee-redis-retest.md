# 员工分页与 Redis 请求指纹基准复测

日期：2026-11-07。范围：本机、独立 Docker 容器、合成数据；未连接正式库，未推送 GitHub。本文只记录本次实测，不把局部数据外推为生产承诺。

## 1. 代码与数据集

- 历史分页实现：Git `b0abf68` 的 `EmployeeService`（SHA-256 `ae396a17…b945`），记录在 `benchmarks/results/*/environment.json`。
- 当前实现：工作区 `EmployeeService` + `SysEmployeeMapper.xml`；Redis 指纹在 `PreventDuplicateSubmitAspect` 中复用静态 `ObjectMapper`。当前源码哈希与 `20261107-224623-employee-retest-optimized/environment.json` 一致。
- 数据：10,000 / 50,000 / 100,000 条合成员工，另加 1 个 superadmin；部门 900；每轮新建 MySQL 容器后导入 `db.sql`，插入完成后执行 `ANALYZE TABLE`。
- 环境：Windows 11，i7-14700HX（28 逻辑处理器），16 GB 主机内存；Docker Desktop / WSL2，MySQL 8.0.46，Redis 7.4.12，Java 17.0.17。详见 `benchmarks/results/hardware.json`。
- 每轮使用 `benchmarks/run.py` 新建自己的容器，结束后只停止、保留数据卷供复核；不读取、不清理主机已有数据库或卷。

## 2. 方法

- 每个规模、场景先做 2 次不记录预热，再记录 7 次；取中位数，奇偶轮交替执行历史/当前变体，减少顺序偏差。
- `legacy_pagination_only`：历史查询 + Java 内存合并、排序、分页，不执行历史 `page()` 里的资料修补；用于隔离分页本身。
- `legacy_full`：历史 `page()` 原样执行（含 `syncRegisteredEmployeeProfiles`），仅在 10k first 场景测量。
- `current`：当前 `EmployeeService.page()`，即 count + 分页两条 SQL。
- 每个样本都断言历史实现与当前实现的 `total` 和返回 ID 列表完全一致；不一致时基准进程直接失败。
- `EXPLAIN ANALYZE` 在计时样本之外单独执行，只用于解释计划，不计入延迟。

## 3. 员工分页：原始内存分页 vs 当前数据库分页

中位数，单位 ms；“分配”为当前线程分配字节数。

| 规模 | 场景 | 历史分页 ms | 当前 ms | 提升 | 历史分配 | 当前分配 |
|---:|---|---:|---:|---:|---:|---:|
| 10k | first | 153.05 | 17.29 | 8.9x | 161.94 MB | 0.16 MB |
| 10k | deep | 151.47 | 18.99 | 8.0x | 161.94 MB | 0.16 MB |
| 10k | filtered | 16.93 | 10.72 | 1.6x | 9.17 MB | 0.18 MB |
| 50k | first | 730.26 | 117.27 | 6.2x | 813.03 MB | 0.16 MB |
| 50k | deep | 719.89 | 127.52 | 5.6x | 813.03 MB | 0.16 MB |
| 50k | filtered | 50.25 | 69.92 | **0.72x（当前慢）** | 9.17 MB | 0.18 MB |
| 100k | first | 1429.85 | 240.89 | 5.9x | 1624.45 MB | 0.16 MB |
| 100k | deep | 1399.88 | 256.72 | 5.5x | 1624.45 MB | 0.16 MB |
| 100k | filtered | 156.74 | 140.24 | 1.1x | 89.87 MB | 0.18 MB |

- first/deep 的历史分页每次执行 6 条 SQL，返回 20,004 / 100,004 / 200,004 行；filtered 为 4 条 SQL，返回 1,114 / 1,114 / 11,114 行。当前固定 2 条 SQL，返回 21 行。
- 10k first 的 `legacy_full` 为 **8,348.9 ms、10,008 条 SQL、750.72 MB 分配**；当前为 17.29 ms、2 条 SQL、0.16 MB。差异主要来自历史 `page()` 在每次 GET 中调用 `syncRegisteredEmployeeProfiles()`，对约 1 万个员工逐个 `selectById`；当前已把该修补移出查询路径，改为显式 `repairLegacyProfiles()`。这不是单纯分页优化的功劳，本文分开列示。
- 50k filtered 是当前实现唯一慢于历史分页的组合：当前仍要扫描 `idx_dept_id(dept_id=900)` 的 5 万行再做 UNION/排序，而历史实现在这个规模只物化 1,114 行。绝对差约 19.7 ms；100k filtered 时历史要物化 11,114 行，当前反而略快（140.24 vs 156.74 ms）。没有为此继续改 SQL 或加索引，因为本轮确认的主要瓶颈是 COUNT，且继续改造会扩大 schema/查询复杂度。

## 4. COUNT SQL：本次实际修复的部分

历史 SQL 版本的 count 是 `SELECT COUNT(*) FROM (<UNION ALL ...>)`，会先物化普通员工 + 管理员并完成 join，再计数。当前拆成两个子查询：员工表按 `is_deleted` 覆盖索引计数，管理员表单独计数。以下为 `EXPLAIN ANALYZE` 的 actual time（ms）：

| 规模 | 场景 | 旧 count | 新 count | 提升 |
|---:|---|---:|---:|---:|
| 10k | first | 19.0 | 0.99 | 19x |
| 10k | deep | 17.5 | 0.93 | 19x |
| 10k | filtered | 7.44 | 2.91 | 2.6x |
| 50k | first | 144 | 4.87 | 30x |
| 50k | deep | 136 | 4.90 | 28x |
| 50k | filtered | 37.7 | 27.3 | 1.4x |
| 100k | first | 266 | 9.36 | 28x |
| 100k | deep | 290 | 11.5 | 25x |
| 100k | filtered | 77.5 | 54.5 | 1.4x |

同一批 EXPLAIN 里分页 SQL 的 actual time 基本不变（100k first 263 → 290 ms、100k deep 296 → 312 ms、100k filtered 81.5 → 79.9 ms，属于运行间波动）。因此：

- 无过滤/深分页的请求提升主要来自 count：100k 时约 250 ms 的计数成本降到约 10 ms。
- filtered 场景的 count 仍受 `dept_id` 索引扫描限制，只从 77.5 ms 降到 54.5 ms；分页自身仍是主要成本。
- 旧 count 的 `EXPLAIN` 在 `benchmarks/results/20261107-192016-employee-baseline/explain-*-directoryCount.json`；新 count 在 `20261107-224623-employee-retest-optimized/` 同名文件。

### 端到端对照（中间 SQL 版本 vs 当前）

旧基线运行（`20261107-192016-employee-baseline`）的 `current` 已是“SQL 分页 + UNION count”，其 EXPLAIN 证明 count 仍是物化版本；与本次复测的 `current` 对比，可近似隔离 count 改动。两次运行相隔约 3 小时，属于同机不同批次，只作方向性对照：

| 规模/场景 | 中间版本 ms | 当前 ms |
|---|---:|---:|
| 10k first | 28.01 | 17.29 |
| 50k first | 208.29 | 117.27 |
| 100k first | 425.72 | 240.89 |
| 100k deep | 446.37 | 256.72 |
| 100k filtered | 150.98 | 140.24 |

## 5. Redis 请求指纹：重复创建 ObjectMapper vs 复用

同一基准 `PerformanceBenchmark.redis()`，每次对同一组参数（Map + null + int[]）计算 100 次指纹；before 为中断前已记录的 `20261107-192423-redis-measured-before`（per-call 新建 ObjectMapper 的版本，未提交），after 为本次 `20261107-225000-redis-retest-after`。

| 指标 | before | after | 变化 |
|---|---:|---:|---:|
| 100 次指纹 | 73.71 ms | 1.46 ms | 50.5x |
| 单次指纹 | 737 µs | 14.6 µs | 50.5x |
| 100 次分配 | 21.9 MB | 430.5 KB | 50.9x |
| 单次分配 | 224,258 B | 4,408 B | 50.9x |

两次样本的指纹值完全相同：`7c0965c90c6c199264cc5e810f163bbbd3276f6410c6613ad456b6433f4fa0c1`。说明复用 `ObjectMapper` 没有改变参数排序、null/数组边界和 SHA-256 结果，只去掉了每次调用新建 mapper 的初始化和分配。

真实 AOP + Redis 竞争路径（每轮 10 次记录，取 batch 中位数）：

| 并发 | before ms | after ms | 提升 | 结果 |
|---:|---:|---:|---:|---|
| 1 | 4.80 | 1.30 | 3.7x | 1 次执行，0 拒绝 |
| 10 | 6.49 | 2.24 | 2.9x | 1 次执行，9 拒绝 |
| 50 | 19.65 | 5.03 | 3.9x | 1 次执行，49 拒绝 |
| 100 | 34.90 | 6.54 | 5.3x | 1 次执行，99 拒绝 |

这里测的是单实例、同参数竞争下的防重批次耗时；不是吞吐上限，也不是跨实例压测。before/after 是两次独立运行，绝对值受 JIT/GC 影响，但指纹哈希、参数和 Redis 版本一致。

## 6. 语义与回归

- 分页基准对 10k/50k/100k × first/deep/filtered 共 9 组，每组 7 个样本都断言：历史与当前的 `total` 相同、返回 ID 顺序完全相同。
- `EmployeeDirectoryIntegrationTest` 覆盖：员工/管理员合并、`empName`/`status`/`deptId` 过滤、负 ID 只读管理员、逻辑删除行排除、count 与 page 行数一致；新增“按系统管理部筛选返回负 ID 只读管理员”用例。
- `EmployeeAcceptanceTest` 覆盖真实 Sa-Token 拦截链：未登录 401、普通员工/非人事管理员 403、人事管理员/超管 200。
- `EmployeeReadOnlyTest` 断言 `page()` 只调用 `directoryCount`/`directoryPage`，不再触发历史同步写入。
- `RedisDuplicateIntegrationTest` 7 项覆盖真实 Redis 窗口、过期、用户/路径隔离、降级、断连恢复、容量上限与并发；本次全部通过。
- 最终完整回归：**44 项通过，0 失败，0 错误，0 跳过，exit code 0**。证据：`benchmarks/results/regression-20261107-225312.json`，原始 Surefire 在 `back/target/surefire-reports/`。

### 本轮修正的两个测试问题

1. `EmployeeDirectoryIntegrationTest` 原先插入“已删除的 superadmin”，但 `uk_only_one_superadmin` 不区分 `is_deleted`，第二次跑回归必然重复键失败。改为同样能验证逻辑删除过滤的 `admin`，测试意图不变。
2. 一次全量回归里 `SalesConcurrencyIntegrationTest` 撞了 `CodeGenerator` 的 3 位随机单号（5 个并发销售约 1% 概率）。这是既有测试/单号生成器的偶发问题，与本轮分页/指纹改动无关；未改生产单号生成器，重跑后通过。该风险仍存在，若后续要求稳定回归，应单独处理。

## 7. 已知边界

- 合成数据、单机 Docker、无生产流量；不能据此承诺生产环境的绝对延迟。
- `current.page()` 仍是 count + page 两条查询，不是同一快照；并发写入时 total 与当前页可能不一致（原有边界）。
- 分页 SQL 仍会把 UNION ALL 结果物化后排序；50k filtered 当前比历史内存分页慢约 19.7 ms，已在表中保留，不做“全面更快”的表述。
- 历史 `page()` 的 `syncRegisteredEmployeeProfiles()` 已移出 GET；如果库里存在没有员工档案的历史 `employee` 账号，需要显式调用 `repairLegacyProfiles()` 才会补档。这是行为变化，不是性能数字。
- Redis 只测当前实例的请求指纹与本地防重路径；故障切换窗口、跨实例幂等边界仍以 `docs/redis-acceptance.md` 为准。

## 8. 原始产物

- `benchmarks/results/20261107-224623-employee-retest-optimized/`：本次员工分页样本、EXPLAIN、环境与源码哈希。
- `benchmarks/results/20261107-225000-redis-retest-after/`：本次 Redis 指纹与并发样本。
- `benchmarks/results/20261107-192016-employee-baseline/`：旧 UNION count 计划与端到端基线。
- `benchmarks/results/20261107-192423-redis-measured-before/`：指纹复用前样本。
- `benchmarks/results/20261107-224529-employee-retest-optimized/`：夹具首次失败记录（`db.sql` 已自带 `system_management` 部门，硬插 901 冲突）；修正后的有效结果以 224623 为准。
- `benchmarks/results/regression-20261107-224339.json`（43 项，1 个 superadmin 夹具错误）、`regression-20261107-225137.json`（44 项，1 个销售单号随机碰撞）为中间失败记录；最终以 `regression-20261107-225312.json` 为准。
- `benchmarks/results/regression-20261107-225312.json`：最终 44 项回归汇总。
- `benchmarks/results/hardware.json`：硬件与 Docker 信息。
