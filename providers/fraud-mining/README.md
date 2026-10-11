# fraud-mining Provider

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
