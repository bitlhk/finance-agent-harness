# Shared TASK execution (candidate)

This embedded module depends on Core, not a Runtime SDK or a bank implementation. It is not a scheduler, planner or financial transaction engine.

## Ownership

| Component | Responsibility |
| --- | --- |
| `TaskCheckpointPolicy` | Frozen parameters, owner/capability matching, supplementation and checkpoint recovery rules |
| `TaskConfirmationPolicy` | Confirmation field, binding and time checks; not authentication |
| `TaskProtocol` | Core TASK request construction, idempotency, bank-result checkpoint and observation decisions |
| `ProviderTaskExecution` | One authorized Provider attempt with freshly supplied context |
| `TaskCoordination` | Durable handle, confirmation, terminal cache and recovery coordination |
| Integrator | Native tools, current trusted context, result/event conversion and SDK lifecycle |
| Host | Authentication, grants, confirmation UI/event provenance, installation scope, journal and admission |
| Provider | Bank API mapping, precheck/submit/lookup and authoritative business result |

## Integration rules

1. Install only declared, compatible capabilities. Build an installation scope including trusted identity/session and bound asset versions. Shared code does not permit another Runtime to take over an old task.
2. Provide a `TaskCoordination.Journal`. `locked` must serialize participating processes; every `put` must be durably visible before it returns, even if a later Provider call crashes. Do not defer all writes until the lock callback exits.
3. Register a backend per installed capability. Supply fresh authorized Core requests to `ProviderTaskExecution`; do not derive grants, task handles or host approval from model output.
4. Route model tools only to `invoke`. `confirmationBinding`, `confirm` and `decline` belong to trusted Host operations. A constructed `Approval` value does not authenticate the caller.
5. Keep required audit and observation boundaries. A declaration is not evidence that the Runtime hook runs or blocks.
6. Reuse the protocol for new same-protocol capabilities, then verify actual bank/Runtime behavior. Missing execution semantics require a compatibility review rather than a business-specific Builder branch.

`InstalledTaskController` in the OpenJiuwen integration is the current compatibility consumer. It preserves the old Host store and terminal file shape. AgentScope TASK is **not yet admitted or verified**; its public QUERY-only assembly gate remains unchanged.

## Verification

From repository root:

```bash
mvn -pl integrations/openjiuwen-java -am \
  -Dtest=TaskCoordinationTest,TaskRecoveryTest,TaskContinuationPolicyTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
python -m unittest engineering.test_execution_module_boundary -v
```

These are controlled tests, not a live bank or Runtime TASK qualification. See ADR-0021 for second-consumer, artifact lock and deployment gates. Legacy `89d0ad1` synthetic pending/UNKNOWN/terminal bytes are retained in OpenJiuwen test resources; no production checkpoint was copied.
