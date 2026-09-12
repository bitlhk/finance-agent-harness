# ADR 0010: Candidate execution observation V0.1

Status: candidate; financial audit and authorization contracts remain unchanged.

## Decision

Add an optional, metadata-only `ObservationEventV01` / `ObservationSink` and explicit
per-request `ObservationRun`. The Host owns root identity, persistence and owner access.
Core does not become an exporter service, workflow engine or history database.

`OpenJiuwenObservationAdapter` translates observed SDK 0.1.13 callback events.
An AFTER callback does not prove a request was sent or a business operation succeeded.
Model callbacks retain `NOT_OBSERVED` transport status; force-finish records `NOT_SENT`.
SDK internal retries, token timing and implicit parallel spans are not inferred.

`ObservedFinanceProvider` preserves access/input/descriptor contracts, records the
delegate outcome and separately records returned business status. A returned FAILED
result is `RETURNED + businessStatus=FAILED`, not COMPLETED. NonAiBankingProvider
records every explicit HttpClient send attempt, including explicit reauthentication
retry. SENT means the transport API was entered, not proof of server receipt.

## Ownership and integration

- Host passes trusted request/instance/process identities and the current observation.
- Integrator callbacks run after existing authorization gates, without consuming steering.
- Same-thread nesting is explicit. Async adapters must propagate parent context themselves.
- Provider adapters add safe route templates at actual transport boundaries. Installing
  a Pack alone does not instrument a new Provider's private HTTP client.
- Sink refusal/exception increments dropped events and cannot replace a business result.
- EvidenceReceipt remains the financial execution receipt; observation is diagnostic data.

## Compatibility and scope

Existing four financial contracts and the default non-observed Provider path are retained.
The candidate event schema is separately versioned. No runtime source change, reduced
introspection, global lock-policy change, automatic re-execution or true streaming.
The initial consumer is the isolated Builder read-only Host. Task observation methods
exist as wrappers but are not a claim of full Task lifecycle acceptance.

## Acceptance

Schema negatives, callback force-finish/error cases, actual HTTP timeout/retry, returned
business failure and failing sinks are offline gates. Generated Host crash/restart,
owner-isolated historical reads and storage degradation are tested in Builder.
Real model, seven composition paths, browser timings and production rollout are separate
acceptance items and must not be inferred from unit-test success.
