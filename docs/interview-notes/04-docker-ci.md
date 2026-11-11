# Docker与CI：第四阶段本地验收

## 最终实现与证据（2026-11-06）

以下是最新结果，文末旧记录仅作为排查历史。本轮独立Compose四服务健康，镜像实际构建、HTTP业务、重启持久化已通过；最后一次JUnit运行42项全部通过，远程Actions没有运行。

### 代码与请求链

- `back/Dockerfile`：Maven/Java17编译，JRE非root运行；容器专用properties替换本地配置，避免把开发密码打进镜像。
- `front/Dockerfile`、`deploy/nginx.conf`：npm ci/Vite产物交给Nginx；`/api`转发backend:8080并保留原Host及端口。浏览器 → Nginx → Sa-Token/IP拦截 → Controller/Service → MySQL；防重切面访问Redis。
- `compose.yaml`：MySQL/Redis不发布主机端口，前端只发布127.0.0.1:18080；后端实际依赖检查成功后前端才启动。数据库与上传目录分别有命名卷。
- `ContainerHealthController`：`/api/health/ready`运行SELECT 1和Redis PING，成功200/UP、依赖失败503/DOWN，不输出内部异常。Redis连接及命令超时2秒避免故障检查长时间挂起。
- `002-disable-demo-accounts.sql`：只在空卷初始化阶段清空演示密码、停用演示账号。`ContainerAdminInitializer`仅匹配禁用且空密码的superadmin，用环境变量强密码生成BCrypt；重启不覆盖旧密码。两项H2测试验证这些边界，真实MySQL启动/重启也验证成功。
- `003-container-login.sql`：只为专用Nginx的172.30.94.10/32开放登录策略；没有关闭原IP校验。本机模式不等于公网真实客户端IP过滤。
- `.github/workflows/verify.yml`：临时MySQL/Redis、Java17、42项测试、Vue及前后端镜像构建，无发布步骤。编写了工作流不等于远程CI已通过。

### 真实验证

证据见`docs/verification/deployment/`。运行`python deploy/verify-local.py`，验证SPA、含Origin的HTTP、密码登录、未登录拦截、员工分页、商品初始10→销售2后8→重复销售拒绝→删除恢复10，两笔库存流水合计0。脚本只对固定本地项目运行，保留合成数据。

运行`docker compose -p ecs-stage4-20261106 restart`，等待健康后执行`python deploy/verify-local.py --verify-restart`，验证账号、数据库记录和uploads卷标记未丢失。只重启，不使用`down -v`。本轮还真实停止/恢复隔离Redis，验证健康接口503及恢复。

### 面试问题与回答依据

1. **为什么容器Running还不算部署成功？** 本次四服务健康后仍遇到登录403；必须进一步验证权限链、反向代理和实际业务写入。
2. **为什么用服务名连接数据库？** backend容器的localhost不是MySQL；Compose网络通过mysql/redis服务名解析。
3. **为什么出现同源请求403？** 原Nginx `$host`丢掉18080端口，后端将浏览器Origin判为跨域；改为`$http_host`并以真实Origin头复测。
4. **健康检查为什么还要配超时？** Redis默认超时让一次故障请求超过30秒；本次容器配置2秒超时后返回503，不能只看进程是否存在。
5. **管理员初始化为什么不每次重设密码？** UPDATE同时限定空密码和禁用状态，已有账号重启不会再次匹配，避免配置变化意外覆盖密码。
6. **卷和容器有什么区别？** 本次重启后数据库和上传文件仍在；卷保存业务数据，禁止通过删卷解决初始化错误。初始化SQL只在空卷运行，已有库需另行审查迁移。
7. **Redis重启后还能保证持久业务幂等吗？** 不能，Redis防重是短时间窗口，数据库事务/唯一约束仍承担数据一致性；本阶段不改变前三阶段机制。
8. **42项测试能证明什么？** 16库存+17员工+7Redis+2初始化的限定场景；容器HTTP和持久化另列证据，不重复计数，也不代表全部ERP或浏览器UI都被测试。

### 可亲自练习与已知限制

阅读`.env`取得本地管理员密码（不要发到聊天或提交），访问http://localhost:18080。按脚本顺序观察库存变化；只在本次Compose项目停止Redis，观察健康状态再恢复。修改健康检查前先保留现有配置和测试结果。

尚未执行远程CI或浏览器页面自动化。AI知识库/模型配置未完成；npm ci报告13项依赖漏洞（3 moderate、10 high），本轮未做漏洞可利用性分析或自动升级，不代表公网安全验收。公开授权及其他历史安全问题仍待处理。

## 历史开发记录（以下不代表最新状态）
当前代码证据：back/Dockerfile使用Maven构建Java17 JAR，然后复制到仅含JRE的非root运行镜像；front/Dockerfile使用npm ci及Vite构建，再由Nginx提供SPA与/api反向代理。浏览器请求同源/api，容器内部Nginx连接backend:8080。
Compose定义MySQL、Redis、后端、前端；数据库及上传目录用命名卷，容器删除不等于卷删除。MySQL/Redis健康检查用于依赖就绪，backend目前尚缺健康检查，不能把启动顺序当作业务就绪。
运行前复制.env.example到.env并填写强密码；当前仅允许本机演示，演示账户仍需改进。Docker不可用，尚未执行任何容器验证。GitHub Actions未编写，不宣称CI已通过。
面试问题：为什么多阶段构建？最终镜像不需要Maven和源码。为什么不用localhost连接MySQL？容器自己的localhost不是mysql服务。为什么不down -v排错？会删除持久数据。为什么Running不代表成功？还需真实HTTP业务验证。后续日志入口docker compose logs backend/mysql/frontend，命令尚未实跑。

最新：verify.yml已添加，临时MySQL/Redis只服务CI测试，无发布步骤；REDIS_TEST_SERVER可指定Linux redis-server。最新本机40项回归通过，Vue构建通过；远程CI、镜像构建及容器业务均未执行。Docker环境为Windows11+WSL2.7.10，尚待安装确认。
# 重新安装后的验证补记

本轮确认 Docker CLI/Compose 可执行，但 Engine 管道不存在，且 Desktop/backend 进程未运行。客户端安装、Compose 配置解析、引擎运行、容器健康和业务验收是不同层次的证据，不能互相替代。

现有 `.github/workflows/verify.yml` 已补充两个 Dockerfile 的镜像构建步骤，放在测试和 Vue 构建之后；没有推送镜像或公开源码。远程 Actions 与新增镜像步骤仍未实际运行，本轮没有重新执行 40 项回归。需先完成 Desktop 首次启动，再继续容器验收。

