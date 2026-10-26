---
schema: speckit-feature-record/v1
feature_id: FEAT-001
title: 只读报价与优惠咨询组件 H1
parent_requirement: https://gitcode.com/Euphoria_Yan/agent-store-builder-dev-wo-fork/issues/44
acceptance:
  status: accepted
  decided_by: Euphoria_Yan
  decided_at: '2026-10-19T09:06:04Z'
  decision_source: conversation
behavior_acceptance:
  status: accepted
  decided_by: Euphoria_Yan
  decided_at: '2026-10-19T09:06:04Z'
  decision_source: conversation
delivery:
  phase: ready-to-merge
  branch: codex/quotation-readonly-h1
  pull_request: ''
  review_target:
    type: local-diff
    url: ''
    revision: sha256:d608c25e72c2e6b41dca0a7062986b10e179f35da17c24b73d9f2724890190ac
  merged_commit: ''
dependencies: []
shared_contracts:
- docs/quotation-readonly-v01.md
ownership:
  owner: ''
  collaborators: []
release:
  target: quotation-readonly-v0.1-candidate
  delivered_in: ''
  evidence: ''
completion:
  completed_by: ''
  completed_at: ''
  evidence_source: ''
architecture_impact:
  level: L2
  update_required: true
  reason: Add reusable quotation facts and a bounded synthetic reference Provider
    without changing Core.
  affected_files:
  - docs/quotation-readonly-v01.md
  - docs/current-architecture.md
definition_of_done:
  code_complete: true
  tests_complete: true
  evidence_complete: true
  architecture_synchronized: true
  review_passed: true
---

# FEAT-001：只读报价与优惠咨询组件 H1

## User Value

为Builder FEAT-017的外部前提提供可复用报价合同、领域验证、受管合成Provider及独立组件证据。两个仓库的Feature编号独立，FEAT-001不重编号Builder FEAT-017。

## Scope

一个quotation.offer.query，CNY精确两位小数，来源价格/万分比、可信主体、完整条件/期限/来源绑定、有限事实投影。固定合成来源，无网络业务。先H1组件，再由关联Builder Feature接真实Host。

## Non-Goals

不改Core/Execution/SPI/Grant，不新增第三方依赖，不运行真实模型/银行或TASK，不做UI/原生SDK接线、不签发上游维护者批准或生产资格，不提交/推送/部署/合并。

## User Stories

### US-001：消费精确、可绑定的报价事实
作为金融组件消费者，我希望以严格版本合同取得适用参考报价，避免相同金额被误绑到不同客户、产品或币种。
- 前置：合成数据、受信上下文和已登记合同。
- 主路径：解析完整事实与条件，精确核对原价、参考价及万分比。
- 边界：缺数、未知字段、类型强转、无效时间、冲突或失配时拒绝，不回退旧价。
- 验证：VER-001、VER-002、VER-003。

### US-002：使用受管、只读的合成Provider
作为实施人员，我希望安装固定配置及可追溯工厂，保持当前主体限制和零业务写操作。
- 前置：受管安装、可信Host Grant和合成快照。
- 主路径：校验文件/摘要并冻结，按可信主体与所选产品查询。
- 边界：模型不能指定客户、优惠、路径或权限；TASK及办理字段拒绝，来源不隐式更新。
- 验证：VER-004、VER-005、VER-006。

### US-003：明确披露控制和可装配边界
作为Host维护者，我希望事实验证和运行支持要求明确，避免普通QUERY成功被当成可公开报价。
- 前置：固定绑定、合同和源版本。
- 主路径：纯领域验证给出有限事实投影；组件声明披露控制要求。
- 边界：纯函数不冒充鉴权；新包不自动扩展Runtime，旧资产不改变。
- 验证：VER-007、VER-008。

### US-004：取得不夸大的组件验收证据
作为验收人员，我希望用独立断言验证H1并追溯材料，不把组件通过冒充真实SDK、面客出口或生产验收。
- 前置：冻结用例、版本和只读参考配置。
- 主路径：执行固定组件门禁、回归和架构走查，记录实际结果与摘要。
- 边界：缺项/跳过分列，无模型自评签发PASS，无真实模型/银行调用。
- 验证：VER-009、VER-010。

## Verification

### VER-001：严格结构
所有嵌套对象拒绝未知/重复键及非法类型；AVAILABLE须完整quote且gaps为空，其他状态quote=null且有有限理由。

### VER-002：精确金额
CNY固定两位十进制字符串，无隐式转换/舍入；万分比与原价/参考价精确相符，零原价不制造折扣。

### VER-003：绑定和有效性
客户/产品/币种/合同/来源版本/摘要完全匹配；受信时间在半开区间内，未来或过期不能披露。

### VER-004：受管来源
配置路径非链接、大小有界、摘要吻合、唯一映射并启动冻结；不接受模型路径、动态代码或伪造来源授权。

### VER-005：主体与产品约束
Host当前Grant和Provider范围共同限制查询，参数不能切换主体、扩大scope或制造优惠；未知/未授权产品安全拒绝。

### VER-006：只读且无副作用
能力只注册QUERY；TASK/续办/办理字段拒绝，业务写派发计数为0，无网络/银行/真实模型调用。

### VER-007：有限披露投影
只返回验证后的事实/claimCodes或固定理由，不输出模型正文或失败的部分价格；纯领域函数不声称已经鉴权或发送。

### VER-008：兼容准入声明
Pack/Provider/目录/观测/companion一致；必须声明quotation-disclosure.v0.1，不把声明当实际Host支持；Core/SPI和旧资产语义不变。

### VER-009：独立H1证据
用例先冻结、组件反例不漏跑；报告绑定实际源码/合同/快照/用例/制品，篡改或不完整不能通过。

### VER-010：阶段和架构一致
文档、合同和代码职责一致；H1、SDK/Host资格、Builder资格、维护者审核和发布分别记录，未跑不计通过。

## Dependencies

只复用仓库现有Core/Java21/Jackson/JUnit与已有离线工具；缺工具或依赖如实BLOCKED。H2/H3不是H1组件实现前置，但不完成H1也不能给Builder解除外部前提。

## Shared Contracts

docs/quotation-readonly-v01.md：Euphoria_Yan确认的项目设计公共语义；维护者接纳与实际发布另列。

## Decisions

- 2026-10-19T08:54:18Z：Euphoria_Yan明确确认已呈现的接口方案。
- 2026-10-19T09:06:04Z：Euphoria_Yan对“docs/features/及完整8任务H1实施批次”明确确认。其范围限于该批次，不含提交、推送、PR、部署、合并。Feature接受和行为确认由安装helper分别记录。
- Requirement #44已accept，决策评论为comment_194494204；Harness CODEOWNERS路由bitlhk的维护者评审尚未发生。

## Delivery History

- 2026-10-19：从固定main a8fd7808f5da4742506c16344e208c26506240ec建立隔离Git分支；原仓未改。先补独立工作项和确定性检查，不把已有快照或历史余额证据当H1验证。
