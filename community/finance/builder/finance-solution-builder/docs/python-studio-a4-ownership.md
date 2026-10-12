# A4 responsibility and reuse review

Scope: integrated Studio prototype; not a new SDK or formal FEAT-010 acceptance.
Reviewed implementation: Builder 675d586d, Harness 1ec961b. The later pipe verifier
change supplies trusted model fixture metadata; it does not change generated code.

| Component | Actual owner and responsibility | Reuse and limitation |
| --- | --- | --- |
| Python `ClientSnapshots` | Generated Runtime adapter: native client collector, call/span binding | Template code, not a separately distributed Python Harness SDK |
| Python `freeze` | Generic synchronous detached allowlist/redaction before IPC | No bank outcome, permission or financial arithmetic; bounded projection may omit bodies |
| `PythonFinanceBridge` | Generated Host: owned IPC, current session, installation plan and observation binding | Calls Harness `ReadOnlyInstallation.query`; no Python reimplementation of financial contracts |
| `ModelSnapshots` | Generated Host: capture policy, references, bounded asynchronous writer | Java and Python use the same receiver/storage implementation; not yet a separately published Host storage library |
| `SnapshotStorage`, `ExecutionArchive` | Single-host content storage and append-only observation records | Shared by both generated runtimes through Java; single-host lifecycle/quotas are not distributed durability |
| Harness Core observation / SPI | Versioned events, requests/results, access wrapper and Provider observation | Reused dependency JARs, not copied into the Python template |
| Harness Provider / wealth module | Source HTTP attempts, business results and financial fact mapping | Bank business success and missing facts originate here, not in Builder or the model |
| `BankAccess`, `ReadOnlyProviders` | Simulator-specific trusted Host wiring | Local provisioned policy plus source session introspection; not a bank-wide authorization engine |
| `ExecutionHistory` and browser | Owner-scoped read/projection of stored evidence | Viewing does not invoke model, bank or Skill |

## Important qualifications

The Host enforces current required/public/source scopes using a provisioned local
policy and verified simulator identity. It cannot claim production IAM acceptance.
`ReadOnlyProviders` contains the reference composition's source-scope wiring, not
an extensible universal policy catalogue. A new source must provide its own trusted
session/grant wiring; selecting a capability never creates a grant.

Bank HTTP status and CapabilityResult business status remain distinct. Runtime
return and model parsed response are not proof of a successful bank operation.
Python snapshots are CLIENT_REQUEST_PREPARED / PARSED_MODEL_RESPONSE with dispatch
NOT_OBSERVED. Capture permission and history-view permission are separate.

There is an existing presentation limitation: `browser/history-labels.js` contains
reference-interface and capability display labels. These do not decide authorization,
business success, routing or event outcomes; unknown operations receive a generic
label. Therefore this delivery does **not** claim all business names already come
from immutable Harness asset/Provider metadata. Moving those labels into versioned
descriptions and preserving old names is a follow-up, not silently completed here.
No new per-capability observation dictionary is added by A4.

The Python pre-IPC allowlist and Java storage projection intentionally both filter:
the first prevents raw mutable/secret content crossing IPC, the second protects the
shared writer's input. This is not duplicate financial policy. Changes need both
boundary regressions, not removal of one filter to reduce duplication.

## Handwritten Host entry

A Java Host can use Harness `OpenJiuwenSolutionAssembler.prepare`, the read-only
installation and `FinanceProvider` SPI directly. It supplies trusted invocation
defaults/grant refresh and Provider bindings. No Builder service is required.
For observation, provide an `ObservationRun` sink and `ObservedFinanceProvider`;
Provider HTTP instrumentation remains in the Provider. For model capture, use the
Integrator's `ModelSnapshotClient` receiver and explicitly implement capture/storage
and history access policies. Installing a Pack alone does not configure them.

The Python reference currently requires the owned Java bridge and its generated
Host storage/wiring. A handwritten Python Host must explicitly adopt that protocol,
lifecycle, session and collector contract and rerun the same isolation/revocation/
snapshot tests. It cannot obtain an independent Python Harness library by importing
`finance_python.snapshots`. Extracting reusable Host diagnostics can be considered
after a second actual consumer, without moving all template files into Core.

## Integration and PR lineage

MR154 merges finance df63b170 into A3 be5e7ae9, retaining upstream Codex construction
and optimization UI. PR147 targets the special review baseline; MR149/A1, MR151/A2
and MR152/A3 are stacked reviews. MR154 contains those commits and targets finance,
but does not itself establish upstream acceptance or close those reviews.
Harness PR15 is separately merged as 1ec961b. No accepted Base contract, SDK source,
other Builder module or FEAT completion status is changed by this integration.
