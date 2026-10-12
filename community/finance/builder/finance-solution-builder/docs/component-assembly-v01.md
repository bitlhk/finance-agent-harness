# Component-owned assembly admission V0.1

Status: candidate, admission wired into managed local builds. Not a production release.

Builder understands Harness assembly contracts, not concrete financial business.
The resolver in `src/finance_solution_builder/component_assembly.py` consumes the
existing catalog, Pack files and strict component-owned companions. It does not
interpret balances, cashflow calculations or bank transaction states.

## Ownership

| Owner | Declaration | Meaning |
| --- | --- | --- |
| Harness Capability Pack | `assembly.v0.1.json` beside `pack.yaml` | Required host protocol features and default Domain Pack identities |
| Harness Provider | `assembly.v0.1.json` in registered source | Supported Pack versions/capabilities/operations, artifact and connection kind |
| Builder Host template | `host-support.v0.1.json` | Exact Runtime references, profile, supported protocols and connection kinds |
| Calling application | Explicit Pack/Provider references and bindings | Reviewed assembly choices, never an identity or permission grant |

The protocol vocabulary is small: `query.v1`, `trusted-grants.v1`,
`observation.v0.1`. Java readonly declares only QUERY. Metadata cannot qualify a
TASK Host, implementation or renderer that does not exist. No code from an asset
is imported or executed by this resolver. Unknown fields are rejected.

## Reproduce

Use this Builder branch and the paired Harness candidate branch, not an old
deployed source directory. From this Builder project:

```sh
PYTHONPATH=src:../base/src python -m pytest tests/test_component_assembly.py -q
python engineering/verify_component_assembly.py --harness /path/to/finance-agent-harness
```

After generating a compiled candidate with this template, validate the real Java
installation entry (the output directory must not already exist):

```sh
python engineering/verify_component_installation.py \
  --candidate /path/to/build/candidate --output /path/to/new-installation-checks
```

This uses the candidate's actual JARs with isolated asset copies. It checks valid
preparation, missing plan, changed scope requirements, changed binding/version,
and a mutually consistent but false template/plan Runtime claim. The last case
must be rejected by comparing against the actually loaded Assembler artifacts,
not just by matching two declarations. No bank session or business call is made;
preparation is not Runtime tool registration. Test copies are not portable Agents.
For a Python candidate it uses the existing `ReadOnlyPlanExporter` and delegated
Java bridge solution, not the Python solution as a Java input. See
`docs/component-admission-verification-v01.md` for fixed versions and observed results.

The second entry checks real checked-out files against all seven legacy readonly
recipes. It emits exact input-file hashes and explicitly marks installation,
business authorization and deployment unverified. Test fixtures are controlled
copies, not a Provider distribution. Requirements are returned as `requiredScopes`;
there is deliberately no `grantedScopes` output.

## Compatibility and remaining wiring

Old `solution_for` and request versions retain their compatibility recipes. Old
generated locks and sessions are unchanged. New directory inspection, preflight,
Workbench queue admission and the actual Java build route call `qualify_solution`.
Python builds qualify their delegated Java bridge through that same build route.
The actual Assembler checks `component-plan.json` before exposing an installation;
the generated service lock contains both admission and observed installation plans.
The admission plan identifies the managed template separately from the Runtime
artifact. Python records its Java bridge template digest separately from the
combined Python host digest. No client-supplied support declaration is consumed.

Private SOP replacement is checked against its immutable snapshot and scope;
replaced defaults are not loaded as mandatory methods. Native Java scope checks
read the locked capability requirements, still obtaining grants independently.
Provider construction and additional source requirements now come from managed
classpath factories in the owning Harness artifacts. The managed identity-selection
CLI avoids legacy business recipes; see `docs/managed-selection-v01.md`.
Legacy WebUI selection enums and the fixed simulator identity/connection adapter
remain. Arbitrary external Provider JAR installation is not supported. Consequently
this does not establish complete WebUI extensibility or complete step 2.

Next gates are Provider factory/selection extensibility, then reuse
of the existing TASK confirmation/checkpoint implementation inside shared chat.
The second distinct candidate business is channel preference update. It must be
verified against the simulator and must not require business-specific changes to
Builder core/shared Host/UI. The synthetic extra-capability unit test only proves
resolver extensibility, not that this business or a new runtime Provider runs.

No server was deployed by this increment. Keep the current readonly deployment
and standalone transfer pilot unchanged until technical acceptance is complete.
Human independent onboarding remains a separate, unperformed acceptance item.
