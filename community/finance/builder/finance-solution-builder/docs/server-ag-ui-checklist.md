# Server Demo and AG-UI Acceptance

Run this checklist for each new deployment; the upstream source delivery does not
certify a maintainer's environment. Record actual versions and scoped evidence.

- [ ] Fixed Builder/Harness/Runtime/simulator versions and artifact digests.
- [ ] HTTPS and authenticated operator ownership, private origin and model bindings.
- [ ] Build, start, stop and download operate on the current instance only.
- [ ] Actual model, synthetic bank, two-turn history and cross-session isolation.
- [ ] Revocation before a delayed source response prevents the next model request.
- [ ] Standard AG-UI lifecycle; final financial text released only after authorization.
- [ ] Public redacted guidance is separate from authenticated internal material.
- [ ] Stop/delete only the current verification's test instances; retain scoped evidence.

Instructions: [server deployment](server-demo-v01.md), [AG-UI](ag-ui-v01.md),
[documentation publication](developer-guide-entry-v01.md).

Token streaming, real banking IAM, upstream acceptance and independent human
onboarding are separate acceptance items, not implied by a successful build.
