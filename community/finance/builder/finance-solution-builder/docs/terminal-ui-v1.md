# 多终端体验与界面定制 V1

本专项仅扩展已有只读 Studio 的有限服务配方，不修改 Finance Harness Core、Provider、
OpenJiuwen SDK 或 Base 契约，不开放收支候选、转账和其他未接入模块。沿用26模块目录。
标准 Candidate Backend 迁移仍按原交接计划处理；本专项不宣称已经完成该迁移。

## 开发和运行入口

在本目录安装原有依赖，Node 构建冻结前端：

```bash
python -m venv .venv
.venv/bin/pip install -e ../base -e '.[test]'
# Use the same Python environment that will execute the Harness contract stage.
.venv/bin/pip install -r <harness-repo>/engineering/requirements-conformance.txt
npm ci
npm run build:ui
PYTHONPATH=src:../base/src .venv/bin/python -m pytest tests -q
```

已有工作台启动命令不变。新建页面选择手机端、电脑端或 ATM 端，然后配置名称、
受管理的图标、两套主题、字体、余额卡片、快捷入口顺序、手机语音及电脑结果面板。
切换终端不改变能力、模型或银行连接；ATM 不允许语音。
旧构建保留原体验入口，新体验必须重新构建，不修改原锁或运行实例。

```bash
PYTHONPATH=src:../base/src .venv/bin/python engineering/build_terminal_candidates.py \
  --harness /absolute/path/to/finance-agent-harness \
  --output /absolute/path/to/terminal-candidates
PYTHONPATH=src:../base/src .venv/bin/python engineering/verify_terminal_service.py \
  /absolute/path/to/terminal-candidates/mobile/candidate /absolute/path/to/reports/mobile
```

后一命令额外需要 Playwright Python 和 Chromium，使用明确的受控 HTTP 模型与合成银行数据。
可在同一环境执行 `.venv/bin/pip install playwright` 和 `.venv/bin/python -m playwright install chromium`。
构建分别生成 mobile、desktop、atm、teal 和 wealth-only。测试失败即非零退出，不能用截图代替断言。
原 `engineering/verify_local_service.py` 与三组合回归入口继续使用。

构建还需要 Java 21、Maven 以及固定的 Harness 工作副本。仅安装 Builder Python 包不等于安装了
Harness 的 JSON Schema 格式检查依赖；缺 `rfc3339-validator` 时应修正上述环境，不能跳过合同阶段。

## 独立制品

完整导出 candidate 后，在 Builder/Harness 仓库之外运行：

```bash
# Java 21、Python 3.10+；按原运行说明在服务端配置 FH_BANK_*、FH_ACCESS_POLICY、
# FH_MODEL_URL/FH_MODEL_KEY/FH_MODEL_NAME，FH_MODEL_MODE=LIVE。
python run_ui.py
```

它启动随包 Java Host 与回环 UI 网关，终端输出本机体验 URL，Ctrl+C 只停止本次进程。
不存在银行/模型连接时明确失败，不降级成脚本答案。语音额外需要 ffmpeg、
`websockets==15.0.1` 及 `FH_ASR_APP_ID/FH_ASR_API_KEY/FH_ASR_API_SECRET`。
这三个变量只在服务端，不进入 Lock、浏览器或导出包。默认不保存录音。
`FH_UI_PORT` 可固定空闲回环端口；端口被占用则失败，不连接其他实例。
独立网关只暴露体验操作，不包含构建、删除、撤权测试等管理 API。

复制进 `portable/` 的 Experience、语音及网关模块来自本仓同一实现，不另维护一套金融规则。
金融权限、源身份和映射继续由生成 Host 与同一组 Harness JAR 实施。

## 合同与代码位置

| 内容 | 实现 |
|---|---|
| 创建选择 0.6、界面配置 finance-interface.v0.1 | `workbench_contracts.py` / `terminal_ui.py` |
| 页面与布局预览 | `browser/workbench.js` / `presentation.js` |
| 三端用户入口 | `browser/experience.js` / `examples/agent-experience/style.css` |
| 实例票据、会话代际、最终授权 | `experience.py`；管理权限仍由原 Workbench 检查 |
| 独立运行网关 | `portable_ui.py` / 生成的 `run_ui.py` |
| 终端时限、取消、快照 | 生成 Host 中 `TerminalSession` / `NativeConversation` |
| 来源合同到展示白名单 | `DisplayProjection`，不从模型文字解析金额 |
| 讯飞接线 | `speech.py`，实时授权检查、受限音频、动态修正合并 |

0.2/0.3/0.4/0.5 输入仍保留。旧 RESPONSIVE_WEB 只在旧记录启动预检/编辑中映射到手机视图，
原记录与制品不重写；新请求必须显式选择三种终端之一。新字段仅进入0.6。
新增 `ui-config.json`、`ui/*`、`portable/*` 都列入 service-lock 文件摘要。
`interface.configuration` 与资源摘要属于构建锁，不冒称现有 Assembler 安装锁新增了 UI 语义。

## 展示和事件

`finance-display.v0.1` 包含 runId、messageId、cardId、卡片和观察时间。
余额金额保持十进制字符串；没有卡号时不把内部 accountId 伪造成尾号。
数值型金额即使被 JSON 树规范化为 `5E+4`，也按精确十进制转换为 `50000`，不经 Double 格式化。
展示转换拒绝超过128位精度或正负128范围以外 scale 的数值，防止不可信指数触发无限展开；
这只是展示层输入上限，不是银行金额限额。缺字段、非法文本和无币种不从模型回答或其他余额字段补齐。
财富与余额不相加，风险记录不转为适当性结论。来源、估值时间缺失显式呈现。
只允许已安装工具的完成结果进入卡片；未知版本不铺开原始 JSON，也不提供未知操作。
AG-UI 携带真实运行阶段和一次性最终文本/卡片，**不是模型 token streaming**。

手机结果在对话内；电脑结果与对应历史消息关联，右侧面板可折叠；ATM 提供任务、帮助、
返回与结束。管理页保留停止/删除，体验票据不能管理实例。
当前管理入口使用统一登录；体验票据是限权 bearer，不是新建的多租户 IAM。

ATM：120秒无操作、15秒提醒、600秒绝对上限，由 Host 执行；轮询和模型输出不续期。
结束立即失效，清理 SDK 上下文等待在途运行退出。刷新 ATM 不恢复旧会话。
手机/电脑刷新先验证当前授权再读快照，不重发未知状态请求。
取消本轮不等于停止实例；已发出的只读 GET 可能无法撤回，但取消后阻断后续模型及结果展示。

## 替换余额卡片练习

1. 保持同一 Provider、能力及 `finance-display.v0.1` 数据。
2. 在界面定制选择“示例银行主题”和“账户明细表”，创建新构建。
3. 对比 `browser/presentation.js` 的 `balanceSummary` 与 `balanceLedger`：两者只消费
   rows/source/scope，不调用银行、不解析模型回答、不加总币种。
4. 新组件在此受管理目录实现，加入 `InterfaceConfig.balanceCard` 白名单，再构建 UI。
5. 补正常、零值、缺数、大额精度、恶意文本、来源和未安装能力测试；重新生成并核对 Lock。

客户可改名称、预置标识/主题/布局或新增兼容渲染组件，不需要改 Harness。
不支持任意 HTML/JS/远端组件 URL、标识文件上传或 PDF 自动转设计；这些不是本次完成项。

## 验收边界

受控时序、浏览器视口、真实模型、真实 ASR、真人手机和独立开发者使用分别记录。
官方音频文件回放可证明实际 ASR；浏览器 fake-device 可证明录音管线，但二者都不等于真人麦克风体验。
安装配置与 API 连通不自动代表金融质量、硬件 ATM 或跨银行授权已验收。
分项结果见 `terminal-ui-verification-20260910.md`；实施记录见 `terminal-ui-work-record.md`。
