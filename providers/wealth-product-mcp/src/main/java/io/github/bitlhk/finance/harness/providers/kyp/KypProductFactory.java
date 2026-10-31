package io.github.bitlhk.finance.harness.providers.kyp;

import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import io.github.bitlhk.finance.harness.mcp.*;
import java.time.Clock;
import java.util.*;
import java.util.function.*;

/** Configuration comes from reviewed installation; ServiceLoader discovery grants no source access. */
public final class KypProductFactory implements ManagedProviderFactoryV01 {
    public static final String ID = "wealth-product-mcp", CAPABILITY = "wealth.products.query", SCOPE = "wealth:products:read";
    private final PinnedMcpReader.Target target;
    private final ProductCatalog.SourcePolicy policy;
    private final Supplier<BoundFinanceProvider.Grant> grants;
    private final Predicate<Map<String,String>> audit;
    private final Clock clock;

    public KypProductFactory() {
        target = null; policy = null; grants = null; audit = null; clock = null;
    }

    public KypProductFactory(PinnedMcpReader.Target target, ProductCatalog.SourcePolicy policy,
            Supplier<BoundFinanceProvider.Grant> grants, Predicate<Map<String,String>> audit, Clock clock) {
        this.target = Objects.requireNonNull(target); this.policy = Objects.requireNonNull(policy);
        this.grants = Objects.requireNonNull(grants); this.audit = Objects.requireNonNull(audit);
        this.clock = Objects.requireNonNull(clock);
        if (!target.tool().equals("wealth_assistant_product_search"))
            throw new IllegalArgumentException("KYP_TOOL_NOT_SUPPORTED");
    }
    public String providerId() { return ID; }
    public String connectionKind() { return "wealth-product-mcp.v1"; }
    public Map<String,Set<String>> requirements() { return Map.of(CAPABILITY, Set.of(SCOPE)); }
    public Map<String,String> configurationDigests() {
        if (target == null) throw new SecurityException("KYP_LOCKED_SOURCE_REQUIRED");
        return Map.of("source", PinnedMcpReader.schemaDigest(Map.of("endpoint", target.endpoint().toString(),
                "tool", target.tool(), "schemaPin", target.inputSchemaSha256(), "timeout", target.timeout().toString(),
                "sourceId", policy.sourceId(), "salesTrust", policy.salesTrust().name(), "maximumAge", policy.maximumAge().toString(),
                "transportMode", "STATELESS_PUBLIC_NO_CREDENTIALS", "outputContract", "kyp-product-context.v1")));
    }
    public DescribedFinanceProvider create(Connection connection, Map<String,BoundFinanceProvider> dependencies) {
        if (target == null) throw new SecurityException("KYP_LOCKED_SOURCE_REQUIRED");
        if (!dependencies.isEmpty() || !connection.kind().equals(connectionKind())
                || !connection.endpoint().equals(target.endpoint()) || !connection.timeout().equals(target.timeout()))
            throw new IllegalArgumentException("KYP_CONNECTION_MISMATCH");
        return new Provider(connection);
    }

    private final class Provider implements DescribedFinanceProvider, InvocationAccess {
        private final Connection connection;
        Provider(Connection connection) { this.connection = connection; }
        public ProviderDescriptorV1 descriptor() {
            return new ProviderDescriptorV1(ProviderDescriptorV1.SCHEMA_VERSION, ID, "0.1.0-SNAPSHOT",
                    List.of(new ProviderDescriptorV1.CapabilitySupport(CAPABILITY, CapabilityRequestV1.Operation.QUERY)));
        }
        public void checkAccess(CapabilityRequestV1 request) {
            if (!CAPABILITY.equals(request.capabilityId()) || request.operation() != CapabilityRequestV1.Operation.QUERY
                    || request.context().principalType() != FinanceContextV1.PrincipalType.EMPLOYEE
                    || !request.context().purpose().equals("WEALTH_PRODUCT_REVIEW") || !request.context().scopes().contains(SCOPE))
                throw new SecurityException("KYP_CONTEXT_REJECTED");
            BoundFinanceProvider.checkContext(grants.get(), request.context());
        }
        public CapabilityResultV1 query(CapabilityRequestV1 request) {
            checkAccess(request);
            var initialGrant = grants.get();
            BoundFinanceProvider.checkContext(initialGrant, request.context());
            Runnable guard = () -> {
                checkAccess(request);
                if (!initialGrant.equals(grants.get())) throw new SecurityException("KYP_AUTHORITY_CHANGED");
            };
            if (!Set.of("keyword").containsAll(request.parameters().keySet()))
                throw new IllegalArgumentException("KYP_ARGUMENT_NOT_ALLOWED");
            Object keyword = request.parameters().getOrDefault("keyword", "");
            if (!(keyword instanceof String text) || text.length() > 120 || text.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("KYP_KEYWORD_INVALID");
            // Customer identifiers, CRM context and credentials never become source arguments.
            Map<String,Object> args = Map.of("keyword", keyword, "page", 1, "pageSize", 50);
            record(request, "STARTED", Map.of());
            try {
                byte[] bytes;
                try (var observation = new KypHttpObservation(connection, request)) {
                    bytes = new PinnedMcpReader().read(target, args, guard, observation);
                    observation.returned();
                }
                var catalog = ProductCatalog.parse(bytes, policy, clock.instant());
                guard.run();
                var productRows = new ArrayList<Map<String,Object>>();
                var allIssues = new TreeSet<String>();
                for (var product : catalog.products()) {
                    var row = new LinkedHashMap<String,Object>();
                    row.put("productId", product.id()); row.put("name", product.name());
                    row.put("sourceId", product.sourceId()); row.put("factsType", product.factsType());
                    if (product.factsAsOf() != null) row.put("factsAsOf", product.factsAsOf().toString());
                    if (product.salesAsOf() != null) row.put("salesAsOf", product.salesAsOf().toString());
                    row.put("salesTrust", product.salesTrust().name()); row.put("salesStatus", product.salesStatus());
                    row.put("riskLevel", product.riskLevel());
                    if (product.minimumAmount() != null) row.put("minimumAmount", product.minimumAmount());
                    if (product.termMonths() != null) row.put("termMonths", product.termMonths());
                    row.put("channels", product.channels().stream().sorted().toList()); row.put("issues", product.issues());
                    row.put("readyForScreening", product.eligibleForScreening());
                    allIssues.addAll(product.issues()); productRows.add(Map.copyOf(row));
                }
                var data = Map.<String,Object>of("schema", "kyp-product-context.v1", "products", List.copyOf(productRows),
                        "sourceId", policy.sourceId(), "retrievedAt", catalog.retrievedAt().toString(),
                        "normalizedContentDigest", catalog.contentDigest(), "sourceConfigurationDigest", configurationDigests().get("source"),
                        "readiness", allIssues.isEmpty() ? "READY_FOR_SCREENING" : "PARTIAL",
                        "coverage", "RETURNED_SEARCH_RESULTS_ONLY", "suitabilityAssessed", false);
                record(request, "COMPLETED", Map.of("contentDigest", catalog.contentDigest()));
                guard.run();
                return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION, request.context().requestId(),
                        request.context().traceId(), CapabilityResultV1.Status.COMPLETED,
                        "Product source returned; suitability and purchase are not approved by this query.", data,
                        List.copyOf(allIssues), List.of(), policy.sourceId(), catalog.retrievedAt(), null, null);
            } catch (RuntimeException failure) {
                record(request, "FAILED", Map.of("failureType", failure.getClass().getSimpleName()));
                throw failure;
            }
        }
        public CapabilityResultV1 executeTask(CapabilityRequestV1 request) { throw new SecurityException("KYP_READ_ONLY"); }
        private void record(CapabilityRequestV1 request, String phase, Map<String,String> extra) {
            var fields = new LinkedHashMap<>(extra);
            fields.put("phase", phase); fields.put("requestId", request.context().requestId());
            fields.put("traceId", request.context().traceId()); fields.put("providerId", ID);
            fields.put("sourceId", policy.sourceId()); fields.put("actor", request.context().principalId());
            fields.put("subjectRef", request.context().subjectRef());
            if (!audit.test(Map.copyOf(fields))) throw new IllegalStateException("KYP_AUDIT_FAILED");
        }
    }
}
