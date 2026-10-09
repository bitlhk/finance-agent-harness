# Enterprise Credit Material Audit MVP Migration

This candidate migrates selected reference assets into independent Finance Harness assets. It does
not merge either source branch and it has no runtime dependency on Employee Agent.

## Fixed sources

| Source | Fixed commit | License | Use |
| --- | --- | --- | --- |
| Finance Harness `Smart-Audit` | `afb5e05` | Same repository provenance | Deterministic enterprise evaluator, capability contract shape, result boundaries |
| Employee Agent reference role pack | `0503bab254d52ac496bcf5fd51e23532aee975fe` | MIT | Evidence matrix, comparability rules, current-rule handling, minimum supplementation guidance and synthetic cases |

The Employee Agent MIT notice is retained in `THIRD_PARTY_NOTICES.md`. Application instances,
Adoption state, internal tokens, databases and write tools are deliberately excluded.

## Migration map

| Source file | Target asset | Directly reused | Adaptation | Acceptance |
| --- | --- | --- | --- | --- |
| `providers/smart-audit/.../EnterpriseCreditDeterministicEvaluator.java` | `providers/enterprise-credit-material-audit` | BigDecimal balance equation and advisory-only boundary | Preserve material identity, unit, currency, period, source locator and distinct missing/not-comparable outcomes | Balanced, unbalanced, missing and unit mismatch cases |
| `capability-packs/smart-audit-enterprise-credit-audit/pack.yaml` | two capability packs | Runtime-neutral QUERY contract pattern | Split by independently reusable result and replace free-form material content with a bounded material-set contract | Pack/schema/provider parity tests |
| `domain-packs/smart-audit/.../financial-report` | enterprise material review method | Human-review-only language | Narrow to the two MVP checks and prohibit approval, authenticity and fraud conclusions | Method boundary tests and model explanation cases |
| `skills/smart-audit-assistant/SKILL.md` | enterprise material review method | Evidence matrix and fact/judgement separation | Remove EA-specific role, workspace, MCP and write-tool dependencies | Prompt-injection and unsupported-decision tests |
| `references/financial-consistency-review.md` | method reference | Subject/period/currency/basis comparability and difference calculation | Limit to balance equation and application/contract amount checks | Comparable and non-comparable matrix |
| `references/material-and-evidence-review.md` | method reference | Evidence status and minimum supplementation | Use JSON Pointer or text line locators; never invent pages | Locator and missing-field cases |
| `smart-audit-policy.ts` | demo rule asset and provider validation | Current-versus-expired rule qualification | Rule version comes from the locked request; no risk levels or approval decision | Unknown, expired and active demo rule cases |
| role-pack eval and demo materials | frozen synthetic conformance suite | Normal, missing, mismatch and boundary ideas | Replace application-specific IDs and writes with isolated material sets and review versions | 26 deterministic cases plus host lifecycle tests |

## Asset boundaries

- `enterprise.financial-statement.verify` checks only `assets = liabilities + equity` using explicit
  decimal precision and unit metadata.
- `enterprise.application-contract.verify` compares subject and requested/use-contract amount only
  when subject, period, currency and business basis are comparable.
- The Provider computes findings. The method explains saved findings and cannot change them.
- Review decisions are human-authored version records, not Provider or model decisions.
- Demo rules are labelled `DEMO`; they are not represented as an approved bank policy.

