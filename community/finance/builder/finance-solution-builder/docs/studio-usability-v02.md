# Studio Usability Revision (2026-09-07)

This change stays in the existing scenario Builder and its static UI. No EA,
Harness Core, Runtime SDK or banking Adapter implementation is copied or changed.
The accepted Studio assets remain the starting point.

## Model Configuration

Selecting EA catalog metadata does not configure a Java model client. The Studio
now shows the binding state in the selection card and exposes `startReadiness`
on an owner-scoped build response. Blockers are configuration observations, not
claims about network connectivity or business authority. They contain no keys or
endpoints. The start API still rechecks the frozen versions and authorization.

An old build with a null model configuration version must be rebuilt after the
operator binds that connection. An unbound choice may still generate an artifact,
but its start button is blocked with a reason. The API does not silently select
another model, and configuring DeepSeek does not enable the rest of the catalog.

An operator who already has the three legacy `FH_MODEL_*` environment variables
can explicitly bind that **same** model using:

```sh
.venv/bin/python engineering/start_local_stack.py \
  --harness-repo <fixed-Harness-checkout> \
  --simulator-jar <approved-simulator-jar> \
  --simulator-sha256 <verified-sha256> \
  --bank-port 18139 --ui-port 18138 \
  --workspace <private-local-environment-directory> \
  --bind-configured-model model-ea-002 \
  --provision-demo-policy
```

The model name must exactly match the selected catalog identity. This opt-in
operator adapter binds one model only; it is not a runtime fallback. It persists
only environment variable names and public model metadata. Credentials stay in
the process environment, outside generated artifacts. An existing different or
disabled registry is never overwritten. For multiple configured models use the
existing private `FH_MODEL_REGISTRY` mechanism instead of this migration flag.

Use `--provision-demo-policy` **once**, only in a new private directory. Omit it
on subsequent starts. Existing revocations and expiry are not reset. The default
generated demo policy expires after one day; local deployment expiry is an
explicit operator decision and is not a production IAM grant.

## Builder UI

- Capability checkboxes and the plus tile use the same registered capability
  set as the modal and actual Solution. Mandatory financial controls remain fixed.
- The local bank opens an in-place selection dialog instead of leaving the form.
  It only offers the existing simulator connection, with no arbitrary URL editor.
- The build view shows the three actual backend stages, elapsed measurements,
  selected Pack/model names and source/lock evidence. Animation is decorative;
  stage completion comes only from observed reports, never a timer.
- Polling updates changed build details without replacing the sidebar or main
  assembly animation. Reduced-motion preferences remain supported.
- `修改配置并重新构建` creates a separate immutable build. There is no invented
  semantic release number or automatic replacement of the previous Agent.

## Delete Semantics

`POST /api/v1/builds/{id}/delete` takes exactly `{ "buildId": "<same-id>" }`.
Existing owner, CSRF, Origin and Host checks apply. The UI requires confirmation.
The backend serializes deletion with lifecycle operations and rechecks the actual
owned process; queued/building/starting/running/stopping/unmanaged Agents cannot
be deleted. The user must stop the Agent first.

Deletion removes only that build's generated directory (including dependency
copies, zip exports and logs), input and active record. Other Agents, shared
Maven caches and old independent evidence bundles are untouched. A small receipt
under `build/deletion-receipts` keeps only build ID, deletion time and the available
artifact lock SHA. It does not preserve customer data, conversations or credentials.
Redirected root paths are rejected and nested symlinks are not followed. Partial
filesystem cleanup fails explicitly and can be retried; it is not reported as a
successful delete. Already downloaded artifact copies are outside this operation.

These are additive scenario API fields and an operation. Old selection contracts
and frozen artifacts retain their format. No automatic historical bulk deletion
or cross-user build management is introduced.

## Reproduction

```sh
.venv/bin/python -m pytest tests -q
NODE_PATH=<node-dependencies> .venv/bin/python engineering/verify_multi_model.py \
  --harness <isolated-Harness-checkout> --output build/<new-evidence-directory> --browser
```

The latter builds all three compositions and exercises actual generated Java
hosts against separate controlled model and bank HTTP endpoints. Browser checks
cover checkbox/modal behavior, stable animation nodes, editing a fresh narrower
build, deletion cancellation, real deletion and persistence after reload. It is
not a real-model quality test or independent human onboarding.
