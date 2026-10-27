# Previsit delivery quality candidate

Scope: customer-manager report delivery, not new banking capabilities or a new report protocol.
Related design baseline: [Agent 动作与交互统一规范](action-interaction-policy.md). Its confirmation-policy changes are not implemented or verified by this report-quality candidate.
Baseline H: 682073b403c8bfd4b4f83e927101a118eaee5460.
Baseline B: 7c2d74713a39a1e63b2a73d90cc14c5c8c5aaffc.

## Findings

- The model budget is 900 tokens; four evidence categories are presented as four fixed business sections.
- The saved report already contains asset/risk/holding facts. The reference UI promotes only customer/date.
- Number validation uses substring membership, not normalized numeric equality.
- Builder already inlines declared requiredResources into the digest-bound installed SKILL.md. Do not add another loader.
- Employee-agent and Harness have not been proven to receive identical SOP, facts, model or policy context.

## Frozen acceptance matrix

| ID | Test | Required result |
| --- | --- | --- |
| Q01 | Legacy canonical and JSONL reports | Remain readable and streamable |
| Q02 | SOP business headings inside existing text | Preserved without changing authority or root schema |
| Q03 | Required reference text materialization | Content and digest bound; missing reference rejected |
| Q04 | Assets, risk and holdings | Known fields displayed; business date not receipt date |
| Q05 | Same-currency, same-date verified ratio inputs | Decimal calculation with operand references and rounding |
| Q06 | Missing currency/date, mixed currency/date, invalid denominator | No invented ratio; explicit unavailable reason |
| Q07 | Numeric substring, unsupported percentage | Rejected before release |
| Q08 | Exact numbers, commas, ten-thousand denomination, zero | Accepted without introducing new amounts |
| Q09 | Past maturity, unverified oral input, excluded product | Existing rejection/boundaries retained |
| Q10 | Unknown references and report shape | Rejected; frozen facts retained |
| Q11 | History, review, cancellation and owner policy | Existing focused regressions retained |
| Q12 | Desktop/mobile actual reference components | Facts readable, no overflow; fixture evidence labeled |

## Boundaries

No new provider/model requests during offline verification. No live changes, permissions, credentials or old-record rewrites.
Four evidence categories remain the wire contract; SOP-defined Markdown subheadings are content, never executable UI.
Ratios describe the supplied snapshot, not current holdings, verified suitability or a policy minimum.
Numeric membership is not full semantic verification. Tests cannot establish that every model explanation is correct.
Production qualification and live model comparison remain separate gates; do not reuse an old G05 qualification for a new JAR.

## Candidate verification

- Q01/Q02/Q03: actual OpenJiuwen SDK with loopback SSE; declared reference material from Builder's real materialization test is byte-for-byte present in the request. Digest tampering rejected. No real model/provider request.
- Q04: same synthetic frozen facts that previously displayed only name/date now also display assets, cash, investment assets, risk rating/expiry and holding amount/maturity. Missing currency is not inferred.
- Q05/Q06/Q07/Q08: exact decimal and snapshot tests cover 20% and 62.5%, invalid operands, ambiguous profiles, missing or differing date/currency, substring rejection, units, zeros and Markdown list ordinals.
- Q09/Q10/Q11: existing ServicePreparation and customer-manager conversation/confirmation/history regression tests retained. This is not a general NLP proof.
- Q12: production React components with synthetic HTTP transport, 1180/390/375/320 widths, 11 browser checks including history, review, failure and evidence binding. Not ordinary SSO or live Host acceptance.
- Java focused run: 180 tests passed; built JAR loading additionally repeated 14 selected checks.
- Shared package dependency follow-up: 52 existing fraud/post-loan adapter tests passed (14 + 16 + 22), not new business executions.
- Builder Python: 69 passed. Frontend: 114 passed. Browser: 11 checks, no page errors.
- Initial frontend test setup lacked esbuild/Markdown dependencies. Existing dependency directories were reused after both lockfiles matched; no dependency changes. The baseline bundle failed the new asset-content assertion; candidate bundle passed. First result retained.

## Release impact and unresolved effect evidence

Source digest inspection of all 85 existing combinations performed no builds or business calls.
All seven gate groups change: the existing generator import closure includes reference_view.py (103 Python files).
The direct Java business change is service-preparation/customer-manager. G03/G04 also package service-preparation, so their installed JAR contents change even without direct use of the new report helpers. G05 includes both changed modules. G01/G02/G06/G07 change through the shared generator import closure only. The wider gate invalidation is real and must not be bypassed as UI-only.
No production qualification, install admission or deployment has been performed for this candidate.

Real model output quality has NOT been established by deterministic replies. Before release, compare baseline and candidate with identical installed method bytes, frozen facts, goal and model settings. Record first responses, not best-of retries.
Required review: goal-specific trigger; assets/holdings/risk/date when supplied; concrete evidence-grounded questions; conditional next step; explicit missing data; no invented policy/transaction/verified claim. Record token usage and latency, since the token ceiling changes from 900 to 3200.
The employee-agent comparison remains indicative only: exact installed SOP, policy context, model settings and complete inputs were not established equal.
Old records are unchanged; old Agent Locks are not upgraded automatically. UI can read legacy reports without adding derived facts to them.

## 本轮结论

确有我们自身实现造成的信息损失，不只是开发者的 SOP 问题：生成提示过度收缩业务内容，数值判断使用字符串包含，前端遗漏已取得的资产与持仓。
本候选保持原 SOP、原四类证据字段和权限边界，调整报告生成约束，增加有明确计算口径的快照比例并补齐事实展示；不复制岗位智能体的整套应用。
必需参考材料原本已被 Builder 合并入安装文件，本轮实际 SDK 请求验证了这一点，不能再称其普遍未加载。
代码回归、真实 SDK 的本地模拟响应、实际 JAR 加载以及合成页面均通过；这些不是实际模型质量或普通 SSO 验收。
上线前尚需同 SOP/事实/任务的真实模型对照、新资格及安装准入。不能以关闭校验、挑选最好的一次回答或把旧资格套在新 JAR 上代替。
