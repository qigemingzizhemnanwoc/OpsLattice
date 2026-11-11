# 第四阶段部署验收

## 最终本地验收：通过（2026-11-06）

本节为最新结论；后续各节保留历史排查过程，其中未完成描述已被本节覆盖。验收限于本机隔离部署，不代表公网生产就绪或远程 CI 通过。

### 实际结果

| 项目 | 本轮证据 |
| --- | --- |
| Engine | Docker 29.9.2 可用，Linux 容器实际运行 |
| 镜像 | 官方基础镜像；后端 Maven/Java17 多阶段构建、前端 npm ci/Vite/Nginx 构建成功 |
| 四服务 | `ecs-stage4-20261106` 项目 backend/frontend/mysql/redis 均 running healthy |
| 数据库 | 全新命名卷执行 db.sql 成功，26 张表，手机号和库存流水唯一索引存在；不连接本机业务库 |
| 安全初始化 | 演示账号全部清空旧密码并停用，只有 superadmin 用本地随机强密码初始化为 BCrypt；重启密码不变 |
| 网络与接口 | 127.0.0.1:18080 经 Nginx 访问 SPA、健康接口、实际密码登录、用户信息和员工分页成功；未登录查询被拦截 |
| 库存/Redis | 合成商品初始10，销售2后库存8；即时重复销售被拒绝且仅1笔流水；删除后库存10、2笔流水合计0 |
| 故障健康检查 | 真实停止本项目 Redis，健康接口返回503/DOWN；恢复后重新健康 |
| 持久化 | 重启四容器后重新登录成功，商品、库存及流水仍在，uploads卷标记文件保留 |
| JUnit | 最后一次统一运行42项，失败0、错误0、跳过0：库存16+员工17+Redis7+初始化2；不累计之前重复执行 |
| 远程 CI | 工作流含Java17、MySQL/Redis集成测试、Vue构建、两个Docker镜像构建；未推送、未远程执行 |

证据目录 `docs/verification/deployment/` 保存JUnit逐类统计及HTTP/持久化结果。原始本机日志在 `back/target/stage4-final-regression.log`、`container-build.log`、`container-smoke.log`、`container-persistence.log`。JUnit使用已授权的 `warehouse_management_integration_test` 与本次自建16379 Redis实例；容器接口使用独立Compose MySQL/Redis。没有把HTTP检查额外计入42项JUnit。

### 本轮发现并修复

1. 缺少后端就绪检查：新增容器专用 `ContainerHealthController`，实际检查MySQL和Redis，只公开UP/DOWN；Compose等待健康后启动前端。
2. 演示账号固定密码：新增 `002-disable-demo-accounts.sql` 与 `ContainerAdminInitializer`，只初始化空密码且禁用的种子管理员，不重置已有账号。
3. Nginx登录被原回环IP策略拒绝403：专用ingress网络给Nginx固定172.30.94.10，`003-container-login.sql`只允许该精确地址；没有关闭IP校验或放开任意地址。
4. 真实Redis断连时健康检查超过30秒：容器连接/命令超时设为2秒，修复后真实故障返回503。
5. 带浏览器Origin头的同源请求被拒绝：Nginx使用`$http_host`保留端口，且覆盖客户端传入的X-Forwarded-For。修复后带Origin的登录、销售和分页通过。

### 复现与运维边界

- `.env`已被Git忽略，包含本地随机数据库密码和`ADMIN_INITIAL_PASSWORD`；账号为`superadmin`，密码仅在本机文件查看，不复制到报告或提交。
- 本轮命令：`docker compose -p ecs-stage4-20261106 up --build -d --wait`；验证 `python deploy/verify-local.py`；重启后 `python deploy/verify-local.py --verify-restart`。Windows若Docker未入PATH，给脚本设置`DOCKER_EXE`。脚本只操作固定本地项目并保留合成记录，不是生产测试脚本。
- `002`只可用于全新空卷初始化，绝不对既有业务库执行。当前容器初始化后才加入的`003`已仅在本次隔离库执行；未来空卷自动执行三份SQL。已有卷不会自动迁移，禁止用`down -v`排错。
- 入口只绑定本机回环。IP白名单在此模式识别Nginx，不是公网客户端级IP策略；ingress使用172.30.94.0/24，其他主机若网段冲突需同步调整Compose及003。不得直接复制到公网部署。
- 数据库/上传卷保留，测试服务当前仍运行。仅停止了测试自行创建的主机Redis进程；未删除Docker/WSL或备份数据。

### 尚未验证或不在范围内

构建中的npm ci报告13项依赖漏洞（3 moderate、10 high）。本轮没有执行自动升级或可利用性分析；列入后续安全处理，不能声称依赖安全审计已通过。

远程GitHub Actions未运行；浏览器页面点击与完整UI交互未自动化（已验证SPA和带Origin的真实接口）；AI知识库路径/模型密钥未配置，容器日志有知识库未加载提示；旧业务账号创建/重置密码策略、演示历史库存追溯、全量ERP功能及生产TLS/备份未扩展。源码公开授权与历史凭据审查仍待完成。本轮未修改前三阶段业务算法，也未公开推送。

### 可用于简历的表述

在既有Spring Boot/Vue系统上完成Docker Compose本地工程化：实现前后端多阶段构建、MySQL/Redis健康依赖、强密码管理员初始化和持久化卷；修复反向代理IP白名单与同源转发问题，通过真实容器登录、销售防重和库存流水核对及42项JUnit回归；编写GitHub Actions构建测试工作流（远程运行待验证）。

## 重新安装后的本轮检查

- 用户报告已重新安装；实际检查 CLI 29.9.2、Compose 5.6.1 可用，但 `docker version` 无 Server 信息，`docker info` 报 `dockerDesktopLinuxEngine` 管道不存在。
- 未发现 Docker Desktop/backend 进程；后端日志最后更新时间仍为 2026-11-05，不能把旧日志中的故障直接归因于本次安装。
- WSL 默认版本为 2，当前没有已注册的分发。这不能单独证明 WSL2 引擎可用，也不据此擅自安装发行版。下一步由用户打开 Docker Desktop 完成首次启动，再重新检查 Engine。
- 本轮补齐现有 GitHub Actions 中前后端 `docker build` 步骤，只构建、不推送。`git diff --check` 通过；镜像步骤和远程 Actions 尚未执行。
- 本轮尚未重新运行 40 项回归，未进行容器业务、数据库初始化或持久化验证。前三阶段历史通过数不计为本轮测试结果。
- 未修改 Docker/WSL 数据、备份目录、系统权限或正式数据库。

## 当前统一状态（2026-11-06）

以下为当前结论；后文保留执行历史，其中“未安装”“未编写”等早期描述不代表当前状态。

- 已完成：前三阶段限定范围改造及文档；现有后端日志 `back/target/deployment-regression.log` 记录 2026-11-05 统一运行 40 项，失败 0、错误 0、跳过 0。本次只核对日志，没有重新执行这 40 项。
- 已完成配置：前后端 Dockerfile、Nginx、Compose、容器环境配置、环境变量示例、GitHub Actions、README 和第四阶段学习文档；均保留现有文件，不重新生成。
- 已验证：Docker Desktop 当前用户安装记录、CLI/Compose 可用；本次重新执行 `docker compose config --quiet` 成功，使用当前进程临时占位变量，不连接数据库、不启动容器。
- 当前阻碍：本次实际 `docker info` 仍无法连接 `dockerDesktopLinuxEngine` 管道。旧 `docker-secrets-engine/engine.sock` 仍存在；未发现 Docker Desktop/backend 运行进程。此前备份重命名未成功，不能声称已修复。
- 待实现：后端健康检查与安全的初始管理员配置。
- 待验证：实际镜像构建、Compose 全服务启动、全新容器数据库初始化、登录及核心业务 HTTP、上传及数据库卷重启持久化。GitHub Actions 尚未远程运行；不能把本机测试当作 CI 或容器测试证据。
- 发布限制：原项目公开授权和凭据历史审查尚未完成，不能公开推送；现有 SQL 含演示账号，仅限本机隔离演示。

### 下一执行点

代理对旧运行时目录存在显式修改拒绝权限，不能更改 ACL 或绕过限制。用户已经同意备份重命名；需用户在自己启动的普通 PowerShell 执行以下操作（先确保 Docker Desktop 已退出）。只重命名，不删除目录；失败时停止，不改成强制删除或恢复出厂设置。

```powershell
$socketDirectory = Join-Path $env:LOCALAPPDATA 'docker-secrets-engine'
Rename-Item -LiteralPath $socketDirectory -NewName ('docker-secrets-engine.backup-' + (Get-Date -Format 'yyyyMMdd-HHmmss')) -ErrorAction Stop
```

随后从开始菜单启动 Docker Desktop。引擎恢复后继续现有配置的构建及接口验收；若提示管理员权限、启用系统组件或重启，先说明影响并等待确认。

未验收。已检查PATH、C:/Program Files/Docker与E:/DockerData，未找到docker.exe；未发现Docker服务。没有安装Docker Desktop、启用WSL或修改系统环境。
已添加后端Java17多阶段Dockerfile、前端Node22/Nginx多阶段Dockerfile、Compose、环境变量示例和无凭据容器配置。后端构建用独立配置替换本地properties，.dockerignore排除本地凭据/构建产物。镜像构建跳过测试仅用于打包，必须另行执行测试，不能据此判定验收。
Compose仅发布127.0.0.1:18080，MySQL/Redis无主机端口。MySQL及上传文件有命名卷。SQL仅由MySQL入口在空数据卷初始化，不重复执行；禁止用down -v处理初始化失败。现有db.sql含演示账号弱密码与示例数据，因此此配置只适用于本机隔离演示，不能直接公开部署。后端健康检查及强初始管理员配置仍待完善。
未执行：Docker构建、Compose配置解析、启动、接口、持久化验证、40项回归。尚未编写GitHub Actions或完成README。远程CI未运行。
下一步需要可用Docker引擎；如需安装Docker Desktop/启用WSL2，须用户确认系统级操作。前三阶段历史40项通过不能充当本阶段结果。

## 最新实际验证与环境检查
Windows CIM确认为Windows11家庭中文版25H2 build26200；WSL2.7.10已安装，无已注册Linux发行版；HypervisorPresent=True。CPU虚拟化字段在已运行Hypervisor下返回False，不能据此断定BIOS虚拟化未开启。LanmanServer运行且自动启动。常见系统/用户安装路径均未找到Docker可执行文件。
新增.github/workflows/verify.yml，配置Linux Java17/MySQL/Redis与40项测试及Vue构建；REDIS_TEST_SERVER使自建Redis测试支持Linux路径。工作流未在GitHub执行，尚未进行Actions语法/容器实跑验收。
本机Vue npm run build成功。首次40项回归因独立16379测试Redis已退出：39成功、1错误；重新启动本次持有的测试实例后统一40项通过、失败0、错误0、跳过0，结束后只停止本次创建的Redis。不能用本机结果替代容器验证。
README已增加来源、二次开发、Compose与CI状态说明。此前“未编写CI/README”属于阶段历史，以上为最新状态。
下一步等待当前用户Docker安装确认。尚缺引擎安装、镜像构建、Compose解析/启动、容器HTTP及数据持久化、后端健康检查、安全初始化与正式凭据历史审查。

## Docker安装实际结果
已按用户授权执行官方签名有效的Docker Desktop4.98.0当前用户安装，安装日志显示Installation succeeded。CLI29.9.2、Compose5.6.1可运行；compose config --quiet使用临时占位环境变量校验通过。
Engine启动失败：旧%LOCALAPPDATA%/docker-secrets-engine/engine.sock无法访问或重命名，该运行时重解析点创建于2026-07-13，目录内仅有此项。未恢复出厂设置、未删除数据、未启用额外系统组件、未重启。已请求允许将旧socket备份重命名后重试。
因此镜像构建、容器启动、业务HTTP和卷持久化验收仍未执行。Docker安装成功不代表第四项完成。

## Socket备份重试结果
按用户授权退出失败的Docker进程后，单socket重命名失败；确认目录仅含socket后尝试目录备份重命名，也因访问拒绝失败。未产生备份、未删除或改ACL。
只读ACL确认目录Owner为当前用户，当前用户有FullControl，但存在CodexSandboxOnline显式Deny Modify。代理不可绕过限制。需用户在自己启动的普通PowerShell中重命名单独目录为唯一备份名，再重新启动Docker。尚未证明需要管理员权限；不应直接提权或重置权限。当前Docker失败进程已退出。
