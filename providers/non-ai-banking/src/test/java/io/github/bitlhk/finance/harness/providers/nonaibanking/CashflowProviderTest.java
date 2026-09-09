package io.github.bitlhk.finance.harness.providers.nonaibanking;

import com.fasterxml.jackson.core.type.TypeReference;
import com.sun.net.httpserver.HttpServer;
import io.github.bitlhk.finance.harness.cashflow.*;
import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.BoundFinanceProvider;
import org.junit.jupiter.api.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static io.github.bitlhk.finance.harness.cashflow.CashflowFactsV1.*;
import static org.junit.jupiter.api.Assertions.*;

class CashflowProviderTest {
    private static final Instant NOW = Instant.parse("2026-09-02T00:00:00Z");
    private HttpServer server;
    private final AtomicInteger reads = new AtomicInteger(), policies = new AtomicInteger(), writes = new AtomicInteger();
    private AtomicReference<BoundFinanceProvider.Grant> grant;
    private AtomicReference<NonAiBankingProvider.HostSession> session;
    private BoundFinanceProvider provider;
    private Map<String,Object> data, policy;
    private String bodyOverride;

    @BeforeEach void setup() throws Exception {
        data = fixture("summary"); policy = fixture("summary-policy");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            boolean isPolicy = exchange.getRequestURI().getPath().endsWith("/readiness");
            if (!"GET".equals(exchange.getRequestMethod())) writes.incrementAndGet();
            else if (isPolicy) policies.incrementAndGet(); else reads.incrementAndGet();
            String body = isPolicy ? json(policy) : bodyOverride != null ? bodyOverride
                    : json(Map.of("status", "SUCCESS", "data", data,
                    "sourceSystem", "controlled-cashflow", "asOf", "2026-09-01T01:00:00Z"));
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        session = new AtomicReference<>(new NonAiBankingProvider.HostSession("synthetic-bank-session", "customer-a",
                "customer-a", "session-a", Instant.now().plusSeconds(60)));
        var source = new NonAiBankingProvider(NonAiBankingProvider.Config.hostSession(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), Duration.ofSeconds(3), session::get));
        grant = new AtomicReference<>(grant(Set.of("cashflow:read")));
        provider = new BoundFinanceProvider(source, grant::get, Map.of(
                NonAiBankingProvider.TRANSACTION_SUMMARY_QUERY, Set.of("cashflow:read"),
                NonAiBankingProvider.SPENDING_ANALYSIS_QUERY, Set.of("cashflow:read")));
    }
    @AfterEach void stop() { server.stop(0); assertEquals(0, writes.get()); }

    @Test void summaryUsesBankPolicyCurrencyAndDoesNotAddCardBills() {
        var result = provider.query(request(Map.of("period", "2026-08")));
        assertEquals(CapabilityResultV1.Status.COMPLETED, result.status());
        var facts = facts(result);
        assertEquals("8749.50", facts.totals().netCashflow());
        assertEquals(Coverage.OWN_BANK_ACCOUNT_STATEMENT, facts.coverage());
        assertTrue(facts.gaps().contains("ACCOUNT_LIST_LIMIT_100"));
        assertTrue(facts.gaps().contains("SOURCE_BOOKED_STATUS_NOT_VERIFIED"));
        assertEquals(CashflowConsumer.Completeness.PARTIAL, CashflowConsumer.describe(facts).completeness());
        assertFalse(json(result).contains("synthetic-private-account"));
        assertEquals(1, reads.get()); assertEquals(1, policies.get());
    }
    @Test void analysisAndSummaryMayDifferWithoutFalseReconciliation() throws Exception {
        var summary = facts(provider.query(request(Map.of("period", "2026-08"))));
        data = fixture("analysis"); policy = fixture("analysis-policy");
        var result = provider.query(request(NonAiBankingProvider.SPENDING_ANALYSIS_QUERY, Map.of("period", "2026-08"), "customer-a"));
        assertEquals(CapabilityResultV1.Status.COMPLETED, result.status());
        var analysis = facts(result);
        assertEquals("8999.50", analysis.totals().netCashflow());
        assertEquals("8999.50", analysis.trend().getLast().totals().netCashflow());
        assertFalse(CashflowConsumer.comparable(summary, analysis));
        assertFalse(json(result).contains("DO_NOT_EXPOSE_RAW"));
    }
    @Test void absentPeriodDoesNotInventDefaultOrReadBank() {
        for (var args : List.of(Map.<String,Object>of(), Map.<String,Object>of("period", "")))
            assertEquals(CapabilityResultV1.Status.NEED_USER, provider.query(request(args)).status());
        assertEquals(0, reads.get()); assertEquals(0, policies.get());
    }
    @Test void wrongMonthAndModelIdentityFieldsNeverReachBank() {
        for (var args : List.of(Map.<String,Object>of("period", "2026-13"), Map.<String,Object>of("period", "last month"),
                Map.<String,Object>of("period", 202608), Map.<String,Object>of("period", "2026-08", "profile", "admin")))
            assertEquals(CapabilityResultV1.Status.FAILED, provider.query(request(args)).status());
        assertThrows(SecurityException.class, () -> provider.query(request(Map.of("period", "2026-08", "customerRef", "customer-b"))));
        assertEquals(0, reads.get()); assertEquals(0, policies.get());
    }
    @Test void missingScopesCannotBeGrantedByPackOrContext() {
        grant.set(grant(Set.of()));
        assertThrows(SecurityException.class, () -> provider.query(request(Map.of("period", "2026-08"))));
        assertEquals(0, reads.get()); assertEquals(0, policies.get());
    }
    @Test void changedSubjectOrRevokedGrantRejectBeforeHttp() {
        assertThrows(SecurityException.class, () -> provider.query(request(NonAiBankingProvider.TRANSACTION_SUMMARY_QUERY,
                Map.of("period", "2026-08"), "customer-b")));
        assertEquals(CapabilityResultV1.Status.COMPLETED, provider.query(request(Map.of("period", "2026-08"))).status());
        grant.set(null);
        assertThrows(SecurityException.class, () -> provider.query(request(Map.of("period", "2026-08"))));
        assertEquals(1, reads.get()); assertEquals(1, policies.get());
    }
    @Test void actualSourceSessionMustMatchHostSubjectAndSession() {
        session.set(new NonAiBankingProvider.HostSession("bank-b", "customer-b", "customer-a", "session-a", Instant.now().plusSeconds(60)));
        assertEquals(CapabilityResultV1.Status.FAILED, provider.query(request(Map.of("period", "2026-08"))).status());
        assertEquals(0, reads.get()); assertEquals(0, policies.get());
    }
    @Test void unsupportedCurrencyAndWritePolicyFailBeforeCustomerRead() throws Exception {
        for (var entry : Map.of("currency", "USD", "ledgerWriteAllowed", true, "summaryVersion", "future-v9").entrySet()) {
            policy = fixture("summary-policy"); policyRow(policy).put(entry.getKey(), entry.getValue());
            assertEquals(CapabilityResultV1.Status.FAILED, provider.query(request(Map.of("period", "2026-08"))).status());
        }
        assertEquals(0, reads.get()); assertEquals(3, policies.get());
    }
    @Test void incompleteFactsAreNotZeroAndAreNotExecutionUnknown() {
        data.remove("totalExpense"); data.remove("asOf");
        var result = provider.query(request(Map.of("period", "2026-08")));
        assertEquals(CapabilityResultV1.Status.COMPLETED, result.status());
        assertNull(facts(result).totals().expense());
        assertEquals(CashflowConsumer.Completeness.PARTIAL, CashflowConsumer.describe(facts(result)).completeness());
    }
    @Test void malformedResponsesNeverExposeSourceBody() {
        for (String body : List.of("broken-SECRET", "{\"status\":\"SUCCESS\",\"data\":\"SECRET\"}")) {
            bodyOverride = body;
            var result = provider.query(request(Map.of("period", "2026-08")));
            assertEquals(CapabilityResultV1.Status.FAILED, result.status());
            assertFalse(json(result).contains("SECRET"));
        }
    }
    @Test void sourcePeriodAndTotalsConflictsRejectInsteadOfPromptRepair() throws Exception {
        for (var entry : Map.of("period", "2026-07", "netCashflow", "100.00").entrySet()) {
            data = fixture("summary"); data.put(entry.getKey(), entry.getValue());
            var result = provider.query(request(Map.of("period", "2026-08")));
            assertEquals(CapabilityResultV1.Status.FAILED, result.status()); assertTrue(result.data().isEmpty());
        }
    }
    @Test void precisionAndZeroArePreserved() {
        data.put("totalIncome", new java.math.BigDecimal("90071992547409.91"));
        data.put("totalExpense", "0.00"); data.put("netCashflow", "90071992547409.91");
        assertEquals("90071992547409.91", facts(provider.query(request(Map.of("period", "2026-08")))).totals().income());
        data.put("totalIncome", "0.00"); data.put("netCashflow", "0.00");
        var zero = facts(provider.query(request(Map.of("period", "2026-08"))));
        assertEquals("0.00", zero.totals().income());
        assertFalse(zero.gaps().contains("TOTALS_NOT_RETURNED"));
    }
    @Test void mapperRejectsInexactMoneyCategoryConflictsAndBadTrend() throws Exception {
        data.put("totalIncome", 10000.0);
        assertThrows(IllegalArgumentException.class, () -> map(Section.SUMMARY, data, policy));
        data = fixture("analysis"); policy = fixture("analysis-policy");
        data.put("totalIncome", "1.00");
        assertThrows(IllegalArgumentException.class, () -> map(Section.ANALYSIS, data, policy));
        data = fixture("analysis"); data.put("trend", List.of());
        assertThrows(IllegalArgumentException.class, () -> map(Section.ANALYSIS, data, policy));
        data = fixture("analysis"); data.remove("categories"); data.remove("trend");
        assertEquals(CashflowConsumer.Completeness.PARTIAL, CashflowConsumer.describe(map(Section.ANALYSIS, data, policy)).completeness());
    }
    @Test void unclassifiedTransactionsRetainAmountsButExposeMissingLabels() throws Exception {
        data = fixture("analysis"); policy = fixture("analysis-policy");
        data.put("categories", List.of(Map.of("category", "UNCLASSIFIED", "income", "10000.00", "expense", "1000.50",
                "transactionCount", 4, "classificationVersion", "UNCLASSIFIED")));
        var facts = map(Section.ANALYSIS, data, policy);
        assertEquals("1000.50", facts.categories().getFirst().totals().expense());
        assertTrue(facts.gaps().contains("CLASSIFICATION_NOT_RETURNED"));
        assertEquals(CashflowConsumer.Completeness.PARTIAL, CashflowConsumer.describe(facts).completeness());
    }
    @Test void conflictingTrendIsNotMisrepresentedAsCoverageDifference() throws Exception {
        data = fixture("analysis"); policy = fixture("analysis-policy");
        @SuppressWarnings("unchecked") var trend = (List<Map<String,Object>>) data.get("trend");
        trend.getLast().put("expense", "1001.50"); trend.getLast().put("netCashflow", "8998.50");
        var facts = map(Section.ANALYSIS, data, policy);
        assertTrue(facts.gaps().contains("TREND_TOTALS_CONFLICT"));
        assertEquals(CashflowConsumer.Completeness.PARTIAL, CashflowConsumer.describe(facts).completeness());
        assertEquals("8999.50", facts.totals().netCashflow());
        assertEquals("8998.50", facts.trend().getLast().totals().netCashflow());
    }
    private static String json(Object value) {
        try { return HarnessJson.mapper().writeValueAsString(value); }
        catch (java.io.IOException error) { throw new IllegalStateException(error); }
    }
    private static CashflowFactsV1 map(Section section, Map<String,Object> data, Map<String,Object> policy) {
        return CashflowMapper.map(section, "2026-08", "customer-a", "controlled-bank", data, policy, NOW);
    }
    private static Map<String,Object> fixture(String name) throws Exception {
        return HarnessJson.mapper().readValue(Files.readString(Path.of("../../conformance/fixtures/cashflow/source/" + name + ".json")), new TypeReference<>() {});
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> policyRow(Map<String,Object> policy) {
        return ((List<Map<String,Object>>)policy.get("policies")).getFirst();
    }
    private static CashflowFactsV1 facts(CapabilityResultV1 result) {
        return HarnessJson.mapper().convertValue(result.data().get("facts"), CashflowFactsV1.class);
    }
    private static BoundFinanceProvider.Grant grant(Set<String> scopes) {
        return new BoundFinanceProvider.Grant("customer-a", FinanceContextV1.PrincipalType.CUSTOMER,
                Set.of("customer-a"), Set.of("CASHFLOW_DESCRIPTION"), scopes, "session-a", "controlled-host-authority", Instant.now().plusSeconds(60));
    }
    private static CapabilityRequestV1 request(Map<String,Object> args) {
        return request(NonAiBankingProvider.TRANSACTION_SUMMARY_QUERY, args, "customer-a");
    }
    private static CapabilityRequestV1 request(String capability, Map<String,Object> args, String subject) {
        var context = new FinanceContextV1(FinanceContextV1.SCHEMA_VERSION, "req-a", "trace-a", "customer-a",
                FinanceContextV1.PrincipalType.CUSTOMER, "controlled-host", "CASHFLOW_DESCRIPTION", subject,
                "session-a", FinanceContextV1.AuthenticationLevel.BASIC, Set.of("cashflow:read"));
        return new CapabilityRequestV1(CapabilityRequestV1.SCHEMA_VERSION, capability,
                CapabilityRequestV1.Operation.QUERY, context, args, null, null, Map.of(), null);
    }
}
