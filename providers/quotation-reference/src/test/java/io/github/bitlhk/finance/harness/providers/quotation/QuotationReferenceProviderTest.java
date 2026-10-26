package io.github.bitlhk.finance.harness.providers.quotation;

import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class QuotationReferenceProviderTest {
    static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");
    static ManagedProviderFactoryV01.SourceSession session(String subject, Instant expiry) {
        return new ManagedProviderFactoryV01.SourceSession("synthetic-not-a-credential", subject, "principal-a", "session-a", expiry);
    }
    static CapabilityRequestV1 request(String subject, Set<String> scopes, Map<String,Object> args,
            CapabilityRequestV1.Operation operation, String key, Map<String,Object> continuation) {
        var ctx = new FinanceContextV1(FinanceContextV1.SCHEMA_VERSION, "request-a", "trace-a", "principal-a",
            FinanceContextV1.PrincipalType.CUSTOMER, "test", "quotation", subject, "session-a",
            FinanceContextV1.AuthenticationLevel.STRONG, scopes);
        return new CapabilityRequestV1(CapabilityRequestV1.SCHEMA_VERSION, QuotationReferenceProvider.CAPABILITY,
            operation, ctx, args, key, null, continuation, null);
    }
    static CapabilityRequestV1 query() {
        return request("demo-customer-a", Set.of("quotation:read"),
            Map.of("productRef","demo-service-001","currency","CNY"), CapabilityRequestV1.Operation.QUERY, null, Map.of());
    }
    static QuotationReferenceProvider provider(Instant now, Supplier<ManagedProviderFactoryV01.SourceSession> sessions) throws Exception {
        return new QuotationReferenceProvider(QuotationReferenceConfig.load(
            QuotationReferenceConfigTest.fixture(), QuotationReferenceConfigTest.hash(QuotationReferenceConfigTest.fixture())),
            Clock.fixed(now, ZoneOffset.UTC), sessions);
    }
    @Test void returnsImmutableVersionedFactsWithNoBusinessWrite() throws Exception {
        var p = provider(NOW, () -> session("demo-customer-a", NOW.plusSeconds(60)));
        var r = p.query(query());
        assertEquals(CapabilityResultV1.Status.COMPLETED, r.status());
        assertEquals("quote-facts.v0.1", r.data().get("schemaVersion"));
        assertEquals("AVAILABLE", r.data().get("state"));
        assertThrows(UnsupportedOperationException.class, () -> r.data().put("rawText", "injected"));
        var quote = (Map<?,?>)r.data().get("quote");
        assertThrows(UnsupportedOperationException.class, quote::clear);
        assertEquals(1, p.readAttempts()); assertEquals(0, p.businessWrites());
    }
    @Test void revocationBeforeAndAfterReadDoesNotReturnFacts() throws Exception {
        var denied = provider(NOW, () -> null);
        assertEquals(Map.of(), denied.query(query()).data()); assertEquals(0, denied.readAttempts());
        var calls = new AtomicInteger();
        var revoked = provider(NOW, () -> calls.incrementAndGet() == 1
            ? session("demo-customer-a", NOW.plusSeconds(60)) : null);
        assertEquals(CapabilityResultV1.Status.FAILED, revoked.query(query()).status());
        assertEquals(1, revoked.readAttempts()); assertEquals(0, revoked.businessWrites());
        for (var source : List.of(session("other", NOW.plusSeconds(60)), session("demo-customer-a", NOW))) {
            var p = provider(NOW, () -> source);
            assertTrue(p.query(query()).data().isEmpty()); assertEquals(0, p.readAttempts());
        }
    }
    @Test void refusesUntrustedSelectorsAndAllTaskSemantics() throws Exception {
        var p = provider(NOW, () -> session("demo-customer-a", NOW.plusSeconds(60)));
        var good = query();
        for (var r : List.of(
                request("other", Set.of("quotation:read"), good.parameters(), good.operation(), null, Map.of()),
                request("demo-customer-a", Set.of(), good.parameters(), good.operation(), null, Map.of()),
                request("demo-customer-a", Set.of("quotation:read"), Map.of("productRef","unknown","currency","CNY"), good.operation(), null, Map.of()),
                request("demo-customer-a", Set.of("quotation:read"), Map.of("productRef","demo-service-001","currency","CNY","subjectRef","other"), good.operation(), null, Map.of()),
                request("demo-customer-a", Set.of("quotation:read"), good.parameters(), good.operation(), "write-key", Map.of()),
                request("demo-customer-a", Set.of("quotation:read"), good.parameters(), good.operation(), null, Map.of("resume",true)),
                request("demo-customer-a", Set.of("quotation:read"), good.parameters(), CapabilityRequestV1.Operation.TASK, null, Map.of()))) {
            assertEquals(CapabilityResultV1.Status.FAILED, p.query(r).status());
        }
        assertEquals(CapabilityResultV1.Status.FAILED, p.executeTask(good).status());
        assertEquals(0, p.readAttempts()); assertEquals(0, p.businessWrites());
    }
    @Test void expiredAndFutureQuotesNeverReturnOldPrice() throws Exception {
        for (Instant now : List.of(Instant.parse("2026-10-19T13:00:00Z"), Instant.parse("2026-10-19T10:00:00Z"))) {
            var p = provider(now, () -> session("demo-customer-a", now.plusSeconds(60)));
            var r = p.query(query());
            assertEquals(CapabilityResultV1.Status.COMPLETED, r.status());
            assertNull(r.data().get("quote"));
            assertNotEquals("AVAILABLE", r.data().get("state")); assertEquals(0, p.businessWrites());
        }
    }
    @Test void factoryIsLazyPreinstalledAndRejectsNetworkOrDependencies() throws Exception {
        assertTrue(ServiceLoader.load(ManagedProviderFactoryV01.class).stream()
            .anyMatch(p -> p.type() == QuotationReferenceFactoryV01.class));
        var path = QuotationReferenceConfigTest.fixture();
        var f = new QuotationReferenceFactoryV01(path.toAbsolutePath().toString(), QuotationReferenceConfigTest.hash(path));
        assertEquals(Set.of("quotation:read"), f.requirements().get("quotation.offer.query"));
        var connection = new ManagedProviderFactoryV01.Connection(f.connectionKind(), URI.create("reference://quotation"),
            Duration.ofSeconds(1), () -> session("demo-customer-a", NOW.plusSeconds(60)), () -> null, a -> fail("NO_HTTP"));
        assertEquals(QuotationReferenceProvider.ID, f.create(connection, Map.of()).descriptor().providerId());
        var network = new ManagedProviderFactoryV01.Connection(f.connectionKind(), URI.create("https://example.invalid"),
            Duration.ofSeconds(1), connection.currentSession(), () -> null, connection.attempts());
        assertThrows(IllegalArgumentException.class, () -> f.create(network, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new QuotationReferenceFactoryV01("relative.json","0".repeat(64)).configurationDigests());
    }
}
