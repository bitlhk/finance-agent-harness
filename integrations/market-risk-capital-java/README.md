# 原标准法资本：openJiuwen Java 接线

本模块是 `market-risk-capital-readonly.v1` 的受管 Host 扩展，使用真实
openJiuwen Java **0.1.15** 的 ReActAgent、Domain Pack 安装器与原生工具安装器。
底层调用 `market-risk-capital-original` Provider，再通过回环 HTTP 调用独立发布的
原 Python `mktrisk_engine.standard.compute_standard_capital`，不重写公式。

## 边界

- 只安装 `market.risk.capital.query` / `market_risk_capital_query`；模型参数必须为空。
- Host 持有当前用户、组合、任务、会话、固定快照和授权。浏览器只能提交问题，不能选择上述身份。
- `privateStateRoot/market-risk-authority.json` 必须位于交付制品之外。字段由部署方维护：
  `schemaVersion=market-risk-host-authority.v1`、`ownerRef`、本地 `endpoint`、`timeoutMs`、
  `bindingsFile`、`bindingsDigest` 和 `leases`。每个 lease 有主体/任务/会话/快照、
  `credential`、`expiresAt`、`scopes`；凭据不写入组件包、页面或执行结果。
- `bindingsFile` 是独立的非凭据配置，采用 `market-risk-capital-bindings.v1`，固定工具制品、
  原源码、快照与参数摘要。来源数据和当前服务凭据仍由原工具私有会话文件管理。
- 创建会话时只领取一个尚未使用的有效 lease；每次模型请求、工具请求及结果读取重新检查权限。
  关闭会话释放本模块的领取标记。生产 IAM 与跨主机部署不在此模块验证范围内。
- 读取 `component-plan.json` 和 `service-lock.json` 中已锁定的方法、工具和来源；漂移拒绝。
  `bootstrap` 仅表示就绪，实际安装回执在执行时产生。
- 成功取决于本次实际工具结果。工具未调用、缺输入、服务错误或超时都显示失败，
  不复用上一轮事实；模型结束不等于计算成功。
- 原数据日期缺失时保持空值，计算时间单独记录，不替代来源日期。

## 本地验证

先按 Agent Store Workshop 工具的发布说明得到固定工具制品，并安装其锁定依赖。
测试必须传入独立确定的制品摘要；缺前置直接失败，不跳过：

```powershell
mvn -f integrations/market-risk-capital-java/pom.xml `
  "-Dfh.originalToolRelease=$env:FH_ORIGINAL_TOOL_RELEASE" `
  "-Dfh.originalToolReleaseDigest=$env:FH_ORIGINAL_TOOL_RELEASE_DIGEST" test
```

`MarketRiskNativeIntegrationTest` 启动本地固定测试模型和真实原工具服务，
核对方法正文进入模型请求、原生工具调用、两个主体/任务、合法缺日期、数据缺失失败及撤权后的读取。
`target/native-evidence/` 保存实际运行返回和模型请求。输入为合成头寸，模型质量不在此测试中评价。
该测试是组件接线验证；Builder 独立交付与完整场景装配各有后续验证。

CI 在 `mvn verify` 前运行 `engineering/prepare_original_market_risk_tool.py`，
从固定 Agent Store/Builder 提交取得原算法和发布器，沿原发布配方生成独立工具。
使用固定 uv 版本按 `uv.lock` 安装独立 Python 3.12 环境，再传入上述两个 Maven 属性。
合同测试仍分别运行在 Python 3.10/3.12；工具环境不替换矩阵解释器。
准备失败即停止，不能跳过原生集成测试；准备记录单独保留且业务执行保持 `NOT_RUN`。
