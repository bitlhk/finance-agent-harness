package io.github.bitlhk.finance.harness.core;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Build-time declaration of the canonical capabilities implemented by a Provider Adapter. */
public record ProviderDescriptorV1(
        String schemaVersion,
        String providerId,
        String version,
        List<CapabilitySupport> capabilities) {
    public static final String SCHEMA_VERSION = "finance-provider.v1";

    private static final Pattern PROVIDER_ID = Pattern.compile("^[a-z][a-z0-9-]{2,63}$");
    private static final Pattern CAPABILITY_ID = Pattern.compile(
            "^[a-z][a-z0-9]*(\\.[a-z][a-z0-9-]*)+$");

    public ProviderDescriptorV1 {
        ContractChecks.schemaVersion(schemaVersion, SCHEMA_VERSION);
        providerId = matching(providerId, "providerId", PROVIDER_ID);
        version = ContractChecks.requiredText(version, "version");
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        if (capabilities.isEmpty()) {
            throw new IllegalArgumentException("capabilities must not be empty");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (CapabilitySupport capability : capabilities) {
            if (capability == null) {
                throw new IllegalArgumentException("capability must not be null");
            }
            if (!ids.add(capability.capabilityId())) {
                throw new IllegalArgumentException(
                        "duplicate provider capability: " + capability.capabilityId());
            }
        }
    }

    public boolean supports(String capabilityId, CapabilityRequestV1.Operation operation) {
        return capabilities.stream().anyMatch(capability ->
                capability.capabilityId().equals(capabilityId)
                        && capability.operation() == operation);
    }

    public record CapabilitySupport(
            String capabilityId,
            CapabilityRequestV1.Operation operation) {
        public CapabilitySupport {
            capabilityId = matching(capabilityId, "capabilityId", CAPABILITY_ID);
            if (operation == null) {
                throw new IllegalArgumentException("operation is required");
            }
        }
    }

    private static String matching(String value, String field, Pattern pattern) {
        String normalized = ContractChecks.requiredText(value, field);
        if (!pattern.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " has invalid format: " + normalized);
        }
        return normalized;
    }
}
