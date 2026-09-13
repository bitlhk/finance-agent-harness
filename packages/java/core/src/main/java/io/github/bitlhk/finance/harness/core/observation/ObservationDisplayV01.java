package io.github.bitlhk.finance.harness.core.observation;

import java.util.List;

/** Versioned presentation only. Labels do not establish permission, dispatch or business success. */
public record ObservationDisplayV01(String schemaVersion, List<CapabilityLabel> capabilities,
                                    List<SourceLabel> sources) {
    public static final String VERSION = "finance-observation-display.v0.1";
    public ObservationDisplayV01 {
        if (!VERSION.equals(schemaVersion)) throw new IllegalArgumentException("Observation display version");
        capabilities = List.copyOf(capabilities); sources = List.copyOf(sources);
        if (capabilities.size() > 256 || sources.size() > 64)
            throw new IllegalArgumentException("Display limit");
        if (capabilities.stream().map(CapabilityLabel::capabilityId).distinct().count() != capabilities.size()
                || sources.stream().map(SourceLabel::providerId).distinct().count() != sources.size())
            throw new IllegalArgumentException("Duplicate display identity");
    }
    public record CapabilityLabel(String capabilityId, String packId, String version, String title) {
        public CapabilityLabel {
            ObservationRequirementsV01.token(capabilityId); ObservationRequirementsV01.token(packId);
            ObservationRequirementsV01.token(version); text(title);
        }
    }
    public record SourceLabel(String providerId, String version, List<OperationLabel> operations) {
        public SourceLabel {
            ObservationRequirementsV01.token(providerId); ObservationRequirementsV01.token(version);
            operations = List.copyOf(operations);
            if (operations.size() > 256) throw new IllegalArgumentException("Display operation limit");
            var keys = new java.util.HashSet<String>();
            for (var item : operations) if (!keys.add(item.method() + " " + item.route()))
                throw new IllegalArgumentException("Duplicate source display route");
        }
    }
    public record OperationLabel(String operationId, String method, String route, String title, String description) {
        public OperationLabel {
            ObservationRequirementsV01.token(operationId);
            if (method == null || !method.matches("[A-Z]{3,10}") || route == null
                    || !route.matches("/[A-Za-z0-9_./{}-]{0,199}"))
                throw new IllegalArgumentException("Source display needs a method and route template");
            text(title); text(description);
        }
    }
    private static void text(String text) {
        if (text == null || text.isBlank() || text.length() > 500 || text.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid bounded display text");
    }
}
