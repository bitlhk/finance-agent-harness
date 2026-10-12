# Python Finance Candidate V0.1

Status: isolated native balance Candidate validated; no stable deployment or upstream acceptance.
See [fixed-artifact verification](python-finance-candidate-verification-20260912.md).

## Scope

The native Python 0.1.18 ReActAgent and Runner drive the model/tool loop. An owned
Java subprocess reuses BankAccess, Core, BoundFinanceProvider and the reference
NonAiBankingProvider. No SDK source is modified. The bridge performs an actual
Assembler installation check at session creation and stores its installation
Lock; Python separately registers the selected native LocalFunction.

Only the local CUSTOMER balance query is enabled. Wealth, cashflow, custom method
extensions and TASK operations are rejected for Python before generation. Java
combinations remain on their existing implementation. The generated application
does not silently switch runtimes on missing Python dependencies.

## Boundaries

- Private bounded stdin/stdout JSON frames carry generation, request and session
  identifiers. No HTTP bridge, arbitrary route, principal setter or scope setter.
- Java owns simulator sessions, identity mapping and current grants. A Pack's
  required scopes do not become actual grants.
- Current authority is checked before each native model and tool invocation,
  after bank results and before output. The LLM transform callback provides a
  second blocking boundary beyond agent callbacks. Closure prevents context reuse.
- Model credentials remain in the Python host; the Java child's environment
  excludes them. Error responses are fixed codes, and SDK body logging is disabled
  before any customer input.
- Bridge death/invalid frames/timeouts fail closed; no automatic retry or replay.
  The Host closes only its owned bridge process. Studio retains its existing
  process identity and instance-generation checks.
- Java archive spans are receive windows including pipe and authorization work.
  They are not Python model-client timings. Python totals use an independent
  monotonic clock. SDK callbacks do not prove HTTP dispatch. Body snapshots are
  explicitly unsupported for this candidate version.

## Developer Entry

Set `FH_PYTHON_EXECUTABLE` to a Python 3.11-3.13 interpreter with venv support.
The Builder process itself keeps its existing dependencies. Choose OpenJiuwen
Python plus Java financial bridge and only the account module in Studio.
Existing server-side model and simulator connections are reused, not embedded
in generated files. The direct recipe helper is
`finance_solution_builder.python_service.python_solution`.

`OpenJiuwenPythonBackend` implements the CandidateBackend lifecycle; its local
service generator is also the Studio entry. Start/health/invoke/stop reuse the
existing Jobs manager rather than a second unowned process registry. The v0.1
managed delivery layout is `<workspace>/workbench/<candidateId>/candidate`.
Assembly input digest is SHA256 of canonical compact sorted-key Solution JSON.

Java compilation and Harness regression precede a separately created Python
environment. Downloaded wheels, package versions and hashes are recorded;
installation uses `--no-index --require-hashes`. The wheel bundle is specific to
the build platform. `PYTHON-README.md` in the generated artifact gives independent
start/stop steps. It does not need the Builder or Harness source directories.
Installed site-packages files are checked separately from the wheel archives.
Bytecode caches and pip's relocatable `.dist-info/RECORD` are excluded from that
installed-file comparison; package code, data and other metadata remain hashed.
This detects package drift, not a hostile host filesystem or interpreter change.

## Verification

`engineering/verify_python_service.py --candidate ... --output ...` runs an
explicit controlled HTTP model, not a fallback for an unavailable real model.
It checks native two-round context, bank facts entering the next model request,
session isolation, delayed bank response with revocation, insufficient grants,
zero business writes and no sensitive SDK body logs.

From the Finance Solution Builder directory, with its normal dependencies installed:

```sh
python engineering/verify_python_backend.py --harness /path/to/finance-harness \
  --output /tmp/python-backend-check --python /path/to/python3.11
python engineering/verify_python_service.py \
  --candidate /tmp/python-backend-check/workbench/agent-python-backend-final/candidate \
  --output /tmp/python-host-check
/tmp/python-backend-check/workbench/agent-python-backend-final/candidate/.venv/bin/python \
  engineering/verify_python_pipe.py \
  --candidate /tmp/python-backend-check/workbench/agent-python-backend-final/candidate \
  --output /tmp/python-pipe-check
```

Use a new output directory. The Backend script actually builds, starts, invokes,
collects evidence, stops and restarts the native artifact. These scripts use
synthetic bank/model HTTP services explicitly. Real-model configuration is never
silently replaced by those services in the workbench.

For independent delivery, unpack the artifact on the same Linux architecture and
Python minor version, run `python3.11 prepare_python.py` once, then supply the
server-owned model/bank/policy environment described in `PYTHON-README.md` and run
`python3.11 run.py`. Keep the wheel bundle; do not copy an existing virtualenv to
another path. The offline package does not include credentials or customer grants.

Keep controlled timing tests, actual configured-model tests, bridge protocol
faults, Studio browser validation and independent human use as separate results.
No real bank IAM, cross-platform Python wheel portability, model snapshot capture,
or independent human onboarding has been accepted by these tests.
