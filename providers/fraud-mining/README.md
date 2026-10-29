# fraud-mining Provider

## Builder 组件准入

`assembly.v0.1.json` 同时声明 `fraud-risk-readonly@0.1.0-rc.1` 的转账前风险查询，
以及主线 `fraud-risk-evidence-query@0.1.0` 的证据查询。两者均为 QUERY。
连接类型为 `fraud-mining-readonly.v1`，与主线 ManagedProviderFactoryV01 一致。
转账前查询需要 `fraud:read`，证据查询需要 `fraud-risk:read`；声明不授予权限。
Provider 身份和 Maven 坐标仍为实际实现的 `0.1.0-SNAPSHOT`，交付须额外固定源码提交及摘要。

该模块把外部反诈服务映射成 Finance Harness 的只读能力
`fraud.transfer-risk.assess`。它属于南向 Provider，不属于 Harness Core。

同一 Provider 还提供独立的只读能力 `fraud.risk-evidence.query`，用于电诈风险核验辅助。
新能力只接受宿主授权映射后的 `FRAUD_SOURCE_CINO`，执行精确匹配和有界分页，并显式返回
查询完整性。它不会复用旧能力的 `ALLOW/BLOCK` 语义，也不会给出放行、阻断或交易指令。

`matchScore`、`modelScore` 和来源标签按原含义分别保留；这些分数不是银行校准后的诈骗概率。
上游没有稳定快照或版本字段时，结果明确记录为未知，分页完成不冒充稳定快照。

当前参考实现读取反诈系统中的已知风险事件，输出标准化的
`ALLOW` 或 `BLOCK` 结论及最小证据。银行可替换该 Provider，接入自己的
黑名单、规则引擎、专业模型或组合风险决策服务。

当前已连接的专业模型为羲和反诈 Transformer
`Transformer_mapemb_feature_crossattn_hybrid_liteemb_continue_v2`，不是大语言模型。
已知事件命中是当前阻断依据；模型分数是辅助信号，未经过银行阈值校准前不单独触发阻断。

模型或上游服务失败会返回标准 `FAILED`，不会伪装成正常客户。

请求参数：

- `accountRef`：反诈系统中的账号或客户标识；
- `customerRef`：未提供 `accountRef` 时使用。

该 Provider 不执行转账。转账系统仍须在预检和提交阶段消费风险结论并执行阻断。

## 宿主会话鉴权

正式受管装配由 `FraudMiningFactoryV01` 使用 `Connection.currentSession` 构造 Provider。
宿主提供当前 `SourceSession`（凭据、业务主体、操作人、宿主会话、过期时间）。
每次查询和证据分页发送前重新读取，不缓存凭据；经续期的凭据用于下一次来源请求。
业务主体与 `FinanceContext.subjectRef` 绑定；查询参数中的来源 CINO 由宿主授权映射。

Provider 在请求前核对主体、操作人、宿主会话和有效期，以 `X-Session-Id` 传给来源。
缺失、过期、撤销、身份不匹配及远端 401/403 返回非重试 AUTHORIZATION 失败。
分页中途撤权也返回失败，不释放此前取得的部分证据，不降级为匿名请求。
HTTPS 用于受保护服务；合成开发服务支持数值 loopback HTTP。客户端不跟随重定向。
凭据不进入资产声明、模型参数、回执或日志；SourceSession 的字符串展示为 redacted。

`Config(baseUri, timeout)`、五参数分页 Config 及单参数 Provider 构造器保留给既有匿名
reference 测试。受管 Factory 始终使用有会话检查的构造器。
生产宿主负责签发、续期、撤销与来源授权；本轮只使用合成会话和 loopback 服务验收。
