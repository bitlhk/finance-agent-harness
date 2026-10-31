package io.github.bitlhk.finance.harness.examples.wealth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.bitlhk.finance.harness.core.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PreparationKnowledgeTest {
    @TempDir Path root;
    boolean businessGranted = true, knowledgeGranted = true, historyGranted = false, found = true;
    boolean revokeDuringGeneration;
    int businessCalls, knowledgeCalls, modelCalls, closed;
    JsonNode modelFacts;
    String productCitation;
    final PreparationAccess.Snapshot snapshot = new PreparationAccess.Snapshot("crm-authority", "employee",
            FinanceContextV1.AuthenticationLevel.STRONG);
    PreparationAccess business = new PreparationAccess() {
        public Snapshot authorize(String customer) {
            if (!businessGranted || !"customer-a".equals(customer)) throw new SecurityException("CRM_REVOKED");
            return snapshot;
        }
        public String providerId() { return "customer-crm-readonly"; }
        public CapabilityResultV1 query(String request, String trace, String customer, String capability, Map<String,Object> args) {
            authorize(customer); businessCalls++;
            if ("wealth.allocation-context.query".equals(capability))
                return result(request, trace, Map.of("status", "partial", "customerRiskLevel", "C3",
                        "assessmentValidUntil", "2027-08-27", "request", args,
                        "policySource", Map.of("ruleVersion", "fixture-v1"),
                        "eligibleProducts", List.of(), "excludedProducts", List.of()));
            return result(request, trace, Map.of("previsitContext", Map.of("customer", Map.of("customerId", customer),
                    "dataAsOf", "2026-10-31T00:00:00Z", "profiles", List.of(), "readiness", Map.of("status", "READY")),
                    "hostEvidence", Map.of("receiptId", "crm-receipt-a")));
        }
        public void close() { closed++; }
    };
    PreparationKnowledge knowledge = new PreparationKnowledge() {
        public CapabilityResultV1 query(String request, String trace, boolean product) {
            current(); knowledgeCalls++;
            return result(request, trace, Map.of("decision", found ? "EVIDENCE_FOUND" : "UNKNOWN",
                    "evidence", found ? List.of(Map.of("text", "须核实产品销售资格", "anchor", "clause-2")) : List.of(),
                    "knowledgeReceipt", Map.of("manifest", "a".repeat(64), "requestId", request, "traceId", trace,
                            "receiptId", "knowledge-receipt-a")));
        }
        public void current() { if (!knowledgeGranted) throw new SecurityException("KNOWLEDGE_REVOKED"); }
        public void authorizeSaved(JsonNode report) {
            if (!historyGranted || !knowledgeGranted) throw new SecurityException("HISTORY_NOT_GRANTED");
        }
        public void close() { closed++; }
    };
    static CapabilityResultV1 result(String request, String trace, Map<String,Object> data) {
        return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION, request, trace, CapabilityResultV1.Status.COMPLETED,
                "controlled fixture", data, List.of(), List.of(), "fixture", Instant.now(), null, null);
    }
    ServicePreparation open() throws Exception {
        return new ServicePreparation("owner-a", business,
                () -> new ServicePreparation.Mapping("mapping-a", Map.of("合成客户甲", "customer-a")),
                (method, goal, refs, facts) -> {
                    modelCalls++; modelFacts = facts.deepCopy();
                    if (found) assertTrue(refs.contains("/knowledgeEvidence/evidence/0/text"));
                    if (productCitation != null) {
                        assertTrue(refs.contains("/productReview/status"));
                        assertTrue(refs.contains("/productReview/policySource/ruleVersion"));
                        assertFalse(refs.contains("/productReview/invented"));
                        assertTrue(refs.contains("/factAssessment/missing/0/state"));
                        assertFalse(refs.contains("/factAssessment/goal"));
                        assertTrue(refs.stream().allMatch(ref -> !facts.at(ref).isMissingNode()), refs.toString());
                    }
                    if (revokeDuringGeneration) knowledgeGranted = false;
                    var generated = HarnessJson.mapper().createObjectNode();
                    generated.putArray("communicationFocus").addObject().put("text", "核实客户服务需求")
                            .putArray("factRefs").add("/customer/customerId");
                    generated.putArray("preparationAdvice").addObject().put("text", found ? "须核实产品销售资格" : "制度依据尚未取得")
                            .putArray("factRefs").add(productCitation != null ? productCitation
                                    : found ? "/knowledgeEvidence/evidence/0/text" : "/customer/customerId");
                    generated.putArray("pendingItems").add("核实产品销售资格");
                    generated.putArray("reviewChecklist").add("核实事实与制度依据");
                    return generated;
                }, root, Map.of(), new InstalledPreparationMethod("fixture", "1", "sha256:fixture", "sha256:lock", "fixture"),
                Map::of, knowledge);
    }
    ObjectNode prepare(ServicePreparation service) throws Exception {
        return service.prepare("owner-a", service.select("owner-a", "合成客户甲", "准备回访"));
    }
    @Test void originalEvidenceReachesGenerationAndRuntimeGrantCannotReleaseHistory() throws Exception {
        try (var service = open()) {
            var report = prepare(service);
            assertEquals("READY", report.path("explanationStatus").asText(), report.toString());
            assertEquals("clause-2", modelFacts.at("/knowledgeEvidence/evidence/0/anchor").asText());
            assertEquals(modelFacts.at("/knowledgeEvidence/evidence/0/text"), report.at("/knowledgeEvidence/evidence/0/text"));
            assertEquals("a".repeat(64), report.at("/knowledgeExecution/data/knowledgeReceipt/manifest").asText());
            String id = report.path("reportId").asText();
            assertThrows(SecurityException.class, () -> service.read("owner-a", id));
            historyGranted = true;
            assertEquals(HarnessJson.mapper().readTree(HarnessJson.mapper().writeValueAsBytes(report)),
                    service.read("owner-a", id));
            assertEquals(1, businessCalls); assertEquals(1, knowledgeCalls); assertEquals(1, modelCalls);
            businessGranted = false;
            assertThrows(SecurityException.class, () -> service.read("owner-a", id));
            businessGranted = true; knowledgeGranted = false;
            assertThrows(SecurityException.class, () -> service.read("owner-a", id));
            assertEquals(1, businessCalls); assertEquals(1, knowledgeCalls); assertEquals(1, modelCalls);
        }
        assertEquals(2, closed);
    }
    @Test void absentEvidenceRemainsPartialAndDoesNotBorrowAnotherResult() throws Exception {
        found = false;
        try (var service = open()) {
            var report = prepare(service);
            assertEquals("PARTIAL", report.path("status").asText());
            assertEquals("UNAVAILABLE", modelFacts.path("knowledgeStatus").asText());
            assertFalse(modelFacts.has("knowledgeEvidence"));
            assertTrue(report.path("missingEvidence").toString().contains("未取得可用制度原文"));
        }
    }
    @Test void actualProductStatusCanBeCitedWithoutAllowingInventedFields() throws Exception {
        for (String citation : List.of("/productReview/status", "/factAssessment/missing/0/state",
                "/productReview/invented")) {
            productCitation = citation;
            try (var service = open()) {
                var report = service.prepareProduct("owner-a", service.select("owner-a", "合成客户甲", "筛选产品"),
                        Map.of("amount", 1000, "horizonMonths", 12, "channel", "branch"), ServicePreparation.PreparationListener.NONE);
                assertEquals(citation.endsWith("invented") ? "FAILED" : "READY",
                        report.path("explanationStatus").asText(), report.toString());
                if (citation.endsWith("invented")) assertFalse(report.has("modelSuggestion"));
            }
        }
    }
    @Test void revocationDuringGenerationDiscardsJointContent() throws Exception {
        revokeDuringGeneration = true;
        try (var service = open()) {
            assertThrows(SecurityException.class, () -> prepare(service));
            try (var files = Files.list(root)) {
                for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                    var saved = HarnessJson.mapper().readTree(file.toFile());
                    if ("DISCARDED".equals(saved.path("status").asText())) {
                        assertFalse(saved.has("knowledgeExecution")); assertFalse(saved.has("modelSuggestion"));
                    }
                }
            }
        }
    }
}
