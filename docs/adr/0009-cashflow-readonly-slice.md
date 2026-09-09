# ADR-0009：收支只读切片，不扩展 Core

状态：候选实现，待场景 Owner 确认；2026-09-09。

延续 ADR-0004/0005/0006/0008 的 Java-first、四合同、Query/Task 和领域模块边界。
从手机银行 `main@4652b28` 提取两个稳定只读事实能力与一种解释方法，不搬迁 Agent
三引擎、意图树、菜单或全部接口。能力 ID 沿用已存在的业务含义。

- `packages/java/cashflow`：候选收支事实合同和共享消费者，无 Runtime/银行私有依赖。
- `providers/non-ai-banking/CashflowMapper`：特定源响应、治理策略版本与口径转换。
- `cashflow-readonly` Capability Pack：两个 QUERY 工具；`cashflow:read` 是要求，不是授权。
- `cashflow-customer-self-service` Domain Pack：专业步骤、输出和缺数约定，不实现执行引擎。
- Builder：新增有限配方和宿主权限接线，依赖 Harness 制品；不复制合同或金额计算。

源摘要包含最多 100 个账户，分类查询只覆盖已分类流水；趋势和分类总额的覆盖也不同。
这些差异必须保留，不要求二者金额强制相等。信用卡账单余额不重复叠加为账户支出。
响应不含币种时须读取源政策；只支持已核实的 CNY、BOOKED 和当前政策版本，其他值失败关闭。
模型不能替代缺失账期、币种、身份或权限，也不做跨币种或风险等级换算。

兼容：不修改 Core/Integration、原能力和响应。增加独立 V0.1 Schema/JAR，参考 Provider
增加两个能力声明和一个传递依赖；只有显式选择新的 Pack 才安装新工具。
旧 Builder 若硬编码 Harness JAR 集合，需要配套兼容补丁；不能忽略多出来的制品。
当前 SNAPSHOT 制品以固定 commit 和 SHA-256 追溯，不承诺已发布稳定 API。

暂不引入完整本体、通用权限平台、第二 Runtime、新的工作流或办理能力。
是否进一步提炼公共原语，由第二真实消费者/来源的业务验证决定。
