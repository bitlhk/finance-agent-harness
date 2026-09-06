# 主线六项边界修复验证

日期：2026-09-06。修复基线：`49a87e0111ba0c5656832dfa5cf996d433794440`。
开始时本地 main 与 origin/main 一致，工作区干净；仅建立一个短期修复分支。
不重新设计四合同、不增加 Runtime、不连接真实银行或 EA、不部署服务。

## 状态

**Candidate V0.1 / Six Boundary Regressions Fixed / Controlled Validation Passed / Live Acceptance Pending**

这是代码与隔离回归结论，不是生产安全认证、真实授权或商业收益结论。

## 复现与修复

先加入直接回归，再修改实现。第一次选择性 Maven 回归观察到身份、精度、补参、
共享消费者与 Runtime 版本五项预期失败；上游模块失败导致 Kit 未执行，随后单独运行
`ControlledEndpointsTest`，POST 银行查询及修改类 MCP 工具两项均失败，覆盖第六项。
日志留在本机 `/tmp/finance-boundaries-red-20260906.log` 和
`/tmp/finance-endpoints-red-20260906.log`。下表回归已纳入仓内测试及现有 CI。

| 问题 | 修复与直接回归 | 证据边界 |
| --- | --- | --- |
| P1 保险身份错配 | 财富/保险共用可信 adoption-owner 校验；缺失、过期、错 principal/runtime/agent/adoption 及撤销拒绝；合法外层 Grant 不能覆盖内部错配；检查 Token 和 HTTP 调用为零 | 只证明本地边界，EA 仍负责真实客户关系和字段授权 |
| P2 Checkpoint 精度 | 文件专用精确解码；`90071992547409.91`、嵌套参数、整数、小数尾零和终态数据保持值及指纹；重建 Store/Controller 后可按原参数续办及宿主确认，重复查询只一次模拟写入 | 未修改全球 JSON 解码；不能恢复历史已舍入数值；未执行实际交易或多进程验收 |
| P2 空值补参 | Adapter 明示本地收集时，允许替换列明字段的缺失/null/空白字符串；非空值、额外字段、冻结及不确定写入仍拒绝 | `NEED_USER` 或没有 taskRef 都不是独立放行依据 |
| P2 共享消费者遗漏 missingSources | 直接消费合法公共 `coverage-gap.json`，无需 Mapper；`deposits` 形成可见覆盖缺口且返回 PARTIAL | 同一 Fixture 经真实 Schema 验证器和 Java 解码；格式有效不代表事实齐全 |
| P2 Runtime Lock 信任虚假版本 | 从实际加载类的 CodeSource 读取 SDK Maven 元数据与 Integration 构建元数据；完整坐标不符或元数据缺失则安装前拒绝；Lock 加实测制品摘要 | SDK `0.1.13`、Integration `0.1.0-SNAPSHOT`；类目录及 CLI 使用的 JAR 路径通过，不代表远端服务版本 |
| P2 模拟端点把修改计为读取 | 白名单核验 HTTP/RPC 方法、工具名、唯一客户参数和测试身份；错误工具/方法/客户/多余参数/非法 JSON 均拒绝且无事实响应；保存每次源调用尝试 | `sourceWriteCount` 为可能修改的尝试数，包括已拒绝/未知工具，不是成功写入数；模拟器不是生产认证服务 |

测试位置：

- `EmployeeAgentInsuranceProviderTest`：真实 loopback HTTP 与外层 Grant/内层绑定。
- `CheckpointPrecisionTest`、`TaskRecoveryTest`、`ExecutionBoundaryTest`：数值、补参和确认恢复。
- `WealthFactsTest`：公共合同、覆盖缺口及时间语义。
- `OpenJiuwenSolutionAssemblerTest`：分别伪造 template/integration 版本，安装前拒绝。
- `ControlledEndpointsTest`：11 项直接请求验证；`WealthKitTest`：原生 AgentLoop 与业务时钟。

## 时间口径

受控任务的默认 `businessAsOf=2026-09-05T00:00:00Z`；EXPIRED_RISK 单独推进到
`2027-03-02T00:00:00Z`。现有单 Clock Java 重载保持行为兼容，新重载允许分开业务与观察时钟。
Kit 的 Grant/绑定过期、执行时间、事件与 Source.observedAt 仍用系统时钟。
另有观察时钟推进至 2030 年、业务时间保持不变的单测，确保不会把日期推移当回归。
报告同时保留业务日期和真实执行证据时间；未把未来业务日期当发生过的源调用时间。

## 本次实际验证

环境：Ubuntu amd64，OpenJDK 21.0.12，Maven 3.8.7，Python 3.12。

| 项目 | 结果 |
| --- | --- |
| `mvn -B -ntp verify` | 13 模块 BUILD SUCCESS；28 类、164 项，157 通过、0 失败、0 错误、7 跳过 |
| `python engineering/verify_contracts.py` | 9 Schema、54 Fixture/资产/状态探针通过 |
| `bash examples/solutions/wealth-facts-kit/run-controlled.sh` | 五份配置完成，实际 Java Runtime 与本地 HTTP Adapter；模型是脚本响应 |
| `python engineering/verify_wealth_kit.py` | 三份正常 COMPLETED、两份拒绝 REJECTED；正常读取 2/1/1，拒绝读取 0/0；修改尝试均为零 |
| `git diff --check` | 通过 |
| 真实模型、真实银行/EA 授权只读、真实同条件 Provider 替换 | 未执行，待对应验收 |
| 非原作者独立接入、多进程/多节点互斥 | 未执行，不以 AI 自测替代 |

7 项跳过均为显式真实依赖 Smoke：银行查询 2、银行任务/转账各 1、EA 财富/保险各 1、反诈 1。
全量日志：`/tmp/finance-boundaries-final-20260906.log`；CLI：`/tmp/finance-boundaries-cli-20260906.log`。
CI 继续执行原门禁并上传 XML 与受控报告，不新增线上工作流。
本地 CLI 原生调用段约 695–919ms，包含脚本模型而非真实推理；无可比人工接入基线，不计算提效比例。

## 兼容与接入

1. 保险四参数 Config 源码兼容但 fail-closed；接第五个可信绑定 Supplier，先取得宿主 adoption-owner 依据。
   错客户的保险返回投影不会暴露；源拒绝与异常正文改为稳定错误码，消费者应按错误码处理。
2. 文件 Store 的非整数 JSON 数字改读 BigDecimal。旧已损失精度的数据不能修复，保留原任务查证，禁止重提。
   参数指纹算法未变，数值等价仍按去尾零比较。外部代码不得依赖强转 Double。
3. Lock 保留 v1 外壳及 Runtime 字段，assets 增加两个 Runtime 类型；旧 Lock 应重新装配，
   有固定数量断言或资产类型白名单的消费者需适配。无 Pack 声明摘要仍是 observed-not-pinned，未升级为发布证明。
4. 显式真实 Smoke 需要真实 principal 与可信绑定导出文件，不再拼接 `employee:adoptId`。
   文件读取仅人工测试接线点，不负责验证导出者的权威；生产仍用可信宿主供应器。
5. 源调用尝试记录不保存凭据或客户原始数据；共享财富合同与两侧原业务响应版本未变。

复跑入口见 [Kit README](../../examples/solutions/wealth-facts-kit/README.md)；在仓库根目录依次执行表内四项命令，
Python 可用 `python3 -m venv /tmp/finance-harness-verify` 后安装 `requirements-conformance.txt`。
所有默认业务调用只连 loopback。运行输出在 `examples/solutions/wealth-facts-kit/target/runs/`，不提交仓库。

下一步仍是灯塔组确认财富字段与真实宿主授权，场景工具组独立复跑只读接入。
需要的材料和验收角色保持 [原实施记录](../financial-agent-kit-v01-worklog.md) 不变；不扩原语清单。
