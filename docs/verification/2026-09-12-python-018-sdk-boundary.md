# Python 0.1.18 SDK boundary probe

This is an isolated SDK qualification probe, not a Python Finance Harness,
Python Builder integration, or cross-language Core migration. No SDK source was
changed. It does not enable Python in the Java workbench catalog.

## Environment and reproducibility

- Python 3.11.15 in `/home/ubuntu/finance-python-018-probe`.
- `openjiuwen==0.1.18`, supported package Python range >=3.11,<3.14.
- Downloaded wheel `openjiuwen-0.1.18-py3-none-any.whl`, verified against PyPI
  digest: `b5fdc79cfa121324b473d9002d5c8be7514b3932f8866e22a1723147004d6d8a`.
- Evidence: `/home/ubuntu/outputs/model-snapshot-release-v01/python-018-final-3/`.
  `dependencies.json` records 167 installed dependency versions (an environment
  inventory, not a distribution-hash installation lock);
  `sdk-source-hashes.json` records 2501 Python source hashes, compared to the wheel.
- Command from Harness root:
  `/home/ubuntu/finance-python-018-probe/bin/python engineering/probe_python_018.py --output <fresh-directory>`.
- Optional isolated probe, not a new dependency for ordinary Java PR CI. The
  output directory must be new. All HTTP endpoints and data are local synthetic
  fixtures; the API key string is an inert test value. Servers are stopped in
  the probe's finally path.

## Actual results

15 checks passed, 5 loopback model HTTP requests, 22 captured callback events.

| Probe | Observed result | Claim boundary |
| --- | --- | --- |
| Model.invoke | Tool call parsed, then synthetic tool fact supplied to a second invoke | Manual model-tool-model loop, not native Agent execution or bank Provider |
| LLM_INPUT | Tested messages and tool schema equal endpoint request fields | Prepared selected fields, not complete serialized HTTP request |
| LLM_OUTPUT / invoke output | Parsed returned answer and tool call observable | Not raw response bytes, not hidden reasoning |
| Model.stream | Synthetic chunks and natural completion observed with one consistent call ID | SDK stream support only; Java snapshot product remains non-streaming |
| Invoke authorization transform | Revoked test grant raises before HTTP; request count unchanged | Invoke boundary only, not a complete identity/authorization integration |
| Invoke HTTP 500 | Error callback and caller failure, one HTTP request with retries=0 | Exception type retained; raw error body is not captured by the probe |
| Stream HTTP 500 | Error callback, no added natural-completion event, one HTTP request | Mid-stream disconnect/cancellation and retry correlation remain untested |

The initial exploratory transform forwarded the framework-injected `session`
keyword to the underlying client, which failed before HTTP. The probe now accepts
`session` explicitly and does not forward it as an LLM parameter. This is an
adapter integration consideration, not evidence that the unmodified SDK cannot
invoke models. Intermediate `python-018-2` passed 13 checks; final adds stream
failure checks. Only the final 15-check result is the completed matrix.

Captured observer data uses a field allowlist and excludes credentials and
reasoning fields. However, SDK default logging in this synthetic run also emits
messages and response details. A future financial Host must review/suppress or
filter these SDK logs before processing customer data; this probe is not a DLP
qualification or permission to enable production body logging.

## Remaining work

The colleague's fixed Python Builder artifact, generated Host contract and
maintainer integration are not available in this work package. These block
Python financial application integration, not this SDK probe. No claim is made
about authorization during a delayed Python tool result, per-owner persistence,
concurrent sessions, automatic tool execution, or parity with the Java suite.
Those require a minimal actual Host/Integrator and the same lifecycle test cases.
A central observation dashboard is future work and was not implemented.
