# A2: cashflow and published professional methods

This extends [A1](python-readonly-a1.md), not the underlying Runtime or financial contracts.
Native OpenJiuwen Python 0.1.18 still uses the owned Java financial bridge and Java 0.1.15
artifacts. Snapshots remain `NOT_SUPPORTED_PYTHON_V01`; A3 is a separate acceptance stage.

## Supported scope

Three units yield seven nonempty compositions: account balance, wealth facts, and cashflow.
Cashflow contains BOTH `finance.transaction-summary.query` and `finance.spending-analysis.query`.
It is not two independently selectable units. Both require an explicit `YYYY-MM` period and
`cashflow:read`; no transfer, budget action or investment recommendation is installed.

The existing Java `solution_for`, Provider implementations, schemas and installation plan are
reused. Python registration is generic: adding cashflow does not introduce a special Tool class,
bridge protocol, Python field mapper or duplicated financial authorization implementation.
The catalog and finite recipe validation are the extension points for this accepted scope.

## Publish and select a method

In the private workbench, use Business Assets to create or import a method, validate its declared
capabilities and run the existing controlled trial. Publish the accepted draft. A draft alone
is not selectable. The controlled trial establishes loading and boundary behavior, not model
quality. Independent business review and real-model checks remain separate.

In Create Agent choose Python, Financial Assistant, an available model and the configured
simulator; select the published method version and build. The immutable selection records
`assetId`, `version`, and `releaseDigest`. The backend checks owner, release digest and declared
capability scope before materializing the method using the existing Java service builder.
Methods cannot grant scopes, install arbitrary tools, or execute uploaded scripts.

Method changes produce a new published version and a NEW candidate. The previous artifact and
its pinned method remain unchanged. There is no hot replacement of a running Agent. Reusable
Java binaries may be validated and reused; a Python artifact is not a reusable Java host seed.
Each Python candidate gets its own wheel lock and isolated environment.

At session opening, inspect the runtime installation evidence: both cashflow tool names must be
registered, the plan must match actual Providers, and the installed method digest must match
the selected version. A successful build alone does not prove installation or model adherence.

## Verification

From `finance-solution-builder`:

```sh
PYTHONPATH=src:../base/src python -m pytest tests
python engineering/verify_python_packages.py --candidate /candidate --output /checks --harness /harness
python engineering/verify_python_a2_matrix.py --matrix /matrix --output /matrix-checks --harness /harness
```

The matrix layout is `java-1/candidate`, `python-1/candidate`, through `java-7/candidate`, each
with its sibling build-report.json. Unit bits are 1=balance, 2=wealth, 4=cashflow. Generate each
with the existing service/python service builders; these are full isolated candidates, not
metadata-only directories. Do not run two verifiers on the same candidate concurrently.

Business comparisons retain amounts, currency, period, source/policy business time, scope and
missing/conflicting facts. Only per-invocation capture time is excluded for cashflow. Real zero
requires consistent zero totals, categories and trend; changing totals alone must fail as an
inconsistent source, not pass as a zero-asset case. Missing expense information remains a gap.

Use synthetic data for malformed input, revoked access, late source return, wrong owner,
unpublished/tampered releases, and attempts to invoke an uninstalled capability. Keep model
prose checks separate from deterministic facts. Published method trials, real-model acceptance,
browser operation, upstream review and independent human reproduction are distinct results.

Existing generated instances and the stable service are not upgraded by these source changes.
This candidate depends on Harness PR15 and Builder MR149/PR147; no automatic merge is implied.
