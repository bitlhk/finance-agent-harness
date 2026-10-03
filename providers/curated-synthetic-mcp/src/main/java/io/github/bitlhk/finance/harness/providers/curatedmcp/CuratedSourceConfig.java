package io.github.bitlhk.finance.harness.providers.curatedmcp;

import io.github.bitlhk.finance.harness.core.HarnessJson;
import java.net.URI;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Host-owned source configuration, never tool arguments or bank credentials. */
public record CuratedSourceConfig(String schemaVersion, URI endpoint, String contractVersion,
        Map<String, String> toolSchemaPins, Map<String, Subject> subjects, RuntimePolicy runtimePolicy) {
    public record RuntimePolicy(String mode, String sourceIdentitySha256) {
        public RuntimePolicy {
            if (!"STATELESS_BOUNDED".equals(mode) || sourceIdentitySha256 == null
                    || !sourceIdentitySha256.matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("CURATED_SOURCE_RUNTIME_INVALID");
        }
    }
    /** Legacy source only for isolated Provider regression, not managed Factory admission. */
    public CuratedSourceConfig(String schemaVersion, URI endpoint, String contractVersion,
            Map<String, String> toolSchemaPins, Map<String, Subject> subjects) {
        this(schemaVersion, endpoint, contractVersion, toolSchemaPins, subjects, null);
    }
    public record Subject(String customerId, Map<String, Set<String>> resources, String customerSelection) {
        public Subject(String customerId, Map<String, Set<String>> resources) {
            this(customerId, resources, "BOUND_SUBJECT");
        }
        public Subject {
            customerSelection = customerSelection == null ? "BOUND_SUBJECT" : customerSelection;
            if (!Set.of("BOUND_SUBJECT", "SYNTHETIC_ALLOWLIST").contains(customerSelection)
                    || "SYNTHETIC_ALLOWLIST".equals(customerSelection) && customerId != null)
                throw new IllegalArgumentException("CURATED_CUSTOMER_SELECTION_INVALID");
            var copy = new LinkedHashMap<String, Set<String>>();
            resources.forEach((k, v) -> copy.put(k, Set.copyOf(v)));
            resources = Map.copyOf(copy);
        }
    }
    public CuratedSourceConfig {
        if (!("curated-synthetic-source.v1".equals(schemaVersion) && runtimePolicy == null
                || Set.of("curated-synthetic-source.v2", "curated-synthetic-source.v3").contains(schemaVersion) && runtimePolicy != null)
                || !"1.0.0".equals(contractVersion)
                || endpoint == null || !"http".equals(endpoint.getScheme())
                || !Set.of("127.0.0.1", "[::1]").contains(endpoint.getHost())
                || endpoint.getPort() < 1 || endpoint.getUserInfo() != null || endpoint.getQuery() != null
                || endpoint.getFragment() != null || !"/mcp".equals(endpoint.getPath()))
            throw new IllegalArgumentException("CURATED_SOURCE_CONFIG_REJECTED");
        toolSchemaPins = Map.copyOf(toolSchemaPins);
        subjects = Map.copyOf(subjects);
        if (subjects.isEmpty() || toolSchemaPins.isEmpty()) throw new IllegalArgumentException("CURATED_ALLOWLIST_EMPTY");
        toolSchemaPins.forEach((tool, pin) -> {
            CuratedTools.named(tool);
            if (!pin.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("CURATED_SCHEMA_PIN_INVALID");
        });
        var pins = toolSchemaPins;
        subjects.forEach((id, subject) -> {
            if (id.isBlank() || subject.resources().isEmpty()) throw new IllegalArgumentException("CURATED_SUBJECT_INVALID");
            if ("SYNTHETIC_ALLOWLIST".equals(subject.customerSelection()) && !"curated-synthetic-source.v3".equals(schemaVersion))
                throw new IllegalArgumentException("CURATED_CUSTOMER_SELECTION_VERSION_REQUIRED");
            subject.resources().forEach((name, refs) -> {
                var tool = CuratedTools.named(name);
                if (!pins.containsKey(name) || refs.isEmpty() || !tool.resources().containsAll(refs))
                    throw new IllegalArgumentException("CURATED_RESOURCE_INVALID");
                if (tool.selector().equals("customer_id") && "BOUND_SUBJECT".equals(subject.customerSelection()) && (subject.customerId() == null
                        || !refs.equals(Set.of(subject.customerId()))))
                    throw new IllegalArgumentException("CURATED_CUSTOMER_MAPPING_INVALID");
            });
        });
    }
    public static CuratedSourceConfig load(Path path, String pin) {
        try {
            if (pin == null || !pin.matches("[a-f0-9]{64}") || !path.isAbsolute()
                    || !path.normalize().equals(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(path) > 262144) throw new IllegalArgumentException();
            for (Path p = path; p != null; p = p.getParent()) if (Files.isSymbolicLink(p)) throw new IllegalArgumentException();
            byte[] bytes = Files.readAllBytes(path);
            if (!sha(bytes).equals(pin)) throw new IllegalArgumentException();
            var mapper = HarnessJson.mapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            return mapper.readValue(bytes, CuratedSourceConfig.class);
        } catch (Exception failure) { throw new IllegalArgumentException("CURATED_CONFIG_NOT_PINNED"); }
    }
    static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    String canonicalDigest() {
        var policies = new TreeMap<String, Object>();
        subjects.forEach((subject, policy) -> {
            var resources = new TreeMap<String, Object>();
            policy.resources().forEach((tool, refs) -> resources.put(tool, refs.stream().sorted().toList()));
            var fields = new TreeMap<String, Object>();
            if (policy.customerId() != null) fields.put("customerId", policy.customerId());
            if (!"BOUND_SUBJECT".equals(policy.customerSelection())) fields.put("customerSelection", policy.customerSelection());
            fields.put("resources", resources);policies.put(subject, fields);
        });
        var config = new TreeMap<String, Object>(Map.of("schemaVersion", schemaVersion, "endpoint", endpoint.toString(),
                "contractVersion", contractVersion, "toolSchemaPins", toolSchemaPins, "subjects", policies));
        if (runtimePolicy != null) config.put("runtimePolicy", Map.of("mode", runtimePolicy.mode(),
                "sourceIdentitySha256", runtimePolicy.sourceIdentitySha256()));
        return CuratedTools.schemaPin(config);
    }
    @Override public String toString() { return "CuratedSourceConfig[redacted]"; }
}
