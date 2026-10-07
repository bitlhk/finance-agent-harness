# Tool attempt window candidate

The native non-streaming adapters collect bounded metadata for parsed model tool selections and native dispatch attempts. A window belongs to one Harness request ID and includes start/end timestamps, model response count, attempt IDs, names, phases and explicit gaps. No tool arguments, response body or hidden reasoning is stored here.

COMPLETE requires a normally completed invocation, at least one recognized parsed response and no collector gaps. Empty inventory alone is never evidence of zero calls. Missing/disabled capture, unknown response shapes, cancellation, overflow and late events must not prove zero. Tool selection and dispatch are separate phases, not two distinct business calls. Business success is not inferred from either.

Scope excludes internal bank operations, other processes, arbitrary external SDK plugins and streaming adapters. The Builder validates request identity, time window, schema and phases before projecting a zero-call claim. Existing financial result/body collection has a different scope and is not relabeled complete.

Controlled probes cover OpenJiuwen 0.1.15 file dispatch then a zero-call turn; AgentScope 2.0.3 zero-call, file dispatch and unknown-tool rejection. Core and Builder tests cover missing, partial, foreign-request and invalid windows. New Host templates/JARs need fresh artifact qualification before production use; old Agents and old evidence are not upgraded.
