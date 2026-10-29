# ADR 0011: Optional native metadata observation

Status: accepted design for FEAT-020; implementation and qualification pending.

The existing finance-observation.v0.1 contract and authorization/Provider evidence remain unchanged.
Native metadata is separately versioned as finance.native-observation.v1. Its initial integration is opt-in
OpenJiuwen Java 0.1.16 / OpenTelemetry 1.64.0. The root SDK default stays 0.1.15.

Host owns request, instance, process and runtime-lock identities. Correlation uses explicitly propagated
context, not names or nearby timestamps. sdkTraceId/sdkSpanId are native OTel IDs; frameworkTraceId,
when present, is the SDK framework identity, not a substituted OTel trace ID.

Whitelist projection happens synchronously before any new queue, sink, disk or exporter. No arbitrary
attributes, span names, exception descriptions, URLs, prompts, responses, reasoning or tool bodies.
Reported input/output usage is optional; do not derive total usage or costs.

The SDK's default TRACE writer can enqueue body-bearing snapshots before extension handlers.
The pilot must explicitly disable that raw TRACE channel through public stream modes before tracer
initialization; filtering the downstream export is not enough. This public API behavior has an isolated
SDK probe, but the actual Agent path still requires qualification.

START means PENDING. END RETURNED describes technical completion, never banking success. OTel ERROR
does not identify an independent root cause. Epoch nanoseconds are decimal strings to avoid JS integer
precision loss; durations are bounded integers. Preserve clock source and process grouping. Consumers
must reject end-before-start, mismatched duration and foreign request/instance/lock bindings.

Financial authorization and receipts remain mandatory in off/sample/full. Sampling is whole-request;
coverage contains no span data when off. Native and legacy execution counts must not be summed.
Only HOST_EXPLICIT_BINDING permits financial links. Unmapped sources remain separate.

Metadata limits: 16KiB per record, 256KiB per request, 256 queued entries. Reject overflowing diagnostic
records with bounded counters. Terminated runs cannot restart; late data only contributes coverage gaps.
No default console/OTLP exporter, global provider mutation, new receiver server or production claim.

Schema fixtures cover structural positives/negatives; SDK/Host tests must additionally verify semantic
binding, privacy, cancellation, asynchronous gaps, fault handling and compatibility. No qualification is
inherited from older collectors or this design document.
