# 财富只读双线验收 V0.1

启动基线：`647b41c5b2f7b0426ab5003f38e976a58f4a23f6`，PR #5 已合并。
本阶段只使用现有接入套件，不新增金融原语、第二 Runtime、Builder 或授权平台。

## 首个任务与状态

候选场景：**上海 EA、财富经理 EMPLOYEE，描述一个明确获授权客户的已知资产范围、风险测评状态与信息缺口**。
任务只读，不给正式适当性结论、不生成购买/转账动作。真实环境与客户名单须由场景负责人确认，不能沿用历史演示账号推定授权。

| 工作项 | 当前状态 | 完成依据/下一步 |
| --- | --- | --- |
| 修复基线与双版本 CI | 已完成 | PR #5/main `647b41c` |
| 受控复跑入口与记录格式 | 已实现，验证结果另记 | `engineering/run_onboarding.py` |
| 真实员工、客户范围、环境与负责人 | 待确认 | 灯塔场景负责人提供获准验收范围 |
| 宿主当前实授及 adoption-owner 来源 | 待接线 | 技术架构组与 EA Owner 核实权威读取入口 |
| 真实客户 READY、字段口径与来源 | 待材料 | 受控样例、旧 DEGRADED Smoke 不作替代 |
| 非原作者执行者 | 待安排 | 场景工具组指定未参与开发的同事 |

## 线一：真实授权只读

### 接线前必须取得的材料

仅使用受保护工作目录/Secret Manager 传递身份及密钥，不把客户原始数据提交 Git。

| 材料 | 权威来源与责任人 | 核实什么 |
| --- | --- | --- |
| 环境、只读窗口、获准员工/客户范围 | 灯塔组场景负责人 | 范围明确、可关联内部批准记录 |
| 当前 Grant | EA/宿主权限 Owner | principal/type、subjects、purpose、session、实授 scopes、authorityRef、expiresAt 来自当前授权，不从 Pack 复制 |
| adoption-owner 绑定 | EA 身份 Owner | principal 与 runtime/agent/adoption 的真实对应及有效期，不比较用户名猜测 |
| Token 签发路径 | 技术架构组与部署 Owner | 短时凭据、验签/防重放、密钥不进入输入/Lock/Trace |
| 字段与脱敏核对样例 | 灯塔财富场景 Owner | 本行资产范围、币种/小数精度、估值时点、风险体系/版本/期限、缺失语义 |
| 拒绝与撤权测试安排 | 场景及权限 Owner | 仅撤销专用验收授权，不改变真实员工其他业务权限；没有可控授权就标未执行 |

### 复用的代码路径

1. 可信宿主每次读取当前 Grant，供 `InvocationDefaults.currentScopes` 与 `BoundFinanceProvider` 使用。
2. 外层 `wealth.facts.describe` 需要 `wealth:facts:read`，内层 `wealth.previsit-context.query` 需要 `wealth:previsit:read`。两者是需求，不是授予。
3. `EmployeeAgentWealthProvider.Config.identityBinding` 读取已核实的 adoption-owner 对应；保留 Provider 调用前及返回客户校验。
4. 只调用 `prepare_wealth_previsit_context`。复用 `WealthFactsProvider` 的确定性映射、`WealthSourceSemantics` 和 `WealthFactsConsumer`，不复制 EA 私有字段到公共消费者。
5. 进入模型前完成访问检查与白名单投影；首轮可使用本地脚本模型，不向外部模型发送真实数据。真实模型需另行确认数据出口。
6. 用现有 Assembler/Lock、原生调用和 Bridge Evidence 关联本次运行。授权/观察用真实时钟，不能沿用 Fixture 的固定业务日期推断有效性。

**不是直接运行旧 Live Smoke。** `EmployeeAgentWealthLiveSmokeTest` 直接构造 Context.scopes，
绕过本阶段要求的宿主 Grant 读取，并将 `previsitContext` 原始结果落盘。
`LiveIdentityBinding` 文件读取本身也不验证导出者权威。旧 Smoke 是接口诊断，不是本阶段验收入口。
`run-live-demo.sh` 也不自动升级为新验收入口；真实接线必须基于上述受管路径，完成身份与日志审查后再启用。
缺少宿主接口说明时不新增自证 JSON、永久 Grant 或 `verified=true` 配置凑通。

### 必测矩阵

| 用例 | 预期 | 应保留的证据 |
| --- | --- | --- |
| 合法员工、有效实授、正确客户 | 获得允许的事实；缺数单独显示；适当性 NOT_EVALUATED | 客户匹配、Decimal/币种/范围/来源/时间核对及脱敏摘要 |
| 错 principal / 错 session / 错 adoption | 本地拒绝，不调用源查询 | 拒绝原因、对应当前绑定/请求关联 ID、源调用计数或受管出站记录 |
| 授权范围外客户/参数换客户 | 模型上下文无客户事实 | 边界拒绝；不尝试枚举真实未授权客户 |
| 当前授权不足或撤销 | 同一会话下一次调用拒绝，旧记录不授予权限 | 权威撤权记录与时间、本次读取当前 Grant 的证据 |
| Grant / adoption 绑定过期 | 拒绝，不自动续期 | 真实观察时间、来源有效期、拒绝记录 |
| 接口完成但事实缺数/风险过期 | COMPLETED 与 PARTIAL 可同时存在，不补零、不升级家庭资产、不推荐 | 字段级缺口、源覆盖范围与风险体系 |
| 源返回错客户/员工私有信息 | 错客户拒绝；员工私有字段不进入模型 | 本地投影/原生工具结果核验，避免把原始数据写入普通日志 |

本地构造错误 Context 或测试 Grant 只证明本地边界，不能称真实撤权/身份源验收。
真实错主体/撤权案例必须由 Owner 安排合法测试身份；没有受管源计数时标 `NOT_OBSERVED`，不默认填零。
不针对未获授权对象直接发探测请求，不撤销生产用户真实业务权限。

结果按项标 `NOT_RUN / BLOCKED / PASS / FAIL`。允许事实齐全并不等于适当性已判断；
接口降级返回成功不能替代客户事实可核对。证据仅引用受控存储位置、脱敏摘要和请求/审计关联，
不新建客户明文日志库。Owner 负责业务口径签认，AI 不代签。

## 线二：非原作者独立接入

场景工具组指定未参与开发的同事，使用[独立接入手册](onboarding-wealth-v01.md)。
先从受控样例完成环境、装配、运行、失败诊断和证据核验；不等待真实客户材料。
工程脚本只记录自动命令耗时，不替同事填安装时间、求助次数或参与身份。

真实材料到位后，同一同事按线一补宿主实授 Supplier、IdentityBinding Supplier、
配置来源语义与只读 Provider。记录实际专属修改，不为了零改动放宽权限。

## 分项结论与组织边界

- 受控共同语义、边界回归：自动实验结论。
- 独立接入：非原作者的操作记录与可复跑结果，待本人/场景工具组确认。
- 真实授权只读：本次获准角色、客户范围和时间窗口的结论，不能扩大为办理验收。
- 同 Profile 的真实 Provider 替换：本阶段不默认执行；跨角色展示不证明该项。
- Runtime 通用能力由参谋部与 2012 负责；技术架构组负责 Harness/Integrator/装配和接线；灯塔组负责业务及授权范围确认；场景工具组负责独立复制。

当前不自动部署、迁移数据或执行真实办理。下一步依据两线实测卡点决定，不按新增原语或 Pack 数量宣布成熟。
