# 贷后风险核验辅助 MVP 改动清单

基线：Harness `e8a99612c3f91dc7506e9fcfc364ad5d523bb669`；Builder `81aed66329dce422f21f0dab0dbd2712cd1a9d90`。

## 新增业务资产

- 方法：`post-loan-risk-verification@0.1.0`，由岗位智能体 `post-loan-risk-control-assistant@1.2.1` 收窄迁移。
- 合同：`postloan.risk-snapshot.query`、`postloan.risk-escalation.evaluate`。
- Provider：`post-loan-risk-reference`，只读、版本化合成数据，服务端企业白名单映射。
- 场景扩展：风险快照、确定性预警、模型解释、复核、版本与回放。
- Builder 配置：风险合规目录、模板、模块、专用 Host 声明和体验页。

## 复用且未修改的公共机制

- `CapabilityRequestV1` / `CapabilityResultV1`、Provider SPI 和观测包装。
- Builder 资产解析、依赖准入、构建阶段、安装 Lock 和受管生命周期。
- `ManagedScenarioExtensionV01` 会话动作、身份归属、迟到结果隔离接口。
- 现有模型注册与服务端凭据注入。
- 历史回放的零业务调用计数语义。

## 必须修改的公共 Builder 接线

- `workbench.py`：原来用“电诈否则 CRM”的二分硬编码选择环境变量；改为显式场景注册表，新增场景只登记所需服务端配置和映射文件。
- `workbench_contracts.py`：有限业务连接枚举新增 `post-loan-risk-reference`。
- `experience-loader.js`：新增场景体验资源加载分支。
- 统一目录投影：登记新业务模块、连接类型和员工 Profile。

这些是注册与投影变更，不包含贷后业务判断。

## 仍属场景本地、尚未公共化

- 不可变 JSON 报告保存、版本指针、摘要校验和复核冲突处理，客户经理、电诈和本场景目前仍各自实现。
- 场景页面仍分别维护展示代码。

本轮没有为了第四场景重构旧三场景，以控制回归范围；但报告版本库已经出现第三份同类实现，属于明确公共机制缺口。下一轮应抽取仅负责版本持久化和权限回调的薄组件，再逐场景迁移，不能把业务字段统一成一个“大报告模型”。

## 未纳入本分支

- 手机账户/收支组合的组件安装准入问题：独立故障线处理，不与贷后资产提交混合。
- 内部投研：仅保留岗位资产和数据合同盘点；Wind 等可信 Provider 和使用授权未明确前不上线。
- 客户投顾：涉及适当性与面客责任，单独规划。
