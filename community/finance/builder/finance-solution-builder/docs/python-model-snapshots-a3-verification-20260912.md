# A3 fixed-artifact validation (2026-09-12)

## Revisions

Harness stays `0289de7cabe2e4c0e41da61a9ea768ed32df4c7a`. Builder starts at A2
`e7341e90c4aee9c3adb4593cbbe16e5caa68acc2`. Runtime implementation is `a8a19f42`;
reader/UI/verification follow-up is `59736d8f`; final generated template guide is
`c1c24f2ec22ec8b9f906502c07def807ddadc099`. The latter is the clean source used for the
final 14 CLI artifacts and page artifact. Later delivery documentation does not change those files.

Evidence root: `/home/ubuntu/outputs/python-model-snapshots-v01`. Private reviewer configuration,
browser cookies and login URLs are not repository assets and must not be publicly distributed.

## Actual checks

| Check | Result / evidence |
| --- | --- |
| Native interface probe | Prepared requests, tool schemas and parsed responses compared with controlled endpoint; blocked transform caused zero HTTP; `interface-probe.json` |
| Builder full suite | 621 passed, no failures; `builder-final.xml` |
| Seven combinations | 14 generated Java/Python candidates, all controlled checks passed; `matrix-checks.json` |
| Shared semantics | 48 corresponding business outcomes passed; `business-comparisons.json` |
| Final Python snapshots | 144 assertions, 16 ordinary bodies plus actual long-context truncation and restart reads; `final-controlled/report.json` |
| Final Java shared storage | 142 assertions; `final-java-fixed-path/report.json`; four-tool snapshot/long-context/restart validation |
| Python negative boundary suite | 83 passed; `final-faults-fixed-path/checks.json`; disabled/ungranted capture, distinct view grant, cross-owner, isolation, quotas, storage fault, model error, hidden reasoning, invalid tools, business failure and late revocation |
| Real model / full synthetic bank | 20 checks, two cashflow rounds, provenance and revocation; `live/report.json` |
| Actual page build and history | Python three-unit artifact `agent-fe8e00584cbc465a`; two real-model turns; 4 browser assertions and desktop/mobile screenshots in `reviewer/` |
| Stopped-page history | 17 checks, 8 bodies read after owned Agent stop, anonymous denial, unchanged historical JSON, four old reviewers healthy; `reviewer/final-history-checks.json` |
| Locked artifacts | 14 CLI artifacts / 3,563 locked files, 12 archive hashes; page 359 locked files separately verified; `artifact-manifest.json` and `final-artifact-audit.json` |

The seed Java build also reran the unchanged Harness suite: 223 tests (216 passed, seven explicit
live smoke skips), 13 schemas / 92 probes. This is not a new production authorization test.
All business HTTP calls in the controlled and live-model tests use synthetic bank data; no transfer
or banking write was enabled. Native SDK/runtime drives execution in both controlled and real-model
checks; a controlled HTTP model is not counted as actual-model quality evidence.

Initial `faults-v3` (83 checks) and `controlled-v2` (144) used the earlier a8a19f42 implementation.
They are retained as earlier evidence, not substituted for fixed final artifact checks. An overlapping
general verifier collided on an instance file and was not accepted. The first final fault/Java reruns
loaded an old installed history-reader package: snapshot bodies were written but its reader lacked
snapshot metadata. Those failures remain in `final-faults` / `final-java-snapshots`. Corrected runs
explicitly set this checkout's PYTHONPATH; no runtime code was changed to hide the failure.

## Observed cost, not a performance qualification

`overhead/report.json` used the final Python combined Lock, controlled model/bank, 12 measured
fresh sessions per mode plus one excluded warmup. Each request made two model calls and one
business GET. Client monotonic round-trip and Python host duration were reported separately.

| Mode | Median client round-trip | Stored bytes including warmup |
| --- | ---: | ---: |
| Observation off | 69.814 ms | 0 |
| Trace only | 227.967 ms | 1,485,925 |
| Trace + body snapshots | 245.775 ms | 1,782,354 |

The matrix build was running concurrently and modes ran sequentially, so these differences are
not a controlled causal attribution of individual costs. They demonstrate nonzero cost in this
environment, not lightweight operation or production P95. The existing archive synchronously
forces event writes (`FileChannel.force(false)`); the Python bridge adds protocol round trips and
snapshot projections/metadata. These are plausible contributors visible in code, not measured
percentages or a proven dominant cause. Dominant cost remains unprofiled. No fsync, authorization
or locking behavior was weakened for speed, and no optimization expansion is part of this delivery.

## Delivery boundary

Reviewer: `http://127.0.0.1:18468/`, with private login file under
`reviewer/private/login-url.txt`. The page-created Agent is stopped after acceptance; history remains
readable and a reviewer can explicitly start it. Existing 18458/18448/18438/18428 and stable services
are preserved. Combination 1-6 artifacts are archived without reproducible virtual environments;
hash-locked wheels and `prepare_python.py` remain. This did not delete old user projects or artifacts.

Capture labels remain CLIENT_REQUEST_PREPARED / PARSED_MODEL_RESPONSE with dispatch NOT_OBSERVED.
Actual endpoint comparisons cannot establish all possible HTTP sends or hidden client retries.
Production authorization, real-bank equivalence, independent human use, statistical quality,
multi-process durability, upstream acceptance and stable deployment remain pending/not run.
No merges or A4 consolidation are included. Review the stacked A3 change independently before A4.
