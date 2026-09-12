# ADR 0011: prepare once, register separately

Status: candidate V0.1. Extends the existing Assembler, not Core or a second installation engine.

The Java Assembler resolves assets, verifies digests, actual Java artifacts and Provider descriptors,
and freezes a PreparedSolution before registering Runtime tools. Existing assemble() delegates to
prepare() and install(), preserving its installation and Lock behavior. A read-only consumer opens
QUERY bridges from that same prepared binding and schema, without creating a Java Agent/model.

The plan says VALIDATED_NOT_RUNTIME_REGISTERED. A Python host separately records its successful
native registration. Java dependencies remain required. The plan's bridgeRuntime identifies Java
artifacts, never the Python SDK. Caller-owned Provider instances remain bound objects: swapping
the caller's provider map cannot swap a prepared binding. Pack/schema/method values are immutable.

Each query validates installed capability, input and required scopes through the existing Bridge
and Provider checks. A host may additionally require all selected scopes at session/turn boundaries;
that policy does not grant permissions, nor remove per-capability/source checks.

QUERY schema V0.1 supports inline object/type/property/required/pattern constraints and local JSON
Pointer references. It is not a claim of all JSON Schema features. URI scopes ($id), dynamic/recursive
references and nonlocal refs are rejected. Resource loading is disabled in the validator factory,
not merely filtered by the caller. Network-counter tests cover nested refs and URI declarations.

TASK remains on the existing confirmed, checkpointed installation path. Neither this plan nor
Python registration authorizes business work. New runtimes still need an Integrator and lifecycle tests.
