# Runtime 0.1.15 isolated qualification

This branch is a CLI qualification candidate, NOT a deployable workbench upgrade.
The workbench selection/catalog still exposes 0.1.13. Do not run or deploy its UI
against this experimental recipe. Migrate those versioned inputs only after the
Runner, authorization and generated-composition gates pass. No Runtime source changes.

Baseline Builder: 563769f6 (includes the unpublished history business-label fix).
Baseline Harness: faff145 (implementation a7b0b14 plus delivery report).
Production remains Builder76b9c1a7 / Harness a7b0b14, OpenJiuwen Java0.1.13.

This candidate pins recipe, generated Maven dependency and Host-reported runtime to
0.1.15; the matching Harness branch migrates runtime declarations and keeps strict
actual-artifact checks. Old generated artifacts are neither changed nor upgraded.

CLI gate (with this branch on PYTHONPATH):

```bash
python engineering/verify_history_compositions.py --harness <HARNESS_015> --output <NEW_DIRECTORY>
python engineering/verify_execution_history.py <NEW_DIRECTORY>/cashflow/candidate --harness <HARNESS_015> --output <NEW_FAULT_DIRECTORY>
```

## Python Builder discovery, 2026-09-12

Fetched GitCode origin/finance at b26496b93ae726941ac7e186de9aff11830cd88f.
Its Finance Solution Builder FEAT-010 is explicitly deferred/backlog (owner decision
2026-09-08), code/tests/evidence complete=false. Public backend registry examples are
not proof of a runnable Python Candidate. No FEAT-010 implementation branch was found
among fetched remote branches; this does not rule out an unshared/private branch.

Other Python consumers found in the existing repository:
- Intent Agent Builder: dependency openjiuwen==0.1.16, distinct intent application.
- Scheduling Agent Builder: Git-pinned Python runtime ce21a9b7, separate scheduling scope.
- Finance Solution Builder: Python build/control process generating Java Agents.

Required from the colleague: exact implemented Python Candidate branch/SHA, generated
artifact, dependency lock, startup/health command and a working read-only probe. Then
inspect trusted identity, granted scopes, every-call checks, Provider contract and
runtime lifecycle. Do not create a central Java service or copy the full Core merely
to make this discovery gate green. Python0.1.18 is not yet executed or accepted here.
