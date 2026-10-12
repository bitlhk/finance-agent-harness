# Smart Audit one-upload demo design

## Goal

Turn the current text-only Smart Audit chat into a concise, demonstrable bank
credit-review workspace.  A reviewer selects one of the two existing task
scenarios, uploads one complete material bundle, and follows the AI assistant
through a controlled pre-credit-review workflow.

The feature remains advisory only: it records submitted material, identifies
gaps and review points, and produces an audit working paper.  It must never
approve, decline, price, disburse, or write back a credit decision.

## Scope and material source

The first release uses the repository's existing Smart Audit test fixtures as
two deliberately fictional, local demo bundles:

| Scenario | One upload bundle | Required categories |
| --- | --- | --- |
| Personal loan audit | `personal-loan-demo.json` | ID card, household register, marriage certificate, loan application form |
| Enterprise credit audit | `enterprise-credit-demo.json` | Balance sheet plus the categories required by the selected enterprise capability |

The browser accepts one JSON bundle in this release.  It validates the bundle
before any chat run, keeps the file in browser memory only, and submits its
material content to the existing governed capability.  PDF/image upload is
explicitly outside this release because the current governed service accepts
TEXT/JSON rather than OCR output.  The UI must say so rather than implying that
a binary file was reviewed.

## Demo flow

1. **Create review case**: select Personal loan audit or Enterprise credit
   audit.  Show the fixed case type and the material checklist.
2. **Receive one material bundle**: drag/drop or choose exactly one JSON file.
   The UI displays filename, material count, categories, and missing required
   categories before enabling review.
3. **Start AI review**: the user clicks one clearly labelled action.  The
   assistant sends the uploaded content, not hard-coded message content, to
   the governed runtime.
4. **AI working paper**: a case-orchestrator delegates only to the installed,
   governed review Subagents.  Progressive message cards show material
   registration, completeness check, specialist rule/cross-check execution,
   and a review summary.
5. **Human review handoff**: the summary has only evidence-backed facts,
   missing material and manual-review follow-ups.  It prominently labels the
   output as non-decisional.

This is a pre-credit-review workflow, not an approval workflow.  It maps to
the real operating sequence of case receipt, material collection, preliminary
review, verification/analysis, and manual review handoff.

## Interaction design

### Layout

- A compact left case rail shows the selected scenario, the five workflow
  stages, and material completeness.
- A compact **review team** rail exposes the actual Harness composition.  It
  shows the case orchestrator and the specialist Subagents admitted for the
  selected task.  Personal review exposes borrower qualification, income
  verification, asset/liability and repayment-capacity specialists; enterprise
  review exposes financial-report, financial-summary, transaction-analysis,
  transaction-report and due-diligence specialists.
- The center pane is a ChatGPT-like conversation.  User actions are compact
  bubbles; the assistant uses distinct cards for stage progress, final audit
  summary, and follow-up items.
- The composer is primarily the single-file upload and **Start review** action;
  a free-text follow-up remains available after the first review.

### Progressive output

The existing transport is Server-Sent Events but currently buffers the complete
answer into one `TEXT_MESSAGE_CONTENT` event.  It will instead emit:

1. a stage-start card for each governed phase;
2. several bounded text deltas for the assistant summary; and
3. one final evidence payload after the run completes.

The UI appends deltas to the active assistant bubble, maintains auto-scroll
unless the reviewer has scrolled away, and renders a typing cursor while the
run is active.  Each Subagent card becomes active only when its governed
capability is actually invoked; its event shows the permitted material
categories, tool status and the non-decision boundary.  The UI must not
represent a cosmetic role card as an independently executed agent or expose
hidden chain-of-thought.  Stage events describe only actual local workflow
milestones and observable capability results.

### Evidence disclosure

The working-paper summary is visible by default.  The following are collapsed
by default and expandable on demand:

- submitted material preview (category, format, size; content only after an
  explicit click);
- rule and tool execution details;
- raw structured evidence/response JSON.

No sensitive material is copied into a visible chat response by default.

## Data contract

The UI introduces a versioned client-side demo envelope:

```json
{
  "schemaVersion": "smart-audit-demo-materials.v1",
  "scenario": "personal-loan-audit",
  "materials": [
    {"category": "身份证", "declaredFormat": "TEXT", "content": "..."}
  ]
}
```

The service validates schema, scenario binding, allowed category, content type,
non-empty content, aggregate size, and exact single-upload semantics before it
creates the governed capability request.  Scenario/category authorization is
still enforced again by the Harness provider.  Uploaded material never grants
identity, scopes, tools, configuration, or decision authority.

## Implementation boundaries

- The Builder's agent-experience frontend owns the case rail, one-file upload,
  material-bundle validation, review-team/Subagent rendering, chat rendering
  and disclosure controls.
- The local readonly service owns request validation, material normalization,
  native conversation invocation, observed capability-to-Subagent mapping and
  SSE event order.
- The Harness Smart Audit provider remains the source of scenario rules,
  required materials, evidence status and non-decision guardrails.
- Existing DeepSeek configuration remains a runtime connection setting and is
  not placed in source, fixtures, logs or the UI.

## Acceptance checks

1. Both supplied demo bundles can be uploaded once and pass their appropriate
   scenario checks.
2. A mismatched, malformed, duplicate-category, or incomplete bundle is
   rejected or produces a visible material-gap state before review.
3. The actual uploaded JSON changes the governed capability request; no
   hard-coded demo message is used for the review body.
4. Browser automation observes multiple SSE deltas and progressive assistant
   rendering for both scenarios, with only actually invoked specialist
   Subagents marked complete.
5. Raw material and raw evidence are collapsed initially and can be expanded.
6. The final output includes manual-review wording and contains no credit
   approval, rejection, pricing, disbursement or write-back action.
7. The review-team rail lists only capability-backed Subagents for the selected
   scenario and identifies the case orchestrator as the sole coordinator.
