# Standards and Live Terminal Release: 2026-09-10

## Scope and Versions

This release adds the scoped industry-reference page, three compatible service labels,
and actual mobile/desktop/ATM generated application surfaces. It does not enable any
new business tool. The 26-module directory still has only the existing balance and
wealth-fact capabilities available. Six discovery groups remain candidates.

| Component | Actual version |
| --- | --- |
| Builder implementation and deployed checkout | `e0ce39977edf9b6643d11412e03f32ea2825172d` |
| Harness used by the deployed stack | `b55d0722cac2905388f57dc4a17b85d346a0e470` |
| Runtime | OpenJiuwen Java `0.1.13`, unchanged |
| Synthetic bank | Existing full simulator; unchanged JAR SHA-256 `e40f43df54b4f54b86cf552554c3e4cd65728073a6fa71cc71cab2108681807e` |
| Actual model smoke | Server-configured `model-ea-002`, `deepseek-v4-flash` |
| Public developer guide | V0.7, separate scoped documentation patch |

The Builder branch is `feat/studio-standards-live-terminals-v01`, based on `be8d335d`.
Implementation commits are `fba0a593`, `f4bbae2d`, `a3feb281`, and `e0ce3997`. A later documentation-only
commit does not change these frozen runtime assets. Pushing the branch is not upstream
maintainer acceptance or a merged MR.

## Separate Verification Results

| Verification | Observed result and limitation |
| --- | --- |
| Builder and Base tests | 502 passed, 2 skipped, locally and in the new server release |
| Standards/label focused tests | 34 passed |
| Standards page browser checks | 115 passed, local and simulated server footer, 1440/1024/390/320 |
| Existing Builder preview | 188 passed; not a substitute for generated application tests |
| Generated Java applications | Mobile 58, desktop 61, ATM 60 checks passed; repeated after the label fix, controlled HTTP model and bank |
| Shared card renderer | 23 checks passed; seven added label/escaping/evidence assertions, old implementation fails the new coverage-label assertion |
| Actual online model plus full simulator | Three new applications, two completed financial turns each; balance then wealth/risk/gaps cards, six AG-UI RUN_FINISHED results |
| Online generated UI | UI asset hashes match the new source assets; 1440/390 screenshots, end clears facts, owned instances stopped |
| Final labeled online applications | 64 checks passed in one run; three newly built applications, six actual-model turns, Chinese gap labels and raw evidence keys verified |
| Public guide | Local 55 and anonymous online 56 checks passed; four exact reviewed links; zero automatic subresource requests |
| Guide privacy policy | Exact-link positive and suffix/unreviewed URL, internal address/path and secret-pattern negatives passed |
| Professional quality | Not passed or scored; retained bad cases below |
| Real bank authorization, physical phone/ATM, speech call, human independent use | Not run |

The controlled tests exercise fullscreen, small-screen layout, source-panel behavior,
ATM home/end/restart, authorization revocation and cancellation boundaries. Their bank
write count is zero. The online smoke is a separate actual-model/synthetic-bank check;
it is not a fresh online write-counter or adversarial timing acceptance.

Online acceptance applications were created through the real build page:

| Surface | Build ID |
| --- | --- |
| Mobile | `agent-4dfe3054524048f5` |
| Desktop | `agent-2518137b82cc4229` |
| ATM | `agent-57db290419834870` |

All are retained as stopped acceptance artifacts, not left consuming instance slots.
Their selected tool set is exactly `account.balance.query` and `wealth.facts.describe`.
No EA application or old mobile-banking application participated in these generated-host runs.

The above three applications belong to the initial `a3feb281` online run. A subsequent
review added three known gap labels: asset coverage, asset scope, and missing-source
prefix. Original field keys remain in AG-UI evidence and escaped `data-field` attributes;
unknown fields are not guessed or relabeled. No fact, status, authorization, model prompt
or capability contract changes. Human-readable timestamp formatting is deferred.
New `e0ce3997` applications are generated separately; old applications are not patched.
Local regenerated Locks honestly record a dirty worktree because this release report
and its README link were in progress; the only uncommitted files were documentation.
The deployed checkout and its generated application source identity are clean.

Final label-release acceptance applications:

| Surface | New build ID |
| --- | --- |
| Mobile | `agent-fafb82e1edd44dd5` |
| Desktop | `agent-dea33c14afcd44ce` |
| ATM | `agent-079fdfcfc75243b9` |

Each was generated from the deployed `e0ce3997` checkout, completed two model turns,
verified rendered business labels plus unchanged raw AG-UI keys, and was stopped.
This final six-turn smoke had no failing attempt; earlier failures remain in the record.

## Failed Attempts and Open Quality Issues

1. Initial mobile smoke timed out waiting for an assistant answer. The selector matches
   the actual UI. That attempt did not capture the SSE error body; instance logs are
   overwritten on restart, so its precise cause remains undetermined. Do not relabel
   the original failed attempt as a pass or infer its cause from a later attempt.
2. The next mobile attempt completed two actual answers, but Playwright could not read
   the released streaming response body. The verifier now captures a `fetch` response
   clone; this changes evidence collection only. The following mobile run passed.
3. A desktop attempt received model-generated `accountId={}`. Runtime schema validation
   rejected it because a string is required, emitted `MODEL_OR_RUNTIME_FAILED`, and the
   UI cleared the session. The same immutable application later passed two turns without
   relaxing validation. This is a retained model tool-argument bad case, not stable quality.
4. An actual answer called source-reported assets "declared assets" and interpreted R3
   as medium risk despite an unspecified rating scheme. Deterministic cards still show
   source scope and the missing scheme. This professional-output bad case is not fixed
   by the terminal UI work and must not be presented as financial-quality acceptance.

The smoke report's successful final attempt is therefore scoped to link/UI completion,
not a claim of zero failures across attempts. Tests now stop waiting as soon as the
captured stream has a RUN_ERROR, and retain the failing page and stream before stopping.

## Compatibility and Deployment

Deployment uses a new versioned checkout, backs up configuration, systemd drop-ins and
the complete build workspace, then switches the application symlink. The previous
checkout remains available for rollback. The new drop-in has a distinct name; an initial
name-collision guard prevented overwriting the existing speech-related drop-in.

Post-deployment comparison found all 9 old Agent records byte-semantically unchanged,
all 13 preexisting service Locks unchanged, and all 1366 present locked files matching
their declared hashes. Another 392 JAR paths in four historical method trial/preview
Locks were already absent in the pre-deployment backup; they are explicitly excluded
from verified-present counts. There was no new file loss. Speech/model/runtime config
and the prior speech drop-in are byte-for-byte unchanged. A process restart does not
preserve an active user conversation; old artifacts themselves were not rewritten.

The label-only follow-up took a second backup before switching from `a3feb281` to
`e0ce3997`. All 12 preceding Agent records and 16 Locks remain unchanged; 1807 present
locked files match, with the same 392 preexisting absent paths. No running instance
was allowed during this second switch. Server disk observation after backups: 82%
used, about 22 GB free; the two rollback archives use about 2.7 GB. Retention cleanup
is a separate operation and must not indiscriminately remove user artifacts.

The public guide is published independently and keeps the two existing embedded SVGs
byte-identical. Only four exact official URLs are allowed; modified suffixes or other
URLs remain rejected. Links load only after explicit navigation. No private source,
deployment configuration, or model credential is added to the public edition.

The Harness documentation workspace had preexisting uncommitted edits. This round
therefore supplies a five-file patch against the pre-edit working copies, plus before/
after hashes, without committing unrelated historical changes. This is not a claim
that the public guide has already landed in Harness Git history.

## Reproduction and Evidence

Use [standards reference tests](industry-standards-v01.md) and
[generated terminal tests](live-terminal-surfaces-v01.md) for repeatable offline entries.
Candidate binary reuse is explicit and validates source/template/Harness/JAR hashes.
Normal business answers and long controlled text have separate screenshots.

The restricted handoff directory `live-terminals-20260910/` contains:

- `frozen-candidates/` and `frozen-verification/`: three frozen applications and 179 controlled checks.
- `labeled-candidates/` and `labeled-verification/`: regenerated applications after the business-label fix, the same 179 checks rerun.
- `online/`: build records, six final actual-model SSE streams, screenshots, stopped-artifact checks and consolidated scope.
- `online-labels/`: final label-release applications, 64 checks, six additional actual-model SSE streams and screenshots.
- `online-first-failure/`, `online-second-failure/`, `online-third-attempt/`: retained pre-success evidence.
- `preserved-artifacts.json`: old Lock/file/record and private-config comparison results, no secret values.
- `public-guide-v07-e0ce3997/`: final exported guide/PDF, diagrams and local/online checks; entry wording follows scenario template, business module, business responsibility.
- `public-guide-v07-scoped.patch` and `public-guide-patch-manifest.json`: only this round's guide edits.
- `rollback.sh`, `rollback-label-release.sh`, release scripts and source bundles: backed-up deployment/rollback instructions.

Raw conversations contain synthetic facts only and remain restricted evidence, not
public documentation assets. Model tuning, true business authorization, upstream
acceptance and independent human onboarding remain separate work.
