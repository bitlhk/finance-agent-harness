# Java 模拟转账候选 V0.1

本场景扩展不修改 Builder Base、只读 service recipe、Python 金融桥或 Runtime 源码。
公共合同、预检/确认/提交/UNKNOWN 映射与文件 Checkpoint 复用 Harness；本目录模板只负责
单候选宿主的登录、动态实授、浏览器确认、受控输入与呈现。未验收前不作为已发布能力。

## 边界

- 仅绑定回环地址的固定 non-ai-banking simulator 实例，必须核对其 instanceId；独立数据库。
- 候选只安装账户余额和行内转账，收款人选择是宿主读取模拟器已保存收款人的辅助输入，
  不宣称新增了通用收款人 Capability。Host 校验当前可见 CNY 账户、有效行内收款人和
  服务端配置的试点金额上限；银行依然拥有正式预检及业务状态。
- 登录只证明模拟器身份；本地独立策略文件提供试点实授，每次操作与每次模型调用前重查。
  Profile、页面参数和模型文字都不授予权限。
- 单进程串行安装/卸载，复用 SDK 工具名字；进程级独占锁避免同一候选状态被两个 Host 使用。
- Cookie、CSRF、固定 Origin 保护页面确认；nonce 绑定会话、原任务、冻结摘要和有效期。
  认证 OTP 只是加强身份，不能替代交易确认。重复 nonce 只查原任务，不重放批准。
- Host 索引只是持久 Checkpoint 句柄目录，执行状态事实源仍是 Harness 文件存储与银行任务。
  重启保留 Cookie 对应的原银行会话，在恢复时重新验证有效身份和动态授权。
- 旧参数不能覆盖冻结任务；UNKNOWN 不另起交易。取消是拒绝宿主批准，不冒称银行任务取消。
- 模型不可用时对话明确失败，仍可独立检验可信表单路径，不用脚本回答冒充真实模型。

## 交付门禁

需要隔离完整模拟器账务/余额、重复确认、错误身份/撤权、过期批准、坏响应、UNKNOWN、
源返回前撤权及杀死候选进程后的查证。顺序 JVM 替换测试不能替代在途崩溃测试。
本轮不支持 Python TASK、真实银行或多租户平台。候选生成和独立部署记录分别提交。

## 固定提交验收（2026-09-14）

Harness `590c410`、Builder `da5d864c` 的干净候选已完成 29 项完整合成银行检查，
包括真实 Host 在银行记账后、响应送达前被终止，再启后查证原任务且不重复记账。
这不证明所有崩溃时刻的原子性，也不代表真实银行验收。

Builder 全量 779 通过、1 跳过；模板 Java 14 通过。首次新工作副本缺 esbuild 导致
四项现有浏览器构建测试失败，执行 npm ci 后全量通过。生成器需先安装项目锁定的
Node 依赖；Markdown 使用现有 browser/markdown.js 的 Marked + DOMPurify 策略，
打包进候选并纳入锁摘要，不加载外部脚本或模型生成的远端图片。

页面确认和配置的真实模型两轮查询已在开发机执行。页面发现表格纯文本展示后改用
共享渲染器，修改后的候选必须重新生成并复验。部署、真人独立接入、银行生产授权
均不由以上结果替代。私有测试记录在 outputs/simulated-transfer-host-v01，模型调用
仅保留请求数量、工具名称与工具结果消息计数，不记录密钥或隐藏推理。

## 可重跑入口

先按模板 README 启动独立合成数据库的模拟器，监听 18661，实例标识
`transfer-pilot-20260914`。这些验收脚本只用于该隔离数据集；不可指向共享演示库。
临时 Host 和代理使用 18662 至 18668；脚本回收自己创建的进程，输出目录必须不存在。

```bash
npm ci
PYTHONPATH=src:../base/src python -m finance_solution_builder.simulated_transfer \
  --harness /path/to/clean-harness --output /path/to/new-build --maven /path/to/mvn
python engineering/verify_simulated_transfer.py --candidate /path/to/new-build/candidate \
  --output /path/to/new-bank-report --bank http://127.0.0.1:18661 \
  --instance transfer-pilot-20260914 --java /path/to/java
python engineering/verify_transfer_revocation.py --candidate /path/to/new-build/candidate \
  --output /path/to/new-revocation-report --java /path/to/java
python engineering/verify_transfer_browser.py --candidate /path/to/new-build/candidate \
  --output /path/to/new-browser-report --java /path/to/java --chromium /path/to/chromium \
  --model-env /private/model-environment.json
```

前两项银行测试使用 `engineering/requirements-transfer-test.txt`。浏览器验收另需
Playwright 与 Chromium，本轮使用 Playwright 1.62.0。model-environment.json 仅含
服务端 `FH_MODEL_URL`、`FH_MODEL_NAME`、`FH_MODEL_KEY`，不能放入生成目录或提交 Git。
浏览器脚本确实调用配置模型；不满足工具调用或待确认断言会失败，不能自动换脚本模型。

干净候选 Builder `f810cff6` / Harness `590c410` 的 Lock SHA-256：
`78d150eda04981b55e1f8c2353248d19012b4db2e6054b18150bf903426f1093`。
该候选通过完整银行 29/29、浏览器和真实模型 9/9（6 次实际模型 HTTP），
受控返回前撤权另验证只有首次模型请求、无下一次模型请求、Host 403 并清理会话。
测试脚本后续入库不改变候选运行实现；它们不是候选运行时依赖。
