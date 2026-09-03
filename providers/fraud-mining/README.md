# fraud-mining Provider

该模块把外部反诈服务映射成 Finance Harness 的只读能力
`fraud.transfer-risk.assess`。它属于南向 Provider，不属于 Harness Core。

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
