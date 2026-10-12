# Component admission verification (partial increment)

Date: 2026-09-14. Not a deployment approval or completed extension mechanism.

## Fixed implementations

- Harness `b507f4d0c5977401d33889469c9d78763f5551b4`.
- Builder `32217f70` (prior admission wiring `8fbac486`). Subsequent documentation
  does not retroactively change generated source identities.
- Java Runtime 0.1.15; Python Runtime 0.1.18 with its existing Java Harness bridge.
- No change to bank implementation, SDK, production sessions or old Agent locks.

## Observed results

| Check | Result / scope |
| --- | --- |
| Harness reactor | 271 tests: 264 passed, 7 explicit real-service Smoke skipped |
| Builder full tests at 32217f70 | 817 passed, 1 skipped |
| Prior wiring 8fbac486 | 14 generated Java/Python compositions, 495 controlled checks, 48 business comparisons passed |
| Installation negative, prior code | Forged matching Runtime declarations were incorrectly accepted; other five cases behaved as expected |
| Installation probes, 32217f70 | Six passed with actual generated Java JARs; forged declarations rejected against loaded Runtime |
| Hardened 32217f70 matrix | 14 built and run; 495 controlled checks, 48 business comparisons passed |
| Python delegated Java installation negatives | Six passed via its actual ReadOnlyPlanExporter entry |
| Real model / real bank | Not run by this increment |
| Shared TASK / second business | Not implemented or accepted by this increment |
| Independent human onboarding | Not performed |
| Production deployment | Not performed |

All matrix model endpoints are controlled scripted HTTP services. They prove
native Runtime/Host/tool behavior and business projections, not model quality.
Bank data is synthetic. Preparation plans record their not-yet-registered status;
service checks separately exercise installed tools and current authorization.

The first matrix validator was started before the last Python build completed;
it correctly rejected the incomplete build. All builds completed before the full
rerun. The second matrix validator explicitly waits for all 14 BUILT reports.
Do not count that orchestration failure as a service regression or hide the attempt.

An additional probe initially used the Java top-level entry for a Python top-level
solution and was rejected before component validation. The verifier now selects
the existing delegated Java exporter for Python candidates. No production Host
code was changed for this correction; `installation-negative-python` preserves
the failed probe and `installation-negative-python-bridge` records the six passes.

## Reproduction

From the Builder project, using its documented JDK21, Maven and Python environment:

```sh
PYTHONPATH=src:../base/src python -m pytest tests -q
python engineering/verify_component_assembly.py --harness /path/to/harness
python engineering/verify_component_installation.py \
  --candidate /path/to/java-1/candidate --output /path/to/new-negative-results
python engineering/verify_python_a2_matrix.py \
  --matrix /path/to/seven-combination-builds \
  --output /path/to/new-service-results --harness /path/to/harness
```

The existing matrix entry expects `java-1` / `python-1` through `java-7` /
`python-7` build directories, each containing the build report and candidate.
Use the existing Java/Python readonly recipes for each nonempty combination of
balance, wealth, and the atomic two-tool cashflow unit. Its historical filename
does not make this an A1/A2 product acceptance; this is a query compatibility gate.

Implementation-machine evidence is under
`/home/ubuntu/outputs/capability-extension-v01`: `builder-wiring-final.xml`,
`installation-negative-before/results.json`, `installation-negative-after/results.json`,
`installation-negative-python-bridge/results.json`,
`query-matrix-01-verification-complete`, and `query-matrix-02-verification`.

## Runtime capability build matrix

For the current managed component directory, run the generic matrix gate before
publishing a Builder and Harness pair:

```bash
PYTHONPATH=src:../base/src python engineering/verify_runtime_capability_matrix.py \
  --harness /path/to/finance-agent-harness \
  --report /new/evidence/runtime-capability-matrix.json
```

This exhaustively resolves every non-empty component subset and proves that
components not declared for a Runtime are rejected. Add `--output` to build each
supported singleton and the maximal supported combination. `--runtime` can split
artifact builds by Runtime when qualification evidence differs.
Use `--host` when a Runtime has multiple declared Hosts and the artifact run must
match a specific qualified Host. The selected Host must support every component
in that run; the verifier never silently substitutes another Host.

Each artifact run must use qualification evidence produced for the exact Runtime
collector and generated Host template under test. A qualification from another
Runtime is not interchangeable merely because the required event names match.
The service identity must be able to read the qualification and its referenced
evidence before a build is queued; Java admission remains authoritative for
digests and proof semantics.

The existing environment contracts use different digest encodings: Host
observation qualification uses 64 lowercase hexadecimal characters, while the
AgentScope native qualification and admission service Lock use the `sha256:`
prefix. Pre-queue validation rejects a mismatched configured digest rather than
allowing an otherwise successful compile to fail at installation admission.
These paths are evidence locations, not public downloads. Requests for source or
artifacts must retain their locks and paired Harness/Builder identities.

Remaining Provider factories, legacy selection branches, shared confirmation UI,
durable task mounting and channel-preference business validation are release gates.
Do not expose transfer merely because a Pack declaration or the standalone pilot exists.
