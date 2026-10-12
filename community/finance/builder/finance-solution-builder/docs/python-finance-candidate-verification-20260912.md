# Native Python Balance Candidate Verification

Date: 2026-09-12. Scope: isolated review only, no stable Shanghai deployment.

## Fixed implementation

- Builder upstream at start: `b26496b93ae726941ac7e186de9aff11830cd88f`.
- Integrated review baseline: `ccb50d0575388f1c011620aa3c51c7049d574e3e`.
- Generated and tested implementation: `40de3be151874c0e6f66199cfe36b2305735a92c`.
- Harness main reused unchanged: `c3801e2df973b68bc11e80bde35577f4d23ccc7d`.
- Runtime: native OpenJiuwen Python 0.1.18; Java bridge uses the locked existing
  Java dependencies and actual Harness Assembler, not a second Java model loop.
- Upstream finance advanced during this work to `e06740a2`. That later feature
  development is not silently included or described as revalidated here.

Subsequent documentation and verification-script changes do not change the
generated runtime implementation. Review the Python increment against `ccb50d05`,
not the whole inherited Studio/Java work as though it were newly authored here.

## Results

| Verification | Observed result |
| --- | --- |
| Builder suite | 595 passed; Python/Java selection and frozen-start compatibility included |
| Harness Maven install | 218 tests, 211 passed, 7 explicit live Smoke skips, zero failures/errors |
| Harness contracts | 13 schemas, 92 fixtures/assets/state probes passed |
| Generated Java bridge/template tests | 18 passed |
| Native Backend real lifecycle | 14 checks passed: assemble binding, build, start, health, invoke, evidence, stop/restart, old handle rejection |
| Python controlled Host | 23 checks passed: native multi-round context, source facts, display, isolation, revocation, model failure and log hygiene |
| Private pipe | 10 checks passed: force-finish zero model HTTP, tool removal, invalid selector, child death, old generation, bad/partial/oversized frames, hung-child reaping |
| Page generation | Five checks passed, including seven actual build stages and owned Python startup |
| Actual model page experience | Five checks passed: session, two rounds, bank source cards, no browser errors |
| Actual model + full simulator | Seven checks passed; one business GET, two model HTTP calls; revocation added neither model requests nor business reads |
| Java existing combinations | Seven generated combinations passed, zero business writes, history reads did not reexecute |
| Relocated offline bundle | Fresh venv installed without package-index access; 23 controlled Host checks passed after moving to another directory |

The actual configured model was `deepseek-v4-flash`; banking data was synthetic
data from the full non-ai-banking simulator. The independent counter separates
session introspection/login/logout from business reads. No real bank IAM or
customer authorization is claimed. The delayed-source revocation race uses a
controlled model endpoint so zero subsequent dispatch is directly observable.

## Fixed artifacts

Page build `agent-fb84e37e908e4634` reports Python 0.1.18 and balance only:

- Service Lock SHA256: `8af9f50e5ccb7b6d7d80f3106795b302ab524c22caab85c4f8f038cecbd030fd`.
- Native Backend artifact Lock: `99c908ad2a074b6eabba8f58b29c72b59cd97b26a48340871a966714078b7c93`.
- Offline archive SHA256: `9d57829c74ef1b383104c62821f6af2cb4ce1c1723d8e5f5b7bc65c593716771`.
- Archive: 325,892,118 bytes; installed original Candidate approximately 1.2 GiB.
- 167 hash-locked wheels; 111 JAR files including the generated host and five
  Harness JARs. The dependency bundle is not size-optimized.
- One cached Backend build measured 105,183 ms. No speedup ratio or P95 claim.

The loopback reviewer uses port 18438; login material stays in its private
reviewer directory. Old port 18428 and the stable Shanghai deployment are untouched.
Only newly generated Python Candidates use this implementation.

## Problems found and fixed

1. Frozen startup normalization had still required Java; it now preserves the
   selected Python runtime and still rejects unsupported compositions.
2. SDK single-agent cleanup indirectly imported optional team dependencies;
   cleanup now uses public single-agent Checkpointer and ContextEngine APIs.
3. The new Host lacked the standard financial display payload; it now reuses
   the Java execution bridge, evidence receipt and DisplayProjection.
4. pip RECORD hashes vary with relocated entrypoint paths. Only that installer
   record and bytecode caches are excluded from installed-file comparison;
   wheel archives and executed package code remain checked. A fresh-directory
   execution regression confirms the fix.
5. The timeout probe initially also limited cold startup to one second. It now
   waits for a healthy bridge before injecting the stopped-child fault.

An earlier Java combination run rejected a source change during its build. Its
partial result was retained; the complete seven-combination run was then repeated
with a fixed source. Earlier failed artifacts are not final acceptance evidence.

## Evidence and limits

The private review bundle contains `builder-release.xml`, `backend-release/checks.json`,
`controlled-release/checks.json`, `pipe-release-fixed/checks.json`,
`browser-build-release/report.json`, `live-page-release/`,
`live-boundaries-release/summary.json`, `java-seven-release/report.json`, and
`portable-release-checks/checks.json`. Build logs and per-file hashes remain with
each Candidate. No credentials are placed in this repository.

Not accepted: stable release, upstream maintainer acceptance, independent human
onboarding, production IAM, arbitrary Python capabilities/Skills, cross-platform
wheels, pure-Python Harness Core, model-body snapshots, token streaming, or
Python/Java clock subtraction. This is one configured customer environment with
isolated native sessions, not a general multi-tenant identity service.

The local UI is an inspection entry, not a new central Harness service. Stop only
its owned Host/bridge processes; retain the fixed artifact and logs for review.
There is no rollback action on the stable site because it was not changed.
