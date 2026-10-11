# fraud-mining Provider

## Builder 组件准入

`assembly.v0.1.json` 只声明支持 `fraud-risk-readonly@0.1.0-rc.1` 的 QUERY，
不声称支持余额查询或整个 `risk-review-basics`。
连接声明 `synthetic-bank-http.v1` 对应当前受控 HTTP 宿主连接，不代表生产接入验收。
Provider 身份和 Maven 坐标仍为实际实现的 `0.1.0-SNAPSHOT`，交付须额外固定源码提交及摘要。

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

## 可选宿主会话鉴权

原有 `Config(baseUri, timeout)` 保持兼容。受保护服务使用
`Config.authenticated(baseUri, timeout, new HostSession(sessionId, expiresAt))`；
会话只由可信宿主配置，以 `X-Session-Id` 传给服务，不能从模型参数取得。
到期会话在发送请求前失败；远端拒绝也返回 FAILED，不降级为 ALLOW 或匿名请求。
认证配置要求 HTTPS；隔离开发服务可使用数值 loopback HTTP。密钥禁止进入日志、
模型上下文或公开证据；HostSession 的字符串展示始终隐藏凭据。
生产宿主负责签发、续期、撤销及服务身份/ACL；此 Provider 不替代银行授权系统。
