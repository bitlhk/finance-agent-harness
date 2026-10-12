package io.github.bitlhk.finance.harness.providers.smartaudit;

/**
 * The only model boundary used by Smart Audit.  Candidate hosts inject an implementation for a
 * frozen model connection; this contract deliberately exposes neither credentials nor endpoints.
 */
@FunctionalInterface
public interface SmartAuditModelPort {
    SmartAuditModelResponse assess(SmartAuditModelRequest request);
}
