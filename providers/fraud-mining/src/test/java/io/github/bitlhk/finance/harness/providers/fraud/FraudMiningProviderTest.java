package io.github.bitlhk.finance.harness.providers.fraud;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.bitlhk.finance.harness.core.CapabilityRequestV1;
import io.github.bitlhk.finance.harness.core.CapabilityResultV1;
import io.github.bitlhk.finance.harness.core.FinanceContextV1;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FraudMiningProviderTest {
    private HttpServer server;
    private FraudMiningProvider provider;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/samples", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            if (query != null && query.contains("keyword=upstream-error")) {
                writeJson(exchange, 500, "{\"code\":500,\"message\":\"failed\",\"data\":null}");
                return;
            }
            if (query != null && query.contains("keyword=fraud-001")) {
                writeJson(exchange, 200, """
                        {"code":200,"message":"success","data":{"items":[
                          {"id":17,"cino":"fraud-001","label":1,"match_status":"matched",
                           "match_confidence":0.91,"prediction_result":"fraud","prediction_score":0.78,
                           "created_at":"2026-09-02T02:00:00"}],"total":1,"page":1,"page_size":20}}
                        """);
                return;
            }
            if (query != null && query.contains("keyword=late-match")) {
                if (query.contains("page=1&")) {
                    writeJson(exchange, 200, """
                            {"code":200,"data":{"items":[{"id":1,"cino":"other","label":0,
                            "match_status":"not_matched","created_at":"2026-09-01T00:00:00"}],
                            "total":2,"page":1,"page_size":1,"source_version":"frozen-v1",
                            "snapshot_consistency":"FROZEN_SYNTHETIC_DATASET"}}""");
                } else {
                    writeJson(exchange, 200, """
                            {"code":200,"data":{"items":[{"id":2,"cino":"late-match","label":1,
                            "match_status":"matched","match_confidence":0.88,"prediction_result":"fraud",
                            "prediction_score":0.7,"created_at":"2026-09-02T00:00:00"}],
                            "total":2,"page":2,"page_size":1,"source_version":"frozen-v1",
                            "snapshot_consistency":"FROZEN_SYNTHETIC_DATASET"}}""");
                }
                return;
            }
            if (query != null && query.contains("keyword=partial-match")) {
                if (query.contains("page=1&")) {
                    writeJson(exchange, 200, """
                            {"code":200,"data":{"items":[{"id":3,"cino":"partial-match","label":1,
                            "match_status":"matched","match_confidence":0.9,"prediction_result":"fraud",
                            "prediction_score":0.75,"created_at":"2026-09-02T00:00:00"}],
                            "total":2,"page":1,"page_size":1}}""");
                } else writeJson(exchange, 503, "{\"code\":503,\"data\":null}");
                return;
            }
            if (query != null && query.contains("keyword=partial-no-match")) {
                if (query.contains("page=1&")) {
                    writeJson(exchange, 200, """
                            {"code":200,"data":{"items":[{"id":4,"cino":"other","label":0,
                            "match_status":"not_matched","created_at":"2026-09-02T00:00:00"}],
                            "total":2,"page":1,"page_size":1}}""");
                } else writeJson(exchange, 503, "{\"code\":503,\"data\":null}");
                return;
            }
            if (query != null && query.contains("keyword=duplicate-match")) {
                writeJson(exchange, 200, query.contains("page=1&") ? """
                        {"code":200,"data":{"items":[{"id":9,"cino":"duplicate-match","label":1,
                        "match_status":"matched","created_at":"2026-09-01T00:00:00"}],"total":2,"page":1,"page_size":1}}""" : """
                        {"code":200,"data":{"items":[{"id":9,"cino":"duplicate-match","label":1,
                        "match_status":"matched","created_at":"2026-09-01T00:00:00"}],"total":2,"page":2,"page_size":1}}""");
                return;
            }
            if (query != null && query.contains("keyword=changing-total")) {
                writeJson(exchange, 200, query.contains("page=1&") ? """
                        {"code":200,"data":{"items":[{"id":11,"cino":"other","label":0}],"total":2,"page":1,"page_size":1}}""" : """
                        {"code":200,"data":{"items":[{"id":12,"cino":"other","label":0}],"total":3,"page":2,"page_size":1}}""");
                return;
            }
            if (query != null && query.contains("keyword=changing-version")) {
                writeJson(exchange, 200, query.contains("page=1&") ? """
                        {"code":200,"data":{"items":[{"id":13,"cino":"other","label":0}],"total":2,
                        "page":1,"page_size":1,"source_version":"v1","snapshot_consistency":"SNAPSHOT"}}""" : """
                        {"code":200,"data":{"items":[{"id":14,"cino":"other","label":0}],"total":2,
                        "page":2,"page_size":1,"source_version":"v2","snapshot_consistency":"SNAPSHOT"}}""");
                return;
            }
            if (query != null && query.contains("keyword=missing-evidence-metadata")) {
                writeJson(exchange, 200, """
                        {"code":200,"data":{"items":[{"cino":"missing-evidence-metadata","label":1,
                        "match_status":"matched"}],"total":1,"page":1,"page_size":100}}""");
                return;
            }
            writeJson(exchange, 200,
                    "{\"code\":200,\"message\":\"success\",\"data\":{\"items\":[],\"total\":0,\"page\":1,\"page_size\":20}}");
        });
        server.start();
        provider = new FraudMiningProvider(new FraudMiningProvider.Config(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), Duration.ofSeconds(2)));
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void matchedIncidentProducesExplicitBlockDecisionWithoutLeakingRawFeatures() {
        CapabilityResultV1 result = provider.query(request("fraud-001"));

        assertEquals(CapabilityResultV1.Status.COMPLETED, result.status());
        assertEquals("BLOCK", result.data().get("decision"));
        assertEquals("KNOWN_FRAUD_INCIDENT_MATCH", result.data().get("reasonCode"));
        assertEquals(true, result.data().get("knownRiskMatch"));
        assertEquals(List.of("BLOCK_TRANSFER", "REFER_FRAUD_REVIEW"), result.nextActions());
        assertFalse(result.data().toString().contains("feature"));
    }

    @Test
    void noMatchAllowsTheBankToContinueItsNormalPreflight() {
        CapabilityResultV1 result = provider.query(request("normal-001"));

        assertEquals(CapabilityResultV1.Status.COMPLETED, result.status());
        assertEquals("ALLOW", result.data().get("decision"));
        assertEquals(false, result.data().get("knownRiskMatch"));
        assertEquals(List.of("CONTINUE_PREFLIGHT"), result.nextActions());
    }

    @Test
    void upstreamFailureIsNeverMappedToNormalOrAllow() {
        CapabilityResultV1 result = provider.query(request("upstream-error"));

        assertEquals(CapabilityResultV1.Status.FAILED, result.status());
        assertEquals("FRAUD_SERVICE_UNAVAILABLE", result.error().sourceCode());
        assertTrue(result.error().retryable());
        assertFalse(result.data().containsKey("decision"));
    }

    @Test
    void readOnlyEvidenceUsesExactSubjectAndCanFindALaterPage() {
        provider = boundedProvider(1, 4, 4);
        CapabilityResultV1 result = provider.query(evidenceRequest("late-match", true));

        assertEquals(CapabilityResultV1.Status.COMPLETED, result.status());
        assertEquals("KNOWN_RISK_MATCH", result.data().get("verificationStatus"));
        assertEquals(true, result.data().get("queryComplete"));
        assertEquals(List.of("MANUAL_EVIDENCE_REVIEW"), result.nextActions());
        assertFalse(result.content().contains("允许"));
        assertFalse(result.content().contains("阻断转账"));
        assertEquals("frozen-v1", result.data().get("sourceVersion"));
        assertTrue(result.data().get("queryScope").toString().contains("FROZEN_SYNTHETIC_DATASET"));
    }

    @Test
    void laterFailurePreservesMatchAndMarksVerificationIncomplete() {
        provider = boundedProvider(1, 4, 4);
        CapabilityResultV1 result = provider.query(evidenceRequest("partial-match", true));

        assertEquals(CapabilityResultV1.Status.COMPLETED, result.status());
        assertEquals("KNOWN_RISK_MATCH", result.data().get("verificationStatus"));
        assertEquals(false, result.data().get("queryComplete"));
        assertTrue(result.data().get("signals").toString().contains("partial-match") == false);
        assertTrue(result.data().get("queryScope").toString().contains("SOURCE_FAILED_AFTER_PARTIAL_EVIDENCE"));
    }

    @Test
    void completeNoMatchExplicitlyDisclaimsSafetyAndTransferApproval() {
        CapabilityResultV1 result = provider.query(evidenceRequest("normal-001", true));
        assertEquals("NO_KNOWN_MATCH_COMPLETE", result.data().get("verificationStatus"));
        assertTrue(result.content().contains("不代表对象安全或允许交易"));
        assertEquals(List.of("MANUAL_EVIDENCE_REVIEW"), result.nextActions());
    }

    @Test
    void evidenceQueryRejectsWrongSubjectTypeAndMissingAuthorization() {
        CapabilityResultV1 denied = provider.query(evidenceRequest("fraud-001", false));
        assertEquals("FRAUD_RISK_READ_REQUIRED", denied.error().sourceCode());
        CapabilityRequestV1 wrongType = new CapabilityRequestV1(
                CapabilityRequestV1.SCHEMA_VERSION, FraudMiningProvider.RISK_EVIDENCE_QUERY,
                CapabilityRequestV1.Operation.QUERY, context(true),
                Map.of("subjectType", "ACCOUNT", "subjectRef", "fraud-001"), null, null, Map.of(), null);
        assertEquals("FRAUD_SUBJECT_TYPE_UNSUPPORTED", provider.query(wrongType).error().sourceCode());
    }

    @Test
    void duplicateRecordsAreDeduplicatedAndDoNotCreateAFalseCompleteResult() {
        provider = boundedProvider(1, 2, 10);
        CapabilityResultV1 result = provider.query(evidenceRequest("duplicate-match", true));
        assertEquals("KNOWN_RISK_MATCH", result.data().get("verificationStatus"));
        assertEquals(true, result.data().get("queryComplete"));
        assertEquals(1, ((List<?>) result.data().get("signals")).size());
    }

    @Test
    void recordAndPageLimitsNeverProduceACompleteNoMatch() {
        provider = boundedProvider(1, 1, 1);
        CapabilityResultV1 result = provider.query(evidenceRequest("late-match", true));
        assertEquals("VERIFICATION_INCOMPLETE", result.data().get("verificationStatus"));
        assertEquals(false, result.data().get("queryComplete"));
        assertTrue(result.data().get("queryScope").toString().contains("PAGE_LIMIT_REACHED")
                || result.data().get("queryScope").toString().contains("RECORD_LIMIT_REACHED"));
    }

    @Test
    void sourceTotalChangeIsReportedAsIncomplete() {
        provider = boundedProvider(1, 4, 10);
        CapabilityResultV1 result = provider.query(evidenceRequest("changing-total", true));
        assertEquals("VERIFICATION_INCOMPLETE", result.data().get("verificationStatus"));
        assertEquals(false, result.data().get("queryComplete"));
        assertTrue(result.data().get("queryScope").toString().contains("SOURCE_TOTAL_CHANGED"));
    }

    @Test
    void sourceVersionChangeIsReportedAsIncomplete() {
        provider = boundedProvider(1, 4, 10);
        CapabilityResultV1 result = provider.query(evidenceRequest("changing-version", true));
        assertEquals("VERIFICATION_INCOMPLETE", result.data().get("verificationStatus"));
        assertEquals(false, result.data().get("queryComplete"));
        assertTrue(result.data().get("queryScope").toString().contains("SOURCE_VERSION_CHANGED"));
    }

    @Test
    void failureAfterAReadPageWithoutAMatchRemainsVerificationIncomplete() {
        provider = boundedProvider(1, 4, 10);
        CapabilityResultV1 result = provider.query(evidenceRequest("partial-no-match", true));
        assertEquals(CapabilityResultV1.Status.COMPLETED, result.status());
        assertEquals("VERIFICATION_INCOMPLETE", result.data().get("verificationStatus"));
        assertEquals(false, result.data().get("queryComplete"));
        assertTrue(result.data().get("queryScope").toString().contains("SOURCE_FAILED_AFTER_PARTIAL_EVIDENCE"));
    }

    @Test
    void missingSourceMetadataStaysUnknownAndMakesTheQueryIncomplete() {
        CapabilityResultV1 result = provider.query(evidenceRequest("missing-evidence-metadata", true));
        assertEquals("KNOWN_RISK_MATCH", result.data().get("verificationStatus"));
        assertEquals(false, result.data().get("queryComplete"));
        assertTrue(result.data().get("queryScope").toString().contains("SOURCE_RECORD_ID_MISSING"));
        assertTrue(result.data().get("queryScope").toString().contains("SOURCE_EVIDENCE_TIME_MISSING"));
        assertTrue(result.data().get("signals").toString().contains("UNKNOWN"));
    }

    @Test
    void managedFactoryIsInstalledForTheReadOnlyContract() {
        var factories = ServiceLoader.load(io.github.bitlhk.finance.harness.core.spi.ManagedProviderFactoryV01.class)
                .stream().filter(value -> value.type().equals(FraudMiningFactoryV01.class)).toList();
        assertEquals(1, factories.size());
        var factory = factories.getFirst().get();
        assertEquals(FraudMiningProvider.PROVIDER_ID, factory.providerId());
        assertEquals(FraudMiningFactoryV01.CONNECTION_KIND, factory.connectionKind());
        assertEquals(Set.of("fraud-risk:read"), factory.requirements().get(FraudMiningProvider.RISK_EVIDENCE_QUERY));
    }

    private FraudMiningProvider boundedProvider(int pageSize, int maxPages, int maxRecords) {
        return new FraudMiningProvider(new FraudMiningProvider.Config(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), Duration.ofSeconds(2),
                pageSize, maxPages, maxRecords));
    }

    private CapabilityRequestV1 evidenceRequest(String subjectRef, boolean authorized) {
        return new CapabilityRequestV1(
                CapabilityRequestV1.SCHEMA_VERSION, FraudMiningProvider.RISK_EVIDENCE_QUERY,
                CapabilityRequestV1.Operation.QUERY, context(authorized),
                Map.of("subjectType", FraudMiningProvider.FRAUD_SOURCE_CINO, "subjectRef", subjectRef),
                null, null, Map.of(), null);
    }

    private FinanceContextV1 context(boolean authorized) {
        return new FinanceContextV1(
                FinanceContextV1.SCHEMA_VERSION, "request-fraud-001", "trace-fraud-001", "employee-demo-001",
                FinanceContextV1.PrincipalType.EMPLOYEE, "INTERNAL", "FRAUD_VERIFICATION", null,
                "session-demo", FinanceContextV1.AuthenticationLevel.STRONG,
                authorized ? java.util.Set.of("fraud-risk:read") : java.util.Set.of());
    }

    private CapabilityRequestV1 request(String subjectRef) {
        return new CapabilityRequestV1(
                CapabilityRequestV1.SCHEMA_VERSION,
                FraudMiningProvider.TRANSFER_RISK_ASSESS,
                CapabilityRequestV1.Operation.QUERY,
                new FinanceContextV1(
                        FinanceContextV1.SCHEMA_VERSION,
                        "request-fraud-001",
                        "trace-fraud-001",
                        "customer-demo-001",
                        FinanceContextV1.PrincipalType.CUSTOMER,
                        "MOBILE",
                        "TRANSFER",
                        "customer-demo-001",
                        "session-demo",
                        FinanceContextV1.AuthenticationLevel.STRONG,
                        java.util.Set.of("fraud:read")),
                Map.of("accountRef", subjectRef),
                null,
                null,
                Map.of(),
                null);
    }

    private static void writeJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
