# ADR-0008: Candidate common wealth facts and explicit source semantics

Status: accepted engineering boundary; business definitions pending owner validation.

Keep ADR-0004/0005/0006 and the four Core envelopes. Add one runtime-neutral Java wealth module, a separately versioned strict facts schema, deterministic source mappers and a shared read-only description. Preserve both original capability IDs and payloads. No generic ontology or policy engine.

The source-specific mapper consumes an authorized result, with host-owned source semantics (namespace, asset coverage, currency when contract-backed, risk scheme, date zone). Missing metadata becomes a gap, never a default CNY/zero or cross-system risk conversion. EA's open portfolio structure requires explicit dictionary evidence before emitting money. Only the projected facts reach the common consumer/model.

New common capability is an opt-in adapter, not a rename of the two original capabilities. Its source is access-checked on every invocation. Binding substitution is demonstrated with controlled sources under equal grants; two real source applications have different authorization prerequisites and remain independently pending.

Host grants are deployment trust boundaries, not JSON fields supplied by the model. Host confirmation and existing checkpoint mechanism remain separate from fact availability and investment suitability.
