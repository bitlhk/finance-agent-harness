# Java 0.1.15 qualification checkpoint

Status: PARTIAL, isolated candidate only. No deployment or stable-instance upgrade.

## Fixed inputs

| Component | Baseline / candidate |
| --- | --- |
| Harness main inspected | 9d8fb89683cdc60da8167f95294237104030d01b |
| History development baseline | faff145 (implementation a7b0b14) |
| Harness seven-composition candidate | 4ac3a76470d8d66b424543b3cdb3c279296fe52e |
| Builder candidate | 37968fcef6b21576e4756f608ff6644801f01f07 |
| Builder baseline | 563769f6d8c76cbe49d83cc00648bb44d0a00459 |
| Last inspected deployment | Harness a7b0b14; Builder 76b9c1a; Java Runtime 0.1.13 |

Both candidate worktrees use `feat/runtime-015-qualification`. They depend on
the unmerged execution-history work, not just main. Builder's CLI candidate
uses 0.1.15; its UI/catalog has deliberately not been migrated or deployed.
Runtime artifact hashes are in `engineering/runtime-qualification-artifacts.json`.

## A: completed and outstanding gates

| Check | Observed result | Scope / remaining limit |
| --- | --- | --- |
| Cached SDK artifacts | Both pinned SHA-256 values match | Not a floating latest-version check |
| Baseline full Java verify | 207 tests, 200 passed, 7 explicit smoke skips | Java 0.1.13 |
| Candidate full verify during generation | 211 tests, 204 passed, 7 explicit smoke skips | Before the subsequent extra disconnected-client test |
| Schemas and probes | 13 schemas, 92 probes passed | Venv interpreter; initial system interpreter lacked referencing |
| Regenerated compositions | All seven passed, 16 capability calls, zero business writes | Three units / four capabilities, controlled endpoints |
| Old/new history fault suite | 51 checks each passed | CONTROLLED_HTTP_AND_MODEL; realModel=NOT_RUN |
| Runner invoke and stream | Normal HTTP count 1; force-finish HTTP count 0 | Simple text Runner probes, not a streaming tool-chain qualification |
| Native request callback | Invoke/stream request JSON matches controlled receiver; 500 case captured | Client request preparation, not send acknowledgement |
| Follow-up native probe | Three tests passed, including disconnected endpoint and tool-result history | Supplementary test-only change after candidate generation |
| Streaming model-tool-model, exceptions and revocation | NOT_RUN as a dedicated Runner streaming matrix | Required before declaring the entire requested A matrix complete |
| SDK-internal retry and concurrent snapshot correlation | NOT_VERIFIED | Callback counts cannot stand in for transport attempts |
| Real model plus simulated bank on 0.1.15 | NOT_RUN | Controlled results do not establish model quality |

The initial version-only build correctly failed strict runtime reference checks.
Candidate input references were migrated; mismatch validation was not relaxed.

In both Runner modes a force-finished model stage can emit AFTER_MODEL_CALL
without model HTTP. In the observed 0.1.15 streaming force-finish path the SDK
retried an empty stream and fell back to invoke: four before/after callbacks,
zero model HTTP requests. These are callback observations, not four sends.

The supplementary disconnected-client test captures a request map even when no
model endpoint is listening. The native hook must therefore be classified as
CLIENT_REQUEST_PREPARED, never automatically as HTTP_SENT.

## B: snapshot gate

Native `withRequestTraceCallback` is available and tested without reflection or
SDK edits. Historical messages, tool definitions and synthetic tool results are
included in the tested client map. This is not a completed body snapshot feature.
Existing history archives contain timing and version evidence, not complete
model request/response bodies.

Pending implementation and acceptance: immutable allowlisted/redacted capture,
parsed-response scope, independent capture/view permissions, truncation and
failure flags, immutable business labels, storage quotas and overhead comparison.
Do not deploy a snapshot collector or enable raw bodies on the strength of the
current probe. No hidden reasoning or secret collection is authorized.

## C / D: Python discovery

Inspected Builder `origin/finance` b26496b93ae726941ac7e186de9aff11830cd88f.
Finance FEAT-010 remains deferred/backlog; no runnable financial Python candidate
was identified. Python Builder backend implementation is not proof that its
generated Agent uses Python. Other builders' Python dependencies are not evidence
for this Finance candidate.

Needed from the Builder owner: actual repository and commit, generated Python
Agent artifact, dependency lock, launch command and successful runtime identity
record. Python 0.1.18 wheel metadata is pinned, but has not been executed here.
D remains NOT_RUN until that runnable input exists; Java work does not wait on it.

## Evidence and reproduction

Local evidence root: `/home/ubuntu/outputs/runtime-015-qualification/`.

- `compositions-015/report.json`: seven cases and individual service-lock hashes.
- `history-013/report.json`, `history-015/report.json`: individual assertions and modes.
- `baseline-013.log`, `migrated-015.log`: actual build runs.
- `runner-013-observed.log`, `runner-015-observed.log`: callback/HTTP distinctions.
- `initial-015-failure/`: preserved strict-version failure evidence.

Reproduce the supplementary probe from the Harness candidate root:

```sh
mvn -pl integrations/openjiuwen-java -am -Dtest=NativeModelRequestTraceTest -Dsurefire.failIfNoSpecifiedTests=false test
python3 engineering/verify_runtime_artifacts.py
```

Builder's `docs/runtime-015-qualification.md` contains candidate-generation and
history-suite commands. Test-only changes after 4ac3a76 do not rewrite the seven
existing candidate locks; those records continue to identify the actual build.

No real-day retention, human onboarding, Python runtime, complete snapshots,
or deployment acceptance is claimed by this checkpoint.
