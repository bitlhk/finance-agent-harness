package io.github.bitlhk.finance.harness.core;

import java.util.Set;

/** Identifies who is requesting a financial capability, through which channel, and for what purpose. */
public record FinanceContextV1(
        String schemaVersion,
        String requestId,
        String traceId,
        String principalId,
        PrincipalType principalType,
        String channel,
        String purpose,
        String subjectRef,
        String sessionRef,
        AuthenticationLevel authenticationLevel,
        Set<String> scopes) {

    public static final String SCHEMA_VERSION = "finance-context.v1";

    public FinanceContextV1 {
        ContractChecks.schemaVersion(schemaVersion, SCHEMA_VERSION);
        requestId = ContractChecks.requiredText(requestId, "requestId");
        traceId = ContractChecks.requiredText(traceId, "traceId");
        principalId = ContractChecks.requiredText(principalId, "principalId");
        if (principalType == null) {
            throw new IllegalArgumentException("principalType is required");
        }
        channel = ContractChecks.requiredText(channel, "channel");
        purpose = ContractChecks.requiredText(purpose, "purpose");
        subjectRef = ContractChecks.optionalText(subjectRef);
        sessionRef = ContractChecks.optionalText(sessionRef);
        authenticationLevel = authenticationLevel == null ? AuthenticationLevel.UNKNOWN : authenticationLevel;
        scopes = ContractChecks.immutableSet(scopes);
    }

    public enum PrincipalType {
        CUSTOMER,
        EMPLOYEE,
        SYSTEM
    }

    public enum AuthenticationLevel {
        UNKNOWN,
        BASIC,
        STRONG
    }
}
