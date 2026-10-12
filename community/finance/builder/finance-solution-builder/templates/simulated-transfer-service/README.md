# Isolated Java simulated-transfer candidate

This artifact runs independently of Builder and Harness source directories. It is not
a production banking application. The read-only Studio recipe does not admit it.

Prerequisites: Java 21; an isolated non-ai-banking simulator whose instance ID and
synthetic identity are verified; a private directory outside this artifact; a private
Host access policy. Runtime: OpenJiuwen Java 0.1.15. No SDK source modifications.

Environment supplied by the trusted host (never embedded in this artifact):

```
FH_TRANSFER_SIMULATOR_ONLY=true
FH_TRANSFER_PORT=18662
FH_TRANSFER_STATE=/private/pilot-state
FH_TRANSFER_POLICY=/private/pilot-policy.json
FH_BANK_URL=http://127.0.0.1:18661
FH_BANK_INSTANCE=<exact simulator instance ID>
```

Optional real model: FH_MODEL_URL, FH_MODEL_NAME, FH_MODEL_KEY. Without these,
model dialogue is unavailable; manual preparation/confirmation is a distinct test path.
For a trusted reverse proxy, set FH_TRANSFER_ORIGIN to its exact origin and
FH_TRANSFER_COOKIE_PATH to the pilot prefix, e.g. /simulated-transfer/. The proxy must
preserve the public Host header and remove this path prefix before forwarding. Do not
forward this to the existing Studio /api routes. Default binding is loopback only.

Policy example (synthetic customer mapping must come from actual simulator login):

```json
{
  "schemaVersion": "simulated-transfer-access.v0.1",
  "bankUrl": "http://127.0.0.1:18661",
  "simulatorInstance": "<verified-instance>",
  "maxAmountCny": "1.00",
  "subjects": {
    "<source-customer>": {
      "enabled": true,
      "scopes": ["account:read", "transfer:create"],
      "expiresAt": "<explicit-future-UTC-time>"
    }
  }
}
```

The safety cap cannot exceed 100 CNY. Account status NORMAL and recipient status
ACTIVE are different source conventions, not a generic shared status enum. The bank
still performs preflight and owns business decisions. OTP strengthens authentication;
only the separate browser confirmation authorizes the frozen task.

Start from this directory, keeping the process PID in the service manager:

```bash
java -cp 'target/simulated-transfer-host-0.1.0.jar:target/dependency/*' \
  io.github.bitlhk.finance.builder.transfer.TransferHost "$PWD"
```

On Windows the classpath separator is `;`; this candidate's private file store and
tests currently target a POSIX host. Do not claim Windows validation.
Stop only this owned process with SIGTERM; restart with the same private state and
bank session still valid. No automatic resubmission occurs. Keep original task handles,
installation Locks, checkpoints and nonce records. Do not clear state to resolve UNKNOWN.

State directories contain sensitive session material. Preserve mode 0700/0600, exclude
them from artifact downloads and source control. Host logout revokes the local session;
it does not claim cancellation of a bank task. Parameter revisions on an existing task
are rejected; this pilot does not implement bank task amendment/cancellation.

service-lock.json identifies actual generated files and JARs; each mount records an
immutable installation Lock under private state/installations. The outer lock is an
integrity inventory, not a signature from a trusted release authority.
