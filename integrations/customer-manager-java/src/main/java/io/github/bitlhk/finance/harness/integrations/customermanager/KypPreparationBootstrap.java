package io.github.bitlhk.finance.harness.integrations.customermanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.StreamReadFeature;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.core.FinanceContextV1;
import io.github.bitlhk.finance.harness.core.evidence.ScenarioEvidence;
import io.github.bitlhk.finance.harness.core.spi.*;
import io.github.bitlhk.finance.harness.examples.wealth.PreparationAccess;
import io.github.bitlhk.finance.harness.mcp.PinnedMcpReader;
import io.github.bitlhk.finance.harness.providers.kyp.*;
import io.github.bitlhk.finance.harness.providers.customercrm.CustomerCrmFactoryV01;
import io.github.bitlhk.finance.harness.providers.customercrm.CustomerCrmProvider;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/** The installed product pack selects the source; a separate current policy grants its use. */
final class KypPreparationBootstrap {
    static final String SOURCE = "assets/capability-packs/wealth-product-facts/source.v1.json";
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = HarnessJson.mapper().copy()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature());

    static PreparationAccess open(ManagedScenarioExtensionV01.Context context, String session,
            PreparationAccess crm, Path authorityFile) throws Exception {
        Path root = context.artifactRoot().toAbsolutePath().normalize();
        JsonNode lock = read(root.resolve("service-lock.json"), 2_000_000);
        boolean selected = false;
        for (var capability : lock.path("capabilities")) selected |= KypProductFactory.CAPABILITY.equals(capability.asText());
        if (!selected) return crm;
        Source installed = source(root, lock);
        var target = installed.target();
        var policy = installed.policy();
        String connectionRef = installed.connectionRef();
        if (authorityFile == null) throw new SecurityException("KYP_READ_AUTHORITY_REQUIRED");
        Path authority = authorityFile.toAbsolutePath().normalize();
        if (authority.startsWith(root) || authority.toRealPath().startsWith(root.toRealPath()))
            throw new SecurityException("KYP_READ_AUTHORITY_OUTSIDE_ARTIFACT_REQUIRED");
        Supplier<BoundFinanceProvider.Grant> grants = () -> grant(authority, context.ownerRef(), session,
                connectionRef, policy.sourceId());
        grants.get();
        var audit = new ScenarioEvidence(context.privateStateRoot().resolve("service-preparation-reports"),
                ScenarioEvidence.host(context));
        var factory = new KypProductFactory(target, policy, grants, event -> {
            var current = grants.get();
            if (!current.principalId().equals(event.get("actor")) || !current.subjects().contains(event.get("subjectRef")))
                throw new SecurityException("KYP_AUDIT_BINDING_INVALID");
            var record = audit.begin(event.get("requestId"), context.ownerRef(), event.get("subjectRef"),
                    "WEALTH_PRODUCT_REVIEW", KypProductFactory.CAPABILITY, Map.of(),
                    current, "INDEPENDENT_PRODUCT_READ_POLICY");
            if (!"STARTED".equals(event.get("phase"))) record.invoked();
            if ("COMPLETED".equals(event.get("phase"))) record.returned();
            record.check("KYP_" + event.get("phase"), () -> {
                if (!current.equals(grants.get())) throw new SecurityException("KYP_AUTHORITY_CHANGED");
            }, event);
            return true;
        }, Clock.systemUTC());
        var connection = new ManagedProviderFactoryV01.Connection(factory.connectionKind(), target.endpoint(),
                target.timeout(), () -> { throw new SecurityException("KYP_PUBLIC_SOURCE_HAS_NO_CREDENTIAL"); },
                context.currentObservation(), ignored -> {});
        var registry = new ManagedProviderFactoriesV01(List.of(factory));
        var providers = registry.createReferenced(Map.of(KypProductFactory.ID,
                new ManagedProviderFactoriesV01.ConnectionReference(connectionRef, connection.kind())),
                Map.of(connectionRef, connection), Map.of(KypProductFactory.ID, grants),
                Set.of(KypProductFactory.ID), registry.configurationDigests(Set.of(KypProductFactory.ID)),
                Map.of(KypProductFactory.ID, ManagedProviderFactoriesV01.connectionDigest(connection)));
        return new KypPreparationAccess(crm, providers.get(KypProductFactory.ID), session, Clock.systemUTC(), grants);
    }

    static Map<String, FinanceProvider> installation(Path root) throws Exception {
        var installed = source(root, read(root.resolve("service-lock.json"), 2_000_000));
        Supplier<BoundFinanceProvider.Grant> deny = () -> { throw new SecurityException("PLAN_ONLY_NO_GRANT"); };
        var kyp = new KypProductFactory(installed.target(), installed.policy(), deny, ignored -> false, Clock.systemUTC());
        var crm = new CustomerCrmFactoryV01();
        var kypConnection = new ManagedProviderFactoryV01.Connection(kyp.connectionKind(), installed.target().endpoint(),
                installed.target().timeout(), () -> { throw new SecurityException("PLAN_ONLY_NO_SESSION"); }, () -> null, ignored -> {});
        var crmConnection = new ManagedProviderFactoryV01.Connection(crm.connectionKind(), URI.create("http://127.0.0.1:1"),
                Duration.ofSeconds(1), () -> { throw new SecurityException("PLAN_ONLY_NO_SESSION"); }, () -> null, ignored -> {});
        var registry = new ManagedProviderFactoriesV01(List.of(crm, kyp));
        var ids = Set.of(crm.providerId(), kyp.providerId());
        return registry.createBound(Map.of(crm.providerId(), new ManagedProviderFactoriesV01.Binding(crmConnection, deny),
                kyp.providerId(), new ManagedProviderFactoriesV01.Binding(kypConnection, deny)), ids,
                registry.configurationDigests(ids), Map.of(crm.providerId(), ManagedProviderFactoriesV01.connectionDigest(crmConnection),
                        kyp.providerId(), ManagedProviderFactoriesV01.connectionDigest(kypConnection)));
    }

    private record Source(String connectionRef, PinnedMcpReader.Target target, ProductCatalog.SourcePolicy policy) {}
    private static Source source(Path root, JsonNode lock) throws Exception {
        Set<String> capabilities = new HashSet<>(); lock.path("capabilities").forEach(v -> capabilities.add(v.asText()));
        if (!capabilities.equals(Set.of(CustomerCrmProvider.CAPABILITY, CustomerCrmProvider.ALLOCATION_CAPABILITY, KypProductFactory.CAPABILITY)))
            throw new SecurityException("KYP_COMPOSITION_NOT_SUPPORTED");
        JsonNode plan = locked(root, lock, "component-plan.json");
        var providers = new HashSet<String>(); plan.path("providerConnections").fieldNames().forEachRemaining(providers::add);
        if (!providers.equals(Set.of(CustomerCrmProvider.PROVIDER_ID, KypProductFactory.ID)))
            throw new SecurityException("KYP_COMPOSITION_NOT_SUPPORTED");
        var crmBinding = plan.path("providerConnections").path(CustomerCrmProvider.PROVIDER_ID);
        if (crmBinding.size() != 2 || !"crm-current".equals(crmBinding.path("connectionRef").asText())
                || !"customer-crm-readonly.v1".equals(crmBinding.path("kind").asText()))
            throw new SecurityException("CRM_INSTALLED_SOURCE_INVALID");
        JsonNode binding = plan.path("providerConnections").path(KypProductFactory.ID);
        if (!KypProductFactory.ID.equals(plan.path("capabilityBindings").path(KypProductFactory.CAPABILITY).asText())
                || !binding.path("connectionRef").asText().matches("[a-z][a-z0-9-]{0,63}")
                || !"wealth-product-mcp.v1".equals(binding.path("kind").asText()) || binding.size() != 2)
            throw new SecurityException("KYP_INSTALLED_BINDING_INVALID");
        JsonNode source = locked(root, lock, SOURCE);
        fields(source, Set.of("schemaVersion", "connectionRef", "endpoint", "tool", "inputSchemaSha256",
                "timeoutMillis", "sourceId", "salesTrust", "maximumAgeSeconds"));
        if (!"finance-kyp-source.v1".equals(text(source, "schemaVersion"))
                || !binding.path("connectionRef").asText().equals(text(source, "connectionRef"))
                || !"DEMO".equals(text(source, "salesTrust")))
            throw new SecurityException("KYP_INSTALLED_SOURCE_INVALID");
        var target = new PinnedMcpReader.Target(URI.create(text(source, "endpoint")), text(source, "tool"),
                text(source, "inputSchemaSha256"), Duration.ofMillis(positive(source, "timeoutMillis", 30_000)));
        var policy = new ProductCatalog.SourcePolicy(text(source, "sourceId"), ProductCatalog.SalesTrust.DEMO,
                Duration.ofSeconds(positive(source, "maximumAgeSeconds", 31_536_000)));
        return new Source(text(source, "connectionRef"), target, policy);
    }

    private static BoundFinanceProvider.Grant grant(Path file, String owner, String session, String connection, String source) {
        try {
            JsonNode policy = read(file, 131_072);
            fields(policy, Set.of("schemaVersion", "revision", "grants"));
            if (!"finance-product-read-authority.v1".equals(text(policy, "schemaVersion")) || !policy.path("grants").isArray())
                throw new SecurityException("KYP_READ_AUTHORITY_INVALID");
            String revision = text(policy, "revision");
            JsonNode match = null;
            Set<String> keys = new HashSet<>();
            for (var item : policy.path("grants")) {
                fields(item, Set.of("ownerRef", "principalId", "connectionRef", "sourceId", "subjects", "expiresAt", "scopes", "purposes"));
                String key = text(item, "ownerRef") + "\n" + text(item, "connectionRef");
                if (!keys.add(key)) throw new SecurityException("KYP_READ_AUTHORITY_AMBIGUOUS");
                if (owner.equals(item.path("ownerRef").asText()) && connection.equals(item.path("connectionRef").asText())) match = item;
            }
            if (match == null || !source.equals(text(match, "sourceId"))) throw new SecurityException("KYP_READ_AUTHORITY_REJECTED");
            var scopes = strings(match.path("scopes"));
            var purposes = strings(match.path("purposes"));
            if (!scopes.equals(Set.of(KypProductFactory.SCOPE)) || !purposes.equals(Set.of("WEALTH_PRODUCT_REVIEW")))
                throw new SecurityException("KYP_READ_AUTHORITY_REJECTED");
            Instant expiry = Instant.parse(text(match, "expiresAt"));
            if (!expiry.isAfter(Instant.now())) throw new SecurityException("KYP_READ_AUTHORITY_EXPIRED");
            return new BoundFinanceProvider.Grant(text(match, "principalId"), FinanceContextV1.PrincipalType.EMPLOYEE,
                    strings(match.path("subjects")), purposes, scopes, session,
                    revision + ":" + ScenarioEvidence.digest(match), expiry);
        } catch (SecurityException denied) { throw denied; }
        catch (Exception invalid) { throw new SecurityException("KYP_READ_AUTHORITY_INVALID"); }
    }

    private static Set<String> strings(JsonNode node) {
        if (!node.isArray() || node.isEmpty()) throw new SecurityException("KYP_READ_AUTHORITY_INVALID");
        var values = new HashSet<String>();
        for (var value : node) if (!value.isTextual() || value.asText().isBlank() || !values.add(value.asText()))
            throw new SecurityException("KYP_READ_AUTHORITY_INVALID");
        return Set.copyOf(values);
    }
    private static JsonNode locked(Path root, JsonNode lock, String relative) throws Exception {
        Path file = root.resolve(relative);
        byte[] bytes = bytes(file, 131_072);
        String actual = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        if (!actual.equals(lock.path("files").path(relative).asText()) || !file.toRealPath().startsWith(root.toRealPath()))
            throw new SecurityException("KYP_INSTALLED_FILE_NOT_LOCKED");
        return JSON.readTree(bytes);
    }
    private static JsonNode read(Path path, int limit) throws Exception { return JSON.readTree(bytes(path, limit)); }
    private static byte[] bytes(Path path, int limit) throws Exception {
        Path normalized = path.toAbsolutePath().normalize();
        for (Path part = normalized; part != null; part = part.getParent())
            if (Files.isSymbolicLink(part)) throw new SecurityException("KYP_FILE_LINK_REJECTED");
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) throw new SecurityException("KYP_FILE_INVALID");
        try (var channel = FileChannel.open(normalized, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long size = channel.size();
            if (size < 1 || size > limit) throw new SecurityException("KYP_FILE_SIZE_INVALID");
            ByteBuffer buffer = ByteBuffer.allocate(limit + 1);
            while (buffer.hasRemaining() && channel.read(buffer) > 0) { }
            if (buffer.position() > limit || channel.size() != size || buffer.position() != size)
                throw new SecurityException("KYP_FILE_CHANGED");
            return Arrays.copyOf(buffer.array(), buffer.position());
        }
    }
    private static void fields(JsonNode node, Set<String> expected) {
        var actual = new HashSet<String>(); node.fieldNames().forEachRemaining(actual::add);
        if (!node.isObject() || !expected.equals(actual)) throw new SecurityException("KYP_CONFIG_FIELDS_INVALID");
    }
    private static String text(JsonNode node, String key) {
        if (!node.path(key).isTextual() || node.path(key).asText().isBlank()) throw new SecurityException("KYP_CONFIG_VALUE_INVALID");
        return node.path(key).asText();
    }
    private static long positive(JsonNode node, String key, long maximum) {
        if (!node.path(key).isIntegralNumber() || !node.path(key).canConvertToLong()
                || node.path(key).asLong() < 1 || node.path(key).asLong() > maximum)
            throw new SecurityException("KYP_CONFIG_VALUE_INVALID");
        return node.path(key).asLong();
    }
}
