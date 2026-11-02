# 面客转账前风险解释（发布候选）

`transfer-anti-fraud@0.1.1-rc.1`，Profile 为 `customer-self-service`。
本资产是发布候选，尚未由 Harness 维护者评审合入或发布。`rc.1` 明确候选语义；
Catalog 登记与装配声明允许通用发现和静态准入，不解除 Builder 的正式固定发布门禁。

原 `domain-packs/anti-fraud` 的 `0.1.0` 保持不变。
派生基础为固定提交 `884947047fca4bf6563c0068f7cf11656ae964d3` 的原面客包及
`domain-packs/risk/internal-control/skills/transfer-risk-review/SKILL.md`。
复用内部方法的证据纪律，改为客户解释用途；没有把内部审核身份或权限移交给客户。
精确来源亦写入 `pack.yaml` 的既有 `sourceReferences`。

Euphoria_Yan 于 2026-10-10 接受六项内容边界：明确账户、回执三态、必要来源与时间、
未命中不等于安全、银行规则负责处置、独立 TASK 与 Host 负责转账及确认。
内容接受不等于发布，也不证明模型在实际银行场景能正确遵循方法。

## 安装与权限

原生 Domain Installer 将 `skills/transfer-risk-explanation/SKILL.md` 安装到同一 ReActAgent。
方法仅声明 `fraud_transfer_risk_assess`，依赖独立 `fraud-risk-readonly@0.1.0-rc.1`，
由反诈 Provider 完整覆盖；不再依赖混合余额与反诈能力的旧包。
真正可调用范围由 Profile、宿主授权与 Provider 校验共同限定，方法文本不会授予权限。
Catalog 另允许 `local-read-only` 与 `local-simulator-task` 使用这份客户解释方法，
方便“创建 Agent”的受控宿主选择；方法仍仅查询风险，不因此获得 TASK 或确认权限。
转账工具来自另一 Capability Pack，确认权属于可信 Host，均不由本专业方法授权。

## 验证边界

`python -m unittest discover -s engineering -p test_antifraud_domain_asset.py`
验证声明、派生来源、旧版本保留与查询边界；`engineering/verify_contracts.py` 验证已有 Schema。
Builder 的真实 Java 安装回归另行验证加载与卸载。本资产没有真实客户数据或模型效果成绩。
