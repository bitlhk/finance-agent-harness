# Business Assets Phase 1 Candidate Verification

Date: 2026-09-13. Candidate only; no production deployment or merge.

This is the initial candidate record. The later UX correction and configured
published-version acceptance are in [the UX verification report](business-assets-ux-verification.md).

## Revisions

- Builder base: `7422164ced1fc04387a144407a975c1f4f915ab9` (`finance`).
- Builder implementation: `d22b67ec968af6b0c094db3d2a95cb717cd5b4ac`.
- Harness base: `21afb5c729bf612361a1f9cefad81c4be87500e8`.
- Harness candidate: `a7ca4abd9c51ef7fb014f872fa66240671b860e0`, PR 19.
- Builder review: MR 166, branch `feat/business-assets-phase1` into `finance`.
- Actual generated trial used Builder `5c4937456c31353b4542be6c2938699a9cbefcd6`.
  Later changes concern navigation, API validation and tests, not the generated
  Java service. Do not relabel the earlier generated artifact.

## Results

| Check | Actual result |
| --- | --- |
| Builder full pytest | 754 passed, 1 skipped |
| Harness Maven/Surefire | 243 tests, 236 passed, 7 skipped, no errors/failures |
| Harness contracts | 18 schemas, 97 fixture/asset/state probes passed |
| Harness PR CI | Python 3.10 and 3.12 Push/PR jobs passed |
| Browser final checks | 11 passed; desktop/mobile screenshots; no page errors |
| Generated Java method trial | Baseline and customized cashflow method, two cases each, passed |
| Published private method | Immutable version 1.0.0 created; reference returned to Create |
| Page build with published version | Not executed: no selected available model connection; button disabled |
| Live model quality / independent human use | Not run |

Python unit tests do not imply Python method-trial support. The actual method
trial uses OpenJiuwen Java 0.1.15 and controlled model/bank HTTP endpoints. Each
customized cashflow case observed one business read and two model requests;
the installed method was present, with zero business write attempts.
The trial installs temporary version `0.0.0`, not the subsequently published
`1.0.0` release.

Trial candidate: `agent-04886e122f98466b`.
Candidate Lock: `sha256:124352f3caf74c9acc3db234334ebb6c9dffddf8f379334ff6fc836c172eb91a`.
Trial method installation: `sha256:eadac6e684ac2dce8648694517cb12a140bced8ae51e2a8595b4c9e98cb0b15f`.

## Reproduction

Use the paired branches and commands in `business-assets-phase1.md`.
Environment: Linux, Python 3.11.15, JDK 21.0.12+8, Maven 3.9.16.
Builder: `PYTHONPATH=src:../base/src python -m pytest tests`.
Harness: `mvn install` with Java 21, then `python engineering/verify_contracts.py`.
UI: `npm ci --ignore-scripts` and `node browser/build-ui.mjs`.

Browser path: business assets -> cashflow -> customize -> save -> validate ->
Java controlled trial -> publish -> use in Create. Import preview cancellation,
legacy methods, directory navigation and mobile overflow were checked separately.
Seven combinations are directory/contract regressions, not live-model deployments.

Local evidence remains in `outputs/business-assets-phase1`: JUnit XML,
screenshots, trial reports and generated Locks. Private browser state and method
bodies are not committed.

## Preserved Failures and Pending Checks

The first generated cashflow trial failed because default prompts lacked a
month. The fix supplies explicit synthetic month `2026-08` and separate summary
and analysis cases. Browser navigation after selecting a published method first
retained the old editor; the subsequent regression passes. Earlier failures
remain recorded and are not overwritten as PASS.

The final optional page-build check stopped at model selection, before POSTing
a build. Configure an available server-side model connection, build published
1.0.0, and verify its new Lock before claiming that release ran as an Agent.
No credentials were invented and no gate was bypassed.

Maintainer acceptance, production release, real model quality and independent
human reproduction remain separate. Transfer PR18, generic package imports,
multi-method execution and online code editing are outside this change.
