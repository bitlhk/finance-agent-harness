package io.github.bitlhk.finance.harness.providers.smartaudit;

/** Verification state for evidence used by a Smart Audit rule. */
public enum AuditEvidenceStatus {
    VERIFIED,
    UNVERIFIED,
    MISSING,
    CONFLICTING,
    EXPIRED
}
