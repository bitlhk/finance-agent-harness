# Python Finance Candidate V0.1

This is a native OpenJiuwen Python 0.1.18 ReActAgent/Runner application with an
owned Java financial bridge. It is not a Python implementation of Harness Core.
The locked selection combines balance, wealth and the two-tool cashflow unit (seven finite
compositions). No transfer or other task tool exists. Published professional methods are
pinned to immutable versions and cannot expand the installed tool or authorization scope.
`installation-plan.json` records validated capabilities, schemas, methods and actual Java Provider
artifacts. Session open must reproduce that plan before Python registers its native tools.

## Prerequisites

Python 3.11-3.13, Java 21, and the same OS/architecture/Python ABI as the build.
The wheel bundle is platform-specific; rebuild on another platform. Maven and
Builder source are not required to run this delivered Candidate. Python 3.10
can run the Builder but cannot run this SDK.

On a fresh extracted artifact, run `python3 prepare_python.py`. This creates
`.venv` using only bundled, hash-locked wheels. Do not copy an existing virtual
environment between machines. The generated `python-dependencies.json` identifies
the actual SDK and transitive wheel versions and hashes. Java JARs are separately
listed in `service-lock.json`, with the Java Assembler reference in `javaBridge`.

## Start and Stop

The trusted host supplies `FH_HOST_TOKEN` (at least 32 characters), `FH_PORT`
(default 0, dynamically allocated), `FH_MODEL_URL`, `FH_MODEL_NAME`, `FH_MODEL_KEY`,
`FH_BANK_URL`, `FH_BANK_LOGIN`, `FH_BANK_SECRET`, and `FH_ACCESS_POLICY`.
Model selection from Studio instead uses `FH_MODEL_REGISTRY` and its allowed
`FH_CONN_*` environment bindings. Never put credentials in the artifact.

The bank must be an explicitly configured local non-ai-banking simulator.
The policy uses the existing `local-simulator-access.v0.1` schema. Granted
scopes must cover the selected capabilities and their sources, and the bank session's customer
must match; a Pack grants nothing. Balance requires account:read; wealth requires both
wealth:facts:read and wealth:customer:read. Every turn checks the full selected set.

Run `.venv/bin/python -I start_python.py`. Read `instance.json` for the owned
Host PID, bridge PID, instance generation, build ID and loopback port. Use a
Bearer token for `GET /health`, `POST /sessions {}`, and
`POST /sessions/{sessionId}/chat {"query":"query my balance"}`. Studio also uses
the buffered AG-UI endpoint; token streaming is unsupported. Model snapshots are opt-in.
Stop the Host PID with SIGTERM, and wait for exit; it closes its Java child.
The script does not control any pre-existing bank or Builder process.

## Boundaries

The model can choose an optional account selector, never principal, customer,
purpose, scope or bank session. The Java bridge owns those from the simulator
and trusted policy. Every model call, tool call and financial output release is
checked against current authority. Revoked sessions are not reused.

Native callbacks mean attempts, not proof of network dispatch. Java observation
windows include pipe and authorization overhead and are not Python SDK timings.
Python total elapsed time uses a separate monotonic clock. Snapshots record filtered
CLIENT_REQUEST_PREPARED and PARSED_MODEL_RESPONSE, never proof of HTTP dispatch or hidden
reasoning. They reuse Java's bounded body store and separate history-view authorization.
Enable only for approved synthetic diagnostics with FH_SNAPSHOTS_ENABLED=true and
FH_SNAPSHOT_CAPTURE_OWNERS containing the FH_HISTORY_OWNER value. View permission is
workbench-only FH_SNAPSHOT_VIEW_OWNERS; it is not forwarded to this Agent. Default is
Trace only, no body. The Host owns the SDK tracer_record_data collector while enabled;
another model-body collector is not qualified and its operator declaration rejects startup.
Projection limits omit oversized bodies without changing actual model inputs. Queue/storage
failure is visible as missing history body, not successful capture. Python projection duration
must not be subtracted from Java timestamps. Bridge failures are fail-closed and are not automatically
replayed. Installation evidence distinguishes the actual Java Assembler check
from Python native tool registration. A Java Runtime dependency remains in the
bridge for its current Assembler, but no Java model execution is performed.
