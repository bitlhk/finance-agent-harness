# Python read-only packages A1

Fixed delivery, test counts and limitations: [A1 verification](python-readonly-a1-verification-20260912.md).
Full generated artifact/source/Lock identifiers: [artifact manifest](python-readonly-a1-artifacts.json).

Scope: native OpenJiuwen Python 0.1.18 plus the owned Java financial bridge. The bridge still
depends on OpenJiuwen Java 0.1.15/Harness artifacts. This is not a pure Python Harness SDK.
Requires the Harness `feat/readonly-installation-plan-v01` change (prepare/install extraction).

Supported candidate combinations: balance, wealth, balance + wealth. Cashflow and customer Skill
replacement remain the next acceptance stage. Compatibility comes from the packaged Runtime
entry in template-modules.v1.json; the UI and Python recipe consume that same capability list.
The existing seven-composition Java recipe remains unchanged.

## Build and run

Select the Python Runtime and account and/or wealth module in the local workbench. Model and
simulator configuration remain server-owned. Building uses the existing service pipeline, then
exports `installation-plan.json` from the actual compiled Provider implementations, without a
bank grant or Java Agent. Python wheels and Java artifacts remain independently locked.

At session open the Java Assembler resolves the same assets and bindings and must reproduce
the exported plan exactly. Python registers native tools from this plan and separately records
`RUNTIME_REGISTERED` and the actual tool names in `runtime-evidence/<session>/runtime-lock.json`.
The plan itself is `VALIDATED_NOT_RUNTIME_REGISTERED`, not proof that a Runtime has installed it.

Java and Python share ReadOnlyProviders and HostMethods. The method catalog is a locked host
asset, not an executable plugin or a grant. Domain Pack instructions come from the prepared
plan. Python has no wealth-specific bridge or balance-specific function signature. Tool schemas
are checked before SDK argument formatting and again by Java before financial execution.

The current host policy deliberately requires all selected public scopes plus underlying source
scopes on every model/tool boundary. Wealth requires both wealth:facts:read and wealth:customer:read.
The Provider still checks each capability and current identity independently. Revoking source
access must also prevent old wealth context from entering the next model call.

Model tool returns contain status, content, normalized business data and error/question fields;
UI cards, task/checkpoint and diagnostic evidence envelopes are kept outside model context.

## Reproduce controlled verification

Run from finance-solution-builder with its existing Python environment:

```sh
PYTHONPATH=src:../base/src python -m pytest tests
python engineering/verify_python_packages.py --candidate /path/to/generated/candidate --output /path/to/evidence
python engineering/verify_python_service.py --candidate /path/to/balance/candidate --output /path/to/balance-evidence
/path/to/balance/candidate/.venv/bin/python engineering/verify_python_pipe.py --candidate /path/to/balance/candidate --output /path/to/pipe-evidence
```

The packages verifier also accepts a separately generated Java candidate for business comparison.
Its HTTP model is explicit controlled testing, not a substitute for real-model acceptance. Compare
normalized amounts, currency, scope, risk/gaps and source semantics, not text or clock-dependent IDs.
Keep fixed source SHAs, service-lock digest and runtime installation receipt with each report.

Generated old candidates do not update automatically. No stable deployment or automatic merge is
part of A1; an isolated reviewer is separate from the prior 18438 accepted balance-only environment.
