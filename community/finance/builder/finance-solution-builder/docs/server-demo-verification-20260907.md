# 单服务器演示部署验证记录

本文件保留部署前的受控验证记录摘要，不覆盖旧结果，也不是本次上游交付的新测试结果。
站点专属公网部署记录不随通用代码提交；目标机重新验收见 [验收清单](server-ag-ui-checklist.md)。

日期：2026-09-07。状态：**Server Deployment Mode Implemented / Isolated HTTPS Smoke Verified / Target Deployment Pending**。

## 固定版本

- Builder 源码基线：`bcee9b6008e70dc8b1520e397fbd83738eb140d2`。
- 本轮独立开发分支：`feat/studio-server-demo-v01`，未修改 18138 正在使用的工作副本。
- 最终运行实现：`f00678da437484f1e43e19ed8888c6d6ca32612e`，运行前干净。
  其中 `761a4fa` 为功能提交，`f00678d` 修正验证脚本的受控模型引用格式。
- Harness：`b55d0722cac2905388f57dc4a17b85d346a0e470`，独立干净工作副本，未修改公共实现。
- 没有部署到外部服务器、推送 Builder 远端或声明维护者已接纳。

## 本轮实测

| 验证项 | 结果与范围 |
| --- | --- |
| Builder Python | 177 通过，0 失败；较既有 158 项新增 19 项认证/存储/部署回归 |
| 实际生成过程的 Harness Maven | 13 模块成功；172 项，165 通过、7 项显式真实 Smoke 跳过 |
| Harness 合同 | 9 个 Schema、55 项探针通过，日志可核对 |
| Nginx/TLS + 生成宿主 | 29 项通过，余额与财富组合，真实 HTTP/TLS/Maven/Java；模型和银行为受控 HTTP Fixture |
| 模型绑定 | 当前 0.3 模型选择请求、显式绑定、冻结默认模型；非旧接口旁路 |
| 来源与运行版本 | 所有锁文件引用的文件摘要通过；98 个 JAR（含生成应用），包含真实 Harness/SDK 依赖 |
| 会话 | 两轮工具请求，第二轮明确携带第一轮标记及工具结果；不是仅判断消息条数 |
| 安全 | 无登录/错误密码、错误 Host/Origin、无 CSRF、伪造代理头、跨账号查看/下载均拒绝 |
| 银行撤权 | 工作台仍已登录，但银行授权失效后无新增模型/银行业务读取，清空会话 |
| 生命周期与存储 | 实际源码树外数据目录；服务正常退出停止子 Agent；重启后构建归属及 CSRF 一致，实例为 STOPPED |
| 登录撤销 | 移除密码文件中的用户后，旧 Cookie 的新请求为 401 |
| 浏览器 | Playwright Chromium，1440x960 与 390x844；无脚本错误或横向溢出，人工查看截图 |
| 清理 | 通过真实删除接口删除本轮 Candidate，保存最小删除回执；临时 TLS/代理密钥/登录密码销毁 |

最终构建：`agent-342489be24714288`。
锁摘要：`50d7006fdba8c7961b356f90b7d40649726277183831e1bf5ac496beba903b72`。
构建耗时 30,792 ms，其中合同 214 ms、Harness 构建 26,140 ms、Candidate 4,248 ms；
使用已存在的 Maven 依赖缓存，**不能当作干净新服务器耗时或提效比例**。
Candidate 测得 164,483,210 字节（约 157 MiB），不含共享 Maven 缓存；仅为该组合一次样本。
删除后不保留依赖 JAR 副本，保留本地报告、构建/验证日志、锁、截图及删除回执。

## 复跑入口与证据

工作目录：`community/finance/builder/finance-solution-builder`。

```bash
.venv/bin/python -m pytest tests -q --junitxml=build/server-deployment-final-02/builder-tests.xml
NODE_PATH=<playwright-node-modules> .venv/bin/python engineering/verify_server_deployment.py --harness <isolated-harness-checkout> --output build/server-deployment-new --browser
```

重新执行须选新的输出目录，且提前安装实际 Harness 的 conformance requirements。
Node 依赖路径按目标机调整，不能假设有 EA 仓库。

- [29 项报告](../build/server-deployment-final-02/report.json)
- [实际构建报告与分阶段日志索引](../build/server-deployment-final-02/build-evidence/build-report.json)
- [制品锁](../build/server-deployment-final-02/build-evidence/service-lock.json)
- [删除回执](../build/server-deployment-final-02/deletion-receipt.json)
- [桌面截图](../build/server-deployment-final-02/server-desktop.png)
- [手机宽度截图](../build/server-deployment-final-02/server-mobile.png)

上述 `build/` 为本地验证产物，不默认提交 Git；跨机评审需另附该小型证据目录，或按入口重新运行。

## 失败记录及边界

- 前两次隔离 Nginx 探针因测试配置继承系统 `/var/log/nginx/access.log` 而失败。
  仅修正独立测试实例的全局日志和临时目录；没有改动或重载系统 Nginx。
- 第三次真实构建因全新 Python 环境缺日期格式校验器失败。安装 Harness 明确依赖，补充部署说明，
  没有关闭格式门禁。`server-deployment-smoke-03` 保留失败构建记录。
- `server-deployment-smoke-04` 在旧单模型选择上通过 25 项；之后新增显式模型绑定验证。
  第一份增强探针因受控 modelRef 缺少 `model-` 前缀失败，修正样例后才形成最终 29 项结果。
- 本轮仅以组合能力做生成/运行 Smoke，三种组合的结构与既有功能通过 Python 回归；
  不宣称重新执行了全部历史真实模型或完整模拟器验收。
- 没有运行正式目标机 systemd 服务、真实公网 DNS/证书、真实模型、完整模拟器、真实客户授权或真人独立接入。
  systemd 为可安装模板，仍需目标机验收。AG-UI、SSO、生产多租户授权不在本轮范围。
- 18138 主进程及用户的 `agent-6f48f194685b4235` 保持原 PID、在线；未向其发送聊天或撤权操作。

部署配置、权限/密钥存放、停机备份、域名变化及匿名构建迁移限制见
[部署说明](server-demo-v01.md)。没有目标服务器和域名，不把部署准备完成表述为已经迁移上线。
