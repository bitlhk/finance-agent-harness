# 面客转账前风险解释（待审核草稿）

`transfer-anti-fraud@0.1.1-draft.1`，Profile 为 `customer-self-service`。
本资产是方法草稿，尚未由 Harness 维护者评审合入或发布。Catalog 中的独立版本
登记只供关联 PR 核对；Builder 的正式固定发布门禁不因本目录存在而解除。

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
方法仅声明 `fraud_transfer_risk_assess`。复用的 `risk-review-basics` 能力包仍包含余额查询；
真正可调用范围由 Profile、宿主授权与 Provider 校验共同限定，方法文本不会授予权限。
转账工具来自另一 Capability Pack，确认权属于可信 Host，均不由本专业方法授权。

## 验证边界

`python -m unittest discover -s engineering -p test_antifraud_domain_asset.py`
验证声明、派生来源、旧版本保留与查询边界；`engineering/verify_contracts.py` 验证已有 Schema。
Builder 的真实 Java 安装回归另行验证加载与卸载。本资产没有真实客户数据或模型效果成绩。
