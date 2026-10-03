# Curated native coverage, candidate V0.1

This is an isolated engineering gate, not Runtime/Host qualification or Skill
quality acceptance. It does not change the managed catalog or deployment.

## Reproduce

From a clean Harness checkout with its reviewed dependencies already in a local
Maven repository, and the matching Eval source plus FastMCP environment:

```sh
python3 engineering/verify_curated_native.py \
  --eval-repo /absolute/path/to/finance-skill-eval \
  --source-python /absolute/path/to/fastmcp-environment/bin/python \
  --maven-repo /absolute/path/to/isolated-maven-repository \
  --output /absolute/path/to/new-evidence-directory
```

The runner checks the three synthetic source files against the Provider's pinned
reference contract, starts its own bounded loopback source, derives test schema
pins from that owned source and terminates/reaps it in `finally`. It never reads
production credentials. The generated configuration and schema pins are test
inputs, not an auto-admission mechanism for arbitrary MCP servers.

The six selected capability packs are customer, insurance, research, audit,
transfer-risk and post-loan. Their complete installation closure has 12 tools,
including post-loan enterprise lookup. This is NOT the six-Skill acceptance
cohort: the tool-free coaching Skill still needs a tool-free build path. The
wealth draft pack remains excluded. No TASK tools are installed.

## What is measured

- Both native Java Runtimes install the original capability definitions and
  call the actual curated Provider/official MCP SDK. No replacement Agent loop.
- Every selected tool's complete `sourceResult` matches the frozen source
  fixture, including missing values and synthetic/source markers.
- Research continuation is repeated three times per Runtime. The later model
  request contains both facts and documents. These are scripted HTTP models,
  not model quality or task-selection experiments.
- Revocation after a real source result returns blocks the next model HTTP
  request. AgentScope additionally tests uninstalled-tool denial, absent grants,
  model failure and preservation of an UNKNOWN risk decision.
- Raw evidence includes actual controlled model HTTP bodies, tool results,
  installation information and observation events. Only synthetic data is used.
- Test-only AgentScope bootstrap follows the existing qualification test pattern;
  it does not emit, publish or bypass an operator qualification report.

## MCP observation semantics

The Provider captures its parent scope before entering SDK worker threads.
`provider-mcp / exchange` groups `provider-http / attempt` spans under the
existing capability span. All traffic uses the `/mcp` route template; no request
body, source customer ID, session header, credential or hostname is recorded.

`SENT` is recorded only after actual response headers arrive. A connection error
or timeout without a response remains `NOT_OBSERVED`; starting a JDK request is
not independent proof of transmission. HTTP status, transport result, parsed
MCP result and business decision remain distinct. For example, a 200 response
and completed read can still contain `UNKNOWN` or `UNAVAILABLE`.

The SDK may attempt a GET stream that the stateless source rejects with 405.
That HTTP exchange remains visible; it is not a financial read or a retry of
`tools/call`. Metadata intentionally does not reconstruct JSON-RPC bodies.
HTTP attempts must therefore not be counted as business tool calls.

Observer failures do not change a completed read or cause retries. HTTP spans
close before completing the bounded exchange future. Revocation, cancellation,
size/schema checks, source pins and cleanup rules are unchanged.

## Remaining formal-entry gates

1. Exact Runtime and generated Host qualification against the new Provider and
   configuration identities, with all existing negative checks retained.
2. Reviewed catalog admission and build-delivery evidence for the selected packs.
3. Explicit original-Skill migration, including resources, tool bindings and
   source/migrated/installed digests. No claims based only on tool probes.
4. Tool-free coaching and uploaded no-tool Skills: the current AgentScope
   assembler and dynamic Launcher require nonempty capabilities. Do not install
   an unrelated banking tool to work around this restriction.
5. Six curated Skills (19 cases per Runtime), newly uploaded supported Skills,
   migration-to-asset-to-test UI, real model/source evidence and lifecycle faults.
6. Actual administrator SSO and cross-owner checks, review/CI and controlled
   promotion. AI tests do not substitute for human professional-quality review.

The candidate stays at `/release-check/` until these gates pass. Viewing a report
or replaying its evidence must not execute business tools again.
