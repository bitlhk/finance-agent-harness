# Engineering Plane

Engineering Plane 是构建期能力，不进入生产请求热路径。

V1 只实现最小合同、CLI 验证和 Builder 消费验证。V1.2 计划实现：

- AssetRef；
- SolutionManifest；
- SolutionLock；
- Deterministic Composition；
- Builder Adapter；
- Pack / Provider Toolkit；
- Conformance 和 Sandbox 集成。

AI 可以提出 Candidate，但 Builder 必须确定性构建，Eval 独立验证，Human Review 决定发布。

V1 同时定义最小 AgentSolutionManifest、EnvironmentCapabilityProfile 和 DeploymentPreflightReport，并用手工或 CLI 完成一次发布前局部优化。完整 Builder UI、通用插件和自动优化平台不属于 V1。
