# Finance Studio 单服务器演示部署 V0.1

当前 A4 的 Runtime/组合范围见 [当前指南](public/python-runtime-v01.md)；下文版本为原部署基线。

## 版本目录切换的检查

使用 `ProtectSystem=strict` 时，仅替换 `builder` / `harness` 符号链接还不够。
新 release 的真实路径必须列入该服务的 `ReadWritePaths`，否则首页可打开而 Maven 构建失败。
生成和验证需要写入该版本的 Maven target；只开放新 release，不对整个文件系统放开保护。

新 release 归实际服务账号所有。若发布的是链接 Git worktree，应核对 Git 公共元数据归属；
确需设置 `safe.directory` 时，只信任已核验的两个源码真实路径，不使用 `*`。
`DynamicUser` / `StateDirectory` 下，应在实际服务的 HOME 和命名空间验证 Git、Maven、Python，
普通 sudo 下的 HOME 与服务环境可能不同。不得在日志打印模型或身份凭据。

在切换前的隔离服务中完成一次真实生成，而不仅运行 pytest 或健康检查。切换后再次从页面
创建试点制品、启动并核对 Lock；旧 Agent 不自动升级。保留配置、历史及旧 release，
回滚时停止新实例、恢复两条版本链接并撤销仅针对新 release 的 drop-in，然后重启核验。
首次下载依赖和缓存构建耗时分开记录，不将暖缓存结果视为首次接入耗时。

## 范围

一台受信 Linux 服务器、一个 Builder 进程、固定合成银行与服务端模型连接。沿用
OpenJiuwen Java 0.1.13、Harness 制品和三种只读组合；不改变 SDK、金融权限规则或生成宿主协议。
本说明原为单机部署基线。当前 AG-UI 使用 SSE 生命周期及授权后的整段正文，不是逐 Token 流式输出；
见 [协议说明](ag-ui-v01.md) 和 [部署验收清单](server-ag-ui-checklist.md)。
具体站点的部署拓扑、账号和历史运行记录由部署 Owner 私下交接，不随通用代码提交。
SSO、互联网自助注册和多租户银行身份管理不在本轮范围内。

```text
浏览器 -> HTTPS / Nginx Basic 登录 -> 127.0.0.1:18148 Builder
                                         -> 本次构建的 Agent（回环动态端口）
                                         -> 127.0.0.1:18149 合成银行
                                         -> 配置的模型服务
```

每位操作者使用独立登录名，只能查看、下载、启动或删除自己的构建。权限在后端校验，
不是仅隐藏列表。不要共享账户；同账号多标签仍属于同一操作者，沿用实例代际保护。
Nginx 每次请求验证账号，再覆盖用户名及代理凭证头；Builder 仅信任同机代理的有效凭证，
精确核对公开 Host/Origin，使用 Secure/HttpOnly/SameSite Cookie 和 CSRF。
旧匿名 Cookie 无法充当服务器身份，浏览器自填代理头不能授予权限。

**工作台登录不是银行授权。** 首版所有受邀操作者使用同一组合成测试客户与服务端配置，
不代表每个登录名有独立银行身份。既有银行 Session/introspection/Grant 每轮继续校验；
共享测试客户撤权影响所有使用该测试授权的实例。真实客户、多机构或不互信使用者不得直接套用此演示配置。
账户移除只拒绝新的工作台请求；不撤销已发出的模型/银行请求，也不自动删除其构建或停止后台任务。
需要紧急终止时，由运维停止服务及其 cgroup，再按银行授权流程撤权。

## 安装前提

- 专用 Linux 系统账号 `finance-studio`，Python 3.11.4 至 3.13、JDK 21、Maven、Git、Nginx、OpenSSL、apache2-utils。
- `/opt/finance-studio/builder`：本版本 Builder **完整 Git 仓**，含 Base；固定双方已验收提交。
- `/opt/finance-studio/harness`：固定版本 Harness Git 仓，专用于这台 Builder 构建，不能与其他工作台并发 Maven。
- `/opt/finance-studio/non-ai-banking.jar`：固定模拟器制品，必须含已验证身份 introspection；记录 SHA256。
- DNS 域名与有效 HTTPS 证书。只开放 443（需要 HTTP 跳转时开放 80），不开放 18148/18149 或动态 Agent 端口。
- Builder 需要读取源码、向数据目录写产物；Harness 构建需要写本仓 target 等目录及服务用户 Maven 缓存。
  不使用 root 跑 Builder，不让页面上传源码或执行任意命令。

在 Builder 的 `community/finance/builder/finance-solution-builder` 目录准备独立环境：

```bash
python3 -m venv .venv
.venv/bin/pip install -e ../base -e . pytest
.venv/bin/pip install -r /opt/finance-studio/harness/engineering/requirements-conformance.txt
.venv/bin/python -m pytest tests -q
.venv/bin/python engineering/prepare_server_deployment.py --domain studio.example.internal --output /tmp/finance-studio-deployment
```

用实际域名替换示例。生成器只写新的私有目录，不自动安装、不重载服务，也不会覆盖/轮换旧凭证。
生成目录包含代理密钥，不提交 Git、不作为公开构建产物下载。

自动化安装可调用 `bash deploy/server/install-python-dependencies.sh .venv/bin/python "$PWD" <固定Harness目录>`，
它在同一解释器安装 Builder 和 Harness 已声明的校验依赖，并检查日期格式验证器确实有效。
不能只复制旧环境的 `pip freeze` 或安装 Builder 后就认定完整构建依赖就绪。2026-09-10 的部署验收曾因
漏装 `rfc3339-validator` 在 `harness_contracts` 失败；补齐固定版本 Harness 的依赖清单后重跑通过，
并未删除日期校验断言或跳过构建阶段。

## 服务端配置

由运维检查并安装生成的四个文件：

| 文件 | 目标 / 权限 |
| --- | --- |
| nginx.conf | `/etc/nginx/conf.d/finance-studio.conf`，root 所有 |
| finance-studio.service | `/etc/systemd/system/finance-studio.service`，root 所有 |
| proxy-token | `/etc/finance-studio/proxy-token`，finance-studio 所有，0600 |
| proxy-header.conf | `/etc/finance-studio/proxy-header.conf`，root 所有，0600，Nginx master 读取 |

`/etc/finance-studio` 不允许普通用户写；证书放在其 `tls/fullchain.pem` 和 `tls/privkey.pem`。
确保 Nginx worker 可读用户密码文件，但不可读模型配置；通过组权限和父目录遍历权限配置，不把文件全改为 644。

添加账号使用交互命令，不在命令行写密码：

```bash
sudo htpasswd -B -c /etc/finance-studio/users.htpasswd alice
sudo htpasswd -B /etc/finance-studio/users.htpasswd bob
```

`-c` 仅创建首个文件时使用。用户名限制为 1 至 64 位英数字及 `_.@-`，首位必须英数字。
密码文件建议 root:www-data、0640，具体 worker 组以实际 Nginx 为准。
移除账号用 `htpasswd -D`，新请求即需重新认证。浏览器可能缓存 Basic 登录，首版没有伪装的“退出登录”按钮；
切换账号用独立浏览器配置或关闭认证会话。大量用户、SSO、细粒度管理员权限应另行设计，不宣称本版已有。
登录名是稳定归属标识，不得把已离职/移除操作者的旧登录名分配给另一个人；改名和归属迁移须人工核实。

准备两个 0600 的服务器环境文件（不写入制品、不下发浏览器）：

- `runtime.env`：`FH_SIMULATOR_SHA256=<已核对的 JAR SHA256>`。
- `model.env`：沿用 `docs/model-selection-ui-v02.md` 的显式模型绑定格式。设置
  `FH_MODEL_REGISTRY=/etc/finance-studio/model-bindings.json`，必要时设置 `FH_MODEL_CATALOG`；
  配置绑定引用的 `FH_CONN_..._URL`、`FH_CONN_..._KEY`。registry 只含模型元数据及环境变量名。
  使用已有 FH_MODEL_URL/NAME/KEY 的部署可显式采用 `--bind-configured-model <匹配的 catalog ref>`，
  不自动把所有模型菜单标记为已绑定。未绑定仍拒绝启动。

首次**由操作者**建立 `/var/lib/finance-studio/simulator/access-policy.json`，0600、finance-studio 所有。
测试环境授权示例，`expiresAt` 必须换成实际批准的短期有效时间：

```json
{
  "schemaVersion": "local-simulator-access.v0.1",
  "bankUrl": "http://127.0.0.1:18149",
  "subjects": {
    "customer-demo-001": {
      "enabled": true,
      "expiresAt": "<明确批准的 UTC 到期时间>",
      "scopes": ["wealth:facts:read", "wealth:customer:read", "account:read"]
    }
  }
}
```

禁止把 `--provision-demo-policy` 放进自动重启命令。已有文件不自动重置、延期或恢复授权。
模板的合成银行使用内存 H2，每次服务重启重置测试数据；不适合作为业务数据持久化方案。

服务用户须拥有 `/var/lib/finance-studio`（0700）、可写 Harness 构建目录；
Builder 源码可由运维只读管理，`.venv` 要安装完成。把模拟器及所有依赖版本记入运维清单。

```bash
sudo nginx -t
sudo systemctl daemon-reload
sudo systemctl start finance-studio
sudo systemctl reload nginx
```

先核对认证、服务状态和只读验收后，再执行 `systemctl enable finance-studio`。
不要把服务 `--port` 直接映射公网；它没有“关闭认证对外启动”的服务器选项。
模板是专用虚拟主机配置，已有反代时先由运维合并检查，不能覆盖其他站点。

## 数据、退出与升级

- `/var/lib/finance-studio/builds`：构建记录、输入、各 Agent 制品/日志、最小删除回执，以及
  `workbench-records/.local-session-key`。该密钥+公开 origin+登录名决定稳定归属，须安全备份。
- `/var/lib/finance-studio/simulator`：测试策略、绑定元数据、合成银行日志；共享模型凭据仍在 `/etc`。
- `/var/lib/finance-studio/.m2`：共享依赖缓存，不能随单个 Agent 删除。
- 全局构建串行、排队最多 5 个；服务器模式默认同时运行最多 2 个 Agent（各 Xmx512m）。
  它是资源上限而非租户配额；需观察总内存、磁盘与模型消耗。

停止服务会回收其构建/Agent 子进程。正常退出记录 STOPPED，重启后可启动已有制品，**不恢复聊天记忆**。
异常退出保留 UNMANAGED_AFTER_RESTART，不根据磁盘 PID 杀进程或自动收养旧实例。
systemd 的 control-group 负责清理所属进程；人工核实后再处理异常记录，不能盲目改为 RUNNING。
数据目录单写者锁阻止两个 Builder 同时管理同一目录，不宣称分布式或多机恢复。

磁盘维护：在页面停止 Agent，下载确需保留的锁定制品，再“删除”。删除会真正清除该构建、日志及 ZIP，
只留下 buildId、锁摘要、时间回执；不会清除其他构建或 Maven 缓存。暂不自动按天删除，避免误删演示版本。
服务器模式最多保留 20 条构建记录，磁盘可用空间低于 5 GiB 时拒绝新构建；需要用户归档并删除后继续。
这是准入保护，不是文件系统硬配额，也不会自动删除历史记录。监控数据目录和 Maven/Harness 工作盘。

升级前停止服务并做**冷备份**，同时保留 Builder/Harness SHA、模拟器 SHA、部署配置、数据目录和私有密钥。
模型密钥/访问策略与公开制品分开加密保存。新机器恢复相同路径/公开 origin/登录名/会话密钥后再启动；
改变域名会改变用户归属，必须单独做人工核实迁移，不把旧构建自动分配给当前登录人。
本地匿名构建不自动归到服务器用户；建议导出必要制品或在新账号下重新构建。

## 可重跑验证

```bash
.venv/bin/python -m pytest tests -q
NODE_PATH=<包含@playwright/test的node_modules> .venv/bin/python engineering/verify_server_deployment.py --harness <专用Harness工作副本> --output build/server-smoke-01 --browser
```

第二条使用真实 Nginx、临时 TLS、真实 Maven 生成 Agent、受控 HTTP 模型/银行；不调用付费模型或真实银行。
测试环境也须在同一 Python 环境安装所用 Harness 的 `engineering/requirements-conformance.txt`；
缺日期格式校验器时真实构建会失败，不从已有机器的隐式依赖推断新机器可用。
它验证登录拒绝、代理头覆盖、CSRF、账号隔离、外部目录构建、锁摘要、两轮上下文、独立银行撤权、
正常停止/重启、账号移除、浏览器布局及实际删除。临时密钥与 TLS 文件销毁，生成 Agent 测后删除，留下报告/截图/回执。
缺少 Nginx/OpenSSL 时明确失败，不跳过后标 PASS。

正式服务器的 DNS/证书、真实模型连通、完整模拟器授权及真人操作必须分别验收。
目标部署实际结果见上海记录；隔离测试不能替代银行 IAM、真人独立接入或模型业务质量验收。

## 配置依据

采用 [Nginx Basic Authentication](https://nginx.org/en/docs/http/ngx_http_auth_basic_module.html)
处理账号密码；使用 [proxy_set_header](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_set_header)
覆盖代理身份头，关闭上游自动重试和响应缓存。外层登录不替代 Harness 的金融执行授权。
