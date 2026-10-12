# Capability extension: final engineering acceptance

Date: 2026-10-07
Branch (both repositories): `feat/capability-extension-boundaries-v1`

## Conclusion

PASS for the bounded engineering scope: package-owned query presentation,
verified financial dependency closure, two actual additional read-only source
contracts, and preservation of the six existing cards' declared Runtime support.
This is not production, real-model, full-bank or independent human acceptance.
No deployment, old Agent migration, production restart or main-branch merge was
performed. The original parallel-work worktrees were not modified.

Tested implementation pair:

- Builder: `cb0672817c6f5f5a58371f780f960b4d16465787`.
- Harness: `9554417161812e18339f84dfae4ea6a0bdbcfcbd`.
- Bank contract inspected read-only: `162ef4e587c9e9b781bab60bf89591cf4255e579`.
- Evidence index: [capability-boundaries-20261007.json](verification/capability-boundaries-20261007.json).
- Raw local evidence root: `/tmp/finance-boundaries-acceptance`.

Documentation-only commits after these implementation commits do not change the
tested binaries. Earlier checkpoint notes are chronological, not final results.

## Completed boundaries

1. Existing business projections are separated from dispatch, with 257 frozen
   old/new fixture comparisons and the existing Java projection tests.
2. New packs own bounded `presentation.v0.1.json` descriptors. Generated builds
   lock their embedded manifest. Altered presentation rejects binary reuse.
   Unknown versions, scripts, paths and uninstalled capabilities cannot register
   executable views. The generic browser renderer escapes values and retains
   exact decimal strings, missing values and current-page pagination limits.
3. The financial artifact verifier derives exact coordinates and transitive
   reactor dependencies from generated Maven assets; POM and JAR identities are
   frozen and verified. Legacy no-POM verification stays intact. This intentionally
   is not a general-purpose Maven resolver, and existing Host-required libraries
   have not all been removed from the template.
4. Two additional queries exercise actual bank contracts through native Java
   chat, tool dispatch, managed Provider, evidence, projection and rendering:
   `account.statement.query` and `account.transaction.query`.
5. AgentScope generated Host compilation exposed an existing cross-repository
   API mismatch. Harness now accepts the bounded Host generation tuple
   (temperature, top_p, max_tokens); old four-argument callers keep their original
   defaults. Actual controlled HTTP verifies both the tuple and legacy behavior.
   No retry, arbitrary SDK option, capability support or authorization was added.

## Existing support preserved

| Card | OpenJiuwen Java | Python bridge | AgentScope Java |
| --- | --- | --- | --- |
| Account balance | yes | yes | yes |
| Wealth facts | yes | yes | no |
| Wealth customer context | yes | no | no |
| Cashflow (summary and analysis) | yes | yes | yes |
| Internal transfer | TASK | no | TASK |
| Channel preferences | TASK | no | no |
| New monthly statement | yes | no | no |
| New transaction page | yes | no | no |

The existing six cards never all supported every Runtime. Unsupported selections
remain rejected rather than silently substituted. TASK confirmation, cancellation,
authorization, checkpoint and recovery tests remain in the original suites.

## Verification results

| Layer | Result | Meaning |
| --- | --- | --- |
| Full Builder suite | 1806 passed, 5 skipped | 1811 collected; no failures/errors |
| Final Harness reactor | 380 executed, 8 skipped | 388 collected; all 17 modules successful |
| Generated new-query Java Host | 59 passed | package and component installation passed |
| New-query real HTTP chat | 5 checks passed | controlled model and local synthetic source |
| Old/new projection comparison | 257 fixtures equal | included in Builder suite, not additional end-to-end runs |
| Browser rendering tests | records and cashflow passed | escaping, exact values, paging scope |
| AgentScope native qualification | 43 controlled checks | 92 model HTTP, 74 bank HTTP; no real model |
| AgentScope isolated Host | two independent JVM runs passed | native qualification consumed |
| Builder AgentScope Host qualification | 19 checks passed | history, diagnostics, denial and evidence checks |
| Representative artifact builds | 17 BUILT | Java 9, Python 4, AgentScope 4 |

Resolution separately accepts Java's 255 non-empty supported subsets and each of
Python/AgentScope's seven subsets. Each latter Runtime rejects five unsupported
singleton selections. Resolution is not execution. The artifact matrix builds
every singleton plus the maximal combination, not all 269 combinations.

The 13 Java/Python representative builds were frozen at Harness `723140b`.
The only subsequent Harness source delta (`9554417`) is the AgentScope model
connection, options forwarding and its test. AgentScope's four builds use
`9554417`. The full Harness reactor and new Java combined build/chat were rerun
at `9554417`. The evidence index preserves these distinct source identities;
it does not relabel earlier builds as later binaries.

Skipped live tests are not passes. Existing historical artifact and TASK checks
are regression tests, not a migration of running historical Agents.

## New-query semantics and extension cost

- Monthly summary uses an explicit account and YYYY-MM period. It is not an
  official bank statement or a legal-document generation capability.
- Transactions use explicit account/date range, bounded limit and optional cursor.
  Results and totals describe the current page only; `hasMore` is visible and
  does not trigger automatic pagination.
- Both keep `account:read`, trusted subject binding and source identity checks.
  Missing account causes zero source reads. Missing amounts never become zero.
  Source numeric normalization is handled without floating-point loss; extreme
  exponents are rejected. Unexpected source fields are not published.
- Each new capability needs definition, assembly/presentation metadata, Provider
  mapping and contract tests. The first generic presentation/closure change is
  shared infrastructure; these two additions need no new OpenJiuwen dispatch,
  Runtime adapter, TASK authorization or per-capability browser branch.
- Pack auto-discovery currently places the two additions in `agent.ungrouped`.
  Product grouping/naming remains a pre-release task, not a hidden acceptance pass.

The result supports faster onboarding of similar queries. It does not establish
that all 26 business modules are interchangeable leaf capabilities or that a new
bank, new authorization flow, complex TASK or bespoke view is configuration-only.

## Failures retained and corrected

See the checkpoint report for initial development failures. Final acceptance
additionally retained:

- Decimal normalization causing false rejection: fixed in Harness `723140b`.
- Old AgentScope admission evidence rejected after template changes: regenerated
  native and Host proof against actual artifacts; no digest gate bypass.
- Builder expected `ModelConnection.Generation`, but the fixed Harness lacked
  it: fixed in `9554417`, with actual HTTP and legacy-default assertions.
- A verification invocation supplied observation requirements outside the
  trusted Harness workspace: correctly rejected as `UnsafeWorkspacePath`.
  Reused the existing repository `host-diagnostics-required.v0.1.json`; no path
  check was weakened. The final AgentScope matrix then passed.

All qualification model/bank requests use synthetic fixtures. Real credentials,
customer data and live banking calls were not needed for this acceptance.

## Reproduction

Use the Builder project's Python environment, JDK 21 and an isolated populated
Maven cache. From `finance-solution-builder`, set
`PYTHONPATH=src:../base/src:engineering`. The local Maven wrapper uses offline
mode and `/tmp/finance-boundaries-m2`, not the shared default Maven repository.

```sh
python -m pytest tests engineering -q
node engineering/test_records_display.mjs
node engineering/test_cashflow_display.mjs
python engineering/verify_account_history_extension.py --harness "$HARNESS" --output "$NEW_OUTPUT" --maven "$MAVEN" --verify
python engineering/verify_account_history_chat.py "$NEW_OUTPUT/account-history/candidate"
python engineering/verify_runtime_capability_matrix.py --harness "$HARNESS" --output "$MATRIX_OUTPUT" --report "$MATRIX_REPORT" --maven "$MAVEN"
```

AgentScope builds require current native and Host qualification first:

1. Run Harness `integrations/agentscope-java/isolatedHost/verify.sh OUTPUT --tasks`
   with `FH_MAVEN_REPO` pointing to the isolated cache.
2. Run Builder `engineering/verify_agentscope_host.py OUTPUT --harness ...
   --native-bundle ... --maven-repo ... --maven ...`.
3. Supply the qualified candidate/lock, native qualification/digest, Host
   qualification/digest and repository-contained observation requirements via
   the existing `FH_AGENTSCOPE_*` / `FH_OBSERVATION_*` admission variables.
4. Run the matrix with `--runtime agentscope-java-2.0.3`.

The exact successful local launcher is archived at
`/tmp/finance-boundaries-acceptance/run-agentscope-matrix.py`.
Do not reuse proof from different binaries or downgrade REQUIRED observations.

## Release boundary

Temporary verification Hosts, fixtures and build workers have finished. Original
worktrees and production remain unchanged. Local projection screenshots are
`history-desktop-final.png` and `history-mobile-final.png`; they are not proof of
full-site human acceptance. New-query live model/full simulator/human onboarding
remain explicitly NOT_RUN/PENDING in build evidence.

The engineering remediation is ready for review. Before production, pair both
repository changes, settle the new card grouping, run the existing staging/live
checks with approved access and verify rollback. Do not independently publish
the Builder expecting the old AgentScope binary to satisfy the new interface.
