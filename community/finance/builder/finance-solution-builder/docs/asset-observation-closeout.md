# Asset display and snapshot closeout

## Display

Catalog compositions use the label "目录组件". ZIP availability remains explicit in
version details. A released package has one action area under "检查与生成".
Validation time (Asia/Shanghai), runtime, model/source mode and verified scope come
from the immutable release's associated trial, not the current draft or current time.
Missing evidence remains missing; controlled execution is not professional quality approval.

## Separate defects

- The demo owner reached the default 128-body count limit (998309 bytes). This was
  not disk exhaustion. `retentionSeconds=0` means no automatic expiry.
- `/sessions/{id}/status` used the conversation's shared observation context while
  chat completion appended its terminal record. Poll authorization HTTP events could
  consequently arrive in the chat archive after its terminal record.

Status, snapshot, activity, cancel authorization and pre-lock session replacement
checks now supply an explicit null observation context. They still
performs the same lifecycle, policy and source identity checks. It does not attach
to, detach or mutate the chat's observation adapter; no lock strategy is changed.
Cancellation signals themselves remain attached to the active run. Business/runtime
authorization keeps its request context. Historical late-event
detection remains unchanged and no old records are repaired or hidden.

## Capacity operations

`engineering/check_snapshot_capacity.py HISTORY_DIR` inspects body counts and sizes
without opening body content or removing files. It rejects symlinks and emits owner
hashes only. It exits 1 at 80% utilization or full, and 2 if inspection is unavailable.
Use the same `FH_SNAPSHOT_MAX_FILES` and `FH_SNAPSHOT_MAX_BYTES` environment as the
workbench, or explicit `--max-files` and `--max-bytes` arguments.

The demo may use a bounded 1024 files / 64 MiB quota, with retention unchanged at 0.
This is a deployment choice, not a new library default. No automatic pruning is
enabled. A five-minute systemd timer can expose warnings through the service state
and journal; it does not send external notifications. Plan capacity or explicitly
agree retention before the next limit is reached.

The new template applies to newly generated Agents only. Old artifacts are not
rewritten. Rebuild a separate pilot, verify persisted request/response bodies and
event completion with concurrent status polling, then stop the pilot. Keep failed
historical snapshots and their explicit reasons intact.
