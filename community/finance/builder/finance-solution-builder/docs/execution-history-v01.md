# Historical execution playback V0.1 (candidate)

This is metadata inspection, not business replay. Reading history never invokes an
Agent, model, Skill or bank. Existing generated Agents are not upgraded automatically.

## Components and activation

The Host template owns root request/instance/process identity, incremental storage,
authorization HTTP instrumentation and immutable version snapshots. Harness supplies
the candidate observation contract, callback adapter and optional Provider decorator.
NonAiBankingProvider instruments actual send attempts. No SDK or nativeLock policy is
changed. Existing financial receipts and authorization checks remain mandatory.

New generated read-only Hosts install the same mechanism for balance, wealth and
cashflow. A new Pack using an already observed Provider can reuse it; a new Provider
must add actual transport boundaries and route templates itself. Do not claim all
future Packs automatically have HTTP or model-client visibility.

`BEFORE/AFTER_MODEL_CALL` measures the SDK interval, not proof of HTTP delivery or
first token. Force-finish is NOT_SENT. Provider transport SENT means HttpClient.send
was entered. RETURNED plus businessStatus FAILED is not successful business execution.
SDK-internal retries and parallel branches not explicitly observed remain unknown.

## Storage and limits

Jobs passes an owner-scoped directory outside each Candidate. Agent deletion leaves
historical metadata and immutable version files intact. Default local archive:

- `FH_HISTORY_ENABLED`: true, or explicitly false to disable optional observation.
- `FH_HISTORY_DIR`: trusted operator directory, not a browser-supplied path.
- `FH_HISTORY_OWNER`: trusted workspace hash, not customer identity or Grant.
- `FH_HISTORY_REQUEST_BYTES`: 262144 default; 4096..1048576 bytes per event file.
- `FH_HISTORY_MANIFEST_BYTES`: 262144 default; 65536..1048576 bytes for each
  independent immutable service/runtime manifest. Python forwards this to its bridge.
  Invalid integers use the default; out-of-range integers are clamped. This does not
  raise event or label limits. Labels remain at most 65536 bytes.
- `FH_HISTORY_TOTAL_BYTES`: 134217728 default, maximum 1 GiB per archive directory.

Append is synchronous, one writer per request, at most 16 KiB per record, force(false)
after each append. There is no queue or SQLite dual-write. History listing scans owner
metadata and supports signed cursor pages (20 by default, at most 100). Legacy calls
without pagination remain limited to 100 results. Cursor views are live, UTC-sorted,
and expire on backend restart; they do not freeze the archive. Only the selected page
and lookahead are inspected in detail. See `run-records-inspection-v01.md`. Capacity
admission conservatively reserves request + two configured manifest budgets + 64 KiB
labels + 4 KiB sidecars, under a single-host quota file lock and a JVM admission lock.
Each new header freezes `archiveReservationBytes` so lowering a later writer's
configuration does not undercount earlier requests. Valid V0.1 legacy headers without
that field reserve the old writer's hard upper bounds: 1 MiB event file + three 64 KiB
sidecars + 4 KiB. Invalid/unknown headers reserve the new absolute maximum (3215360
bytes). Admission also checks actual regular-file bytes, rejects symlinks, and bounds
scans to 6000 existing requests and 32000 directory entries. No history is deleted.
A refusal or disk error
degrades observation, never turns a completed business result into a failed result.
This flush policy has nonzero cost; power-loss durability is not claimed.

Manifests are bounded regular-file reads with no final symlink following and must be
complete JSON objects with no trailing tokens. Publication writes an owner-only
temporary file in the destination directory, forces and verifies the bytes, then
creates a hard link at the final name without replacement and removes the temporary
name. This requires same-directory/same-filesystem hard-link support and permission;
unsupported filesystems degrade observation, with no unsafe fallback. It is atomic
publication, not a claim of power-loss durability or protection from a malicious
operator with write access to the trusted archive directory. Historical readers cap
manifests at 1 MiB independently of labels and mark corrupt/missing manifests as gaps,
preserving business status and the existing owner-scoped safe error contract.

Late events, invalid/mixed-request records, gaps, unclosed spans and lock mismatch
are visible. Storage failure may prevent even a terminal degradation record; the
current response receipt reports failure when available, and history marks the gap.
Startup recovery checks the saved PID/start identity. A dead/replaced process gets a
separate recovery timestamp. It never invents a death time, duration or successful
completion, and never resumes a bank action. PID namespaces/multi-host directories
are outside this single-host contract.

Default persistence excludes customer questions, raw tool results, prompts, secrets
and hidden reasoning. Session/subject hashes remain linkable pseudonymous metadata,
not anonymized public data. Routes are allowlisted templates without query strings.

## Inspecting and pruning

Use workbench **运行记录** to filter the Beijing display day, Agent/build,
subject/session reference, request and status. The browser converts that day to an
explicit UTC half-open range. The legacy `date=YYYY-MM-DD` API remains UTC-based;
new callers use `createdFrom` and `createdBefore`. Displayed timestamps use the fixed
`Asia/Shanghai` format `YYYY-MM-DD HH:mm:ss`, independent of browser locale.

The request header `capabilities` is the immutable installed capability list, not proof
that every capability ran. List titles use ordered capability spans and business-result
evidence only; installed capabilities remain under runtime/asset details. New generated
Hosts also freeze the Agent display name and explicit request type. Old records are not
rewritten and fall back to a neutral request label. The list never derives a request
summary from a model snapshot. A request detail separates execution
outcome, server duration, record completeness, confirmed failures, duration concerns
and evidence gaps. HTTP return, Host completion and financial business status remain
separate facts.

The **执行过程** tab uses a common scale within each recorded process clock and keeps
different process clocks in separate groups. Click a measured interval to inspect its
outcome, timing boundary, raw operation and related checks. Open-ended spans remain
open-ended; short spans get a larger hit target without widening their visible bar.
Empty space is not labeled as queueing, and concurrent or nested intervals must not be
summed as elapsed time. Root Host time and executor/nativeLock waits are distinct.
The first view selects the earliest evidenced failure; otherwise it selects the longest
comparable model/tool interval. Normal descendant checks are collapsed and counted;
failed descendants remain visible. Selection is an investigation entry, not an automated
root-cause claim.

The **模型交互** tab reads an authorized body only on demand and labels whether the
content came from client request preparation or parsed model response. A captured
client input is not proof of network delivery, and no view claims to expose hidden
reasoning. The **技术信息** tab retains correlation IDs, installation/version evidence,
coverage and paged raw events. Browser-reported monotonic first status/body display
timings are separately labeled; they are not trusted financial evidence or token TTFT.
Old Agents and failed/disconnected clients may have no browser timing.

Retention is explicit local-operator maintenance, default dry-run, not anonymous UI:

```bash
python -m finance_solution_builder.history_retention --data-dir <WORKBENCH_DATA> --owner <TRUSTED_WORKSPACE_ID> --days 30
# Review eligible IDs, then repeat with --apply to delete those historical records.
```

Only old closed/recovered requests whose process is absent are eligible. Live-process,
unrecovered, unreadable and symlink entries are skipped. Configure a maintenance job
only after confirming local retention policy. At quota the archive refuses new records
rather than silently deleting evidence. Back up required history before applying prune.

## Reproduce controlled tests

From this project, using the matching Harness observation branch:

```bash
python engineering/build_history_candidate.py --harness <HARNESS> --output <NEW_OUTPUT> --unit cashflow
python engineering/verify_execution_history.py <NEW_OUTPUT>/candidate --harness <HARNESS> --output <NEW_EVIDENCE>
python -m pytest tests -q
npm run build:ui
npm run build:transport
```

The probe uses synthetic data and a controlled model endpoint. It terminates only its
own isolated child, checks slow requests, source-in-flight revocation, cancellation,
crash/restart, pure historical reads and bounded-storage failures. It is not an actual
model quality test. The generated directory's build report records dirty source status;
development pilots must not be presented as final committed release artifacts.

## Release acceptance

Stage and review fixed Harness/Builder commits, generate new locked pilot artifacts,
then run seven compositions, targeted faults, browser checks and an explicitly enabled
actual model/full-simulator case. Preserve existing workbench data/configuration and old
release links. Never auto-upgrade old Agents. No production deployment has been accepted
by this document alone. Human independent use and actual one-day retention are separate.
