# O1/O2 fixed-source controlled acceptance

Date: 2026-09-13. Candidate scope only; no merge or stable deployment.
Executable Harness: `81422084e985f1156f1d94333371a6d182d77002`.
Executable Builder: `e0a1bb02ec5241029c010819a5ff75ac0b4b4536`.
Subsequent report-only commits do not replace these actual generated-source identities.

## Outcome and boundaries

O1 requirements/admission and O2 component-owned historical descriptions have controlled
candidate acceptance. Neither is a production IAM, real-bank interchangeability, human
independent onboarding, performance or raw model HTTP certification. Collector qualification
is trusted operator assertion bound to source/runtime/dependency identities and evidence
bytes. Controlled test issuance is not human production approval. New versions require
matching qualification; an uploaded business asset cannot confer that trust.

Core remains runtime-neutral; Python uses the owned Java financial bridge, not an independent
Python Core. Business authorization order, SDK source and locking policy are unchanged.
Capability Pack names and Provider source-operation descriptions produce immutable display
snapshots. Both display and actual installation Lock hashes are linked per request.
History verifies the association; stale/new catalog values never relabel frozen records.
Legacy interpretation is explicit; missing new metadata remains UNAVAILABLE.

## Actual verification

Environment: Linux x86_64, Java21.0.12, Maven3.8.7; local test Python3.11.
Generated SDKs: Java0.1.15 / Python0.1.18, actual dependencies in each service lock.

| Check | Actual result |
| --- | --- |
| Harness reactor verify | 243 tests:236 passed,7 explicit live Smoke skips,0 failures/errors |
| Builder full pytest at e0a1bb02 | 712 passed,1 opt-in live Codex smoke skipped (FINANCE_BUILDER_RUN_CODEX_LIVE not enabled) |
| Newly generated Host template tests | 31 passed |
| Schema / existing probes | 17 definitions;92 existing fixture/asset/state probes |
| Shared additional cases | 7 display,6 requirement,8 collector cases;selection and3 unit declarations |
| Qualifying Candidates | Java41/Python58 controlled checks |
| Admitted seven combinations | Java41/17/19/24/29/34/36;Python58/28/30/37/42/49/51 (bits7,1,2,3,4,5,6) |
| Final artifact verification | All16 service locks and their listed files rehashed;212 per-request labels plus installation associations verified |
| Normalized business comparison | 48 Java/Python comparisons passed |
| Required history disabled | Both runtimes reject startup;0 business/model/write calls |
| Browser | 13 checks at1440x1000/390x1000;actual frozen labels and separate observation states;viewing history0 bank/model calls |
| Malicious display browser probe | 3 checks;HTML-looking metadata displayed as text,no script/image execution |
| GitHub CI at8142208 | Push and PR,Python3.10/3.12,all4 jobs passed |

Bit1=balance;bit2=wealth;bit4=cashflow(two capabilities). Model services are controlled
HTTP responders and bank facts are synthetic. Source read/write observations, revocation
while a source request is pending, scope/identity rejection, missing vs zero facts,
session isolation and metadata exclusion from model business results use existing
native-runtime acceptance paths. O1-only results were not substituted for this matrix.

Focused regressions also cover custom Provider names without a UI change, decorator
delegation without grant/business calls, malformed/duplicate metadata, label/installation
digest checks, later description versions, pre-assembly denial vs legacy, and label
write failure leaving the original business outcome intact. Shared Schema checks are
structural; duplicate-ID/route semantics have additional Java/history-reader checks.

Snapshot receipt projection does not verify body bytes or imply a request was sent.
Opening a body still requires its separate owner permission and digest validation.
No new performance experiment or zero-overhead claim. Extra bounded metadata I/O exists.

## Reproduce and inspect

1. Obtain the two fixed commits above and build Harness with `mvn -B -ntp verify install`.
2. Run `python engineering/verify_contracts.py` in Harness and
   `PYTHONPATH=src:../base/src python -m pytest -q` in the Builder module.
3. Generate Candidates with the existing `build_service` / `build_python_service`
   functions using `SolutionBuilder`, `solution_for` / `python_solution`. Test balance,
   wealth, cashflow and the four combinations; do not enable TASK tools.
4. Run `python engineering/verify_python_packages.py --candidate <candidate>
   --output <evidence> --harness <harness-root>` for each language/selection.
   Despite its historical file name, this verifier exercises both native hosts.
5. Follow the O2 qualification guide: review a qualifying run, obtain collector identities,
   protect the qualification file and inject its SHA-256 through trusted Host environment.
   Regenerate with `observation_requirements=<harness>/observation-packs/readonly/selection.json`.
   Repeat actual tests, disabled-history startup and frozen-label/installation hash checks.
6. Query history via owner-authenticated workbench API; inspect `display.state`,
   `observation`, source-operation versions and separate snapshot/body status.

Exact orchestration scripts, original logs, screenshots and hashes are retained at
`outputs/observation-requirements-o1/`: `qualify_o2_matrix.py`,
`o2-matrix-final/reports.json`, `o2-artifact-verification.json`,
`o2-matrix-final/business-comparisons.json`, `o2-browser-checks.json`,
`o2-display-browser.json`, `o2-disabled-start-checks.json`.
The accompanying artifact manifest records each Candidate's source and lock hash.

One first O2 build was deliberately stopped before acceptance to distinguish new
pre-assembly gaps from old records. A later runner exited143 during dependency installation
after11 completed Candidates; cause not established. Its partial build/logs were retained,
the installer exited, and the remaining5 were built at the same sources. Final verification
rehashes all16. Neither interruption was marked PASS. A first malicious browser probe chose
an uninvoked capability and timed out; corrected synthetic-response scope passed without
changing saved evidence. These are local test records, not production incidents.

## Review entry and pending work

Isolated workbench: http://127.0.0.1:18498/ (loopback, separate data/owner).
Login URL is in `outputs/observation-requirements-o1/reviewer-o2/private/login-url.txt`;
do not publish its token. Original reviewer18488 and stable services remain untouched.
The probe Agent was stopped after its terminal record; historical inspection stays available.

Harness PR16 remains draft and Builder MR159 WIP; maintainers have not merged this
iteration. Production authorization, real-model answer quality, independent human use,
dedicated overhead measurement and stable rollout remain unverified/out of scope.
