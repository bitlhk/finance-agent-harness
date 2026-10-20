package io.github.bitlhk.finance.harness.integrations.customermanager;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.examples.wealth.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real managed Session and SDK; synthetic authority and normalized failing fact Provider. */
final class ConversationHostTest {
    @TempDir Path files;
    private static final String QUERY = "prepare synthetic-a";
    private static final String PROPOSAL = "{\"schema\":\"finance-conversation-intent.v1\",\"kind\":\"PREPARE\",\"objectRef\":\"synthetic-a\",\"reportRef\":null,\"revision\":null}";
    final AtomicInteger business = new AtomicInteger();
    final AtomicInteger reportModels = new AtomicInteger();
    final AtomicBoolean revoked = new AtomicBoolean();
    final AtomicBoolean throwsSource = new AtomicBoolean();
    final AtomicBoolean breakEvidenceDuringCall = new AtomicBoolean();
    final AtomicBoolean revokeDuringCall = new AtomicBoolean();
    final AtomicBoolean cancelDuringCall = new AtomicBoolean();
    final AtomicReference<ServicePreparation> taskReference = new AtomicReference<>();
    final AtomicReference<String> scope = new AtomicReference<>("synthetic-scope-a");

    private CustomerManagerScenarioExtension.Managed session(OpenJiuwenIntentProposer proposer) throws Exception {
        var access = new PreparationAccess() {
            public Snapshot authorize(String ref) {
                if(revoked.get()) throw new SecurityException("CRM_EMPLOYEE_SCOPE_REJECTED");
                return new Snapshot("synthetic-authority", "synthetic-employee", FinanceContextV1.AuthenticationLevel.BASIC);
            }
            public String providerId() { return "synthetic-provider"; }
            public CapabilityResultV1 query(String request, String trace, String customer, String capability, Map<String,Object> args) {
                business.incrementAndGet();
                if(cancelDuringCall.get()) {
                    try { assertEquals("CANCELLED",taskReference.get().cancel("owner-a",1)); }
                    catch(Exception failure) { throw new AssertionError("Synthetic cancellation setup failed",failure); }
                }
                if(revokeDuringCall.get()) revoked.set(true);
                if(breakEvidenceDuringCall.get()) {
                    try {
                        java.nio.file.Files.move(files.resolve("decision-evidence"),files.resolve("retained-entry-evidence"));
                        java.nio.file.Files.createFile(files.resolve("decision-evidence"));
                    } catch(java.io.IOException failure) { throw new IllegalStateException("SYNTHETIC_SETUP_FAILED",failure); }
                }
                if(throwsSource.get()) throw new IllegalStateException("PRIVATE_SYNTHETIC_EXCEPTION_BODY");
                return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION,request,trace,
                        CapabilityResultV1.Status.FAILED,"Synthetic source failure",Map.of(),List.of(),List.of(),
                        "synthetic-provider",java.time.Instant.now(),null,
                        new CapabilityResultV1.Failure("SOURCE_FAILURE","SYNTHETIC_PROVIDER_FAILURE",false,"Synthetic failure",List.of()));
            }
            public void close() {}
        };
        var method = new InstalledPreparationMethod("synthetic-method", "1", "sha256:synthetic-method",
                "sha256:synthetic-lock", "Synthetic method only.");
        var task = new ServicePreparation("owner-a", access,
                () -> new ServicePreparation.Mapping("mapping-a", Map.of("synthetic-a","customer-a")),
                (selectedMethod,goal,ids,context) -> { reportModels.incrementAndGet(); return HarnessJson.mapper().createObjectNode(); },
                files, Map.of(), method);
        taskReference.set(task);
        return new CustomerManagerScenarioExtension.Managed("owner-a", "session-a", task, method,
                new ConversationCapabilities(Set.of(ConversationIntent.Kind.PREPARE), List.of("wealth.previsit-context.query")),
                "instance-a", proposer,scope::get);
    }
    private static JsonNode body(String query, String confirmation) {
        var value = HarnessJson.mapper().createObjectNode().put("query", query).put("runId", UUID.randomUUID().toString())
                .put("customerAlias", "synthetic-a").put("conversationContract", "manager-conversation.v2");
        if(confirmation != null) value.put("confirmationId", confirmation).put("confirmationDecision","EXECUTE");
        return value;
    }
    private static String planId(Map<String,Object> result) {
        assertEquals("NEED_CONFIRMATION", result.get("status"));
        return (String)((Map<?,?>)result.get("confirmation")).get("id");
    }
    private static final String MISSING_OBJECT=PROPOSAL.replace("\"synthetic-a\"","null");
    private static final String RESOLVE_OBJECT=PROPOSAL.replace("\"PREPARE\"","\"RESOLVE_OBJECT\"");
    @Test @Timeout(30) void naturalObjectClarificationRetainsOriginalTaskButRequiresExplicitConfirmation() throws Exception {
        try(var model=new OpenJiuwenIntentProposerTest.Fixture(MISSING_OBJECT,RESOLVE_OBJECT);var session=session(model.proposer())) {
            var first=(com.fasterxml.jackson.databind.node.ObjectNode)body("Prepare a visit about liquidity",null);
            first.put("customerAlias","");
            assertEquals("NEED_CLARIFICATION",session.call("chat",first).get("status"));
            var result=session.call("chat",body("Use synthetic-a please",null));
            String id=planId(result);
            var plan=(Map<?,?>)result.get("confirmation");
            String combined=(String)plan.get("query");
            assertEquals("Prepare a visit about liquidity\n本轮补充：Use synthetic-a please",combined);
            assertEquals(0,business.get());
            assertTrue(model.requests.get(1).path("messages").toString().contains("Prepare a visit about liquidity"));
            assertEquals("FAILED",session.call("chat",body(combined,id)).get("status"));
            assertEquals(1,business.get());assertEquals(2,model.requests.size());
        }
    }
    @Test @Timeout(30) void newTaskOfSameKindDoesNotInheritUnrelatedPendingText() throws Exception {
        try(var model=new OpenJiuwenIntentProposerTest.Fixture(MISSING_OBJECT,PROPOSAL);var session=session(model.proposer())) {
            session.call("chat",body("Previous unrelated task",null));
            var result=session.call("chat",body(QUERY,null));
            planId(result);
            assertEquals(QUERY,((Map<?,?>)result.get("confirmation")).get("query"));
            assertEquals(0,business.get());
        }
    }
    @Test @Timeout(30) void trustedScopeChangeInvalidatesPendingClarificationWithoutQuery() throws Exception {
        try(var model=new OpenJiuwenIntentProposerTest.Fixture(MISSING_OBJECT,RESOLVE_OBJECT);var session=session(model.proposer())) {
            session.call("chat",body("Prepare a visit",null));
            scope.set("changed-real-scope");
            var result=session.call("chat",body("Use synthetic-a please",null));
            assertEquals("NEED_CLARIFICATION",result.get("status"));
            assertFalse(result.containsKey("confirmation"));assertEquals(0,business.get());
            assertFalse(model.requests.get(1).path("messages").toString().contains("Prepare a visit"));
        }
    }
    @Test @Timeout(30) void newConversationCannotResumePreviousClarificationOrConfirmation() throws Exception {
        try(var model=new OpenJiuwenIntentProposerTest.Fixture(MISSING_OBJECT,RESOLVE_OBJECT,PROPOSAL);var session=session(model.proposer())) {
            var first=(com.fasterxml.jackson.databind.node.ObjectNode)body("Prepare a visit",null);
            first.put("conversationContext","first-conversation");
            session.call("chat",first);
            var next=(com.fasterxml.jackson.databind.node.ObjectNode)body("Use synthetic-a please",null);
            next.put("conversationContext","second-conversation");
            assertEquals("NEED_CLARIFICATION",session.call("chat",next).get("status"));
            assertFalse(model.requests.get(1).path("messages").toString().contains("Prepare a visit"));
            next.put("query",QUERY);
            String id=planId(session.call("chat",next.put("runId",UUID.randomUUID().toString())));
            var confirm=(com.fasterxml.jackson.databind.node.ObjectNode)body(QUERY,id);
            confirm.put("conversationContext","third-conversation");
            assertThrows(SecurityException.class,()->session.call("chat",confirm));
            assertEquals(0,business.get());
        }
    }
    @Test @Timeout(30) void proposalDoesNotQueryAndConfirmationExecutesOnceWithFailureFactsPreserved() throws Exception {
        try(var model = new OpenJiuwenIntentProposerTest.Fixture(PROPOSAL); var session = session(model.proposer())) {
            String id = planId(session.call("chat", body(QUERY,null)));
            assertEquals(0,business.get()); assertEquals(0,reportModels.get());
            var result = session.call("chat",body(QUERY,id));
            assertEquals(1,business.get());
            assertEquals("FAILED",result.get("status"));
            assertNotNull(result.get("report"));
            assertEquals(1,model.requests.size(),"Confirmation must not call the intent model again");
            assertThrows(SecurityException.class,()->session.call("chat",body(QUERY,id)));
            assertEquals(1,business.get()); assertEquals(0,reportModels.get());
        }
    }
    @Test @Timeout(30) void modifiedTextOrRevokedAuthorityDoesNotExecute() throws Exception {
        try(var model = new OpenJiuwenIntentProposerTest.Fixture(PROPOSAL); var session = session(model.proposer())) {
            String first = planId(session.call("chat", body(QUERY,null)));
            assertThrows(SecurityException.class,()->session.call("chat",body("changed amount",first)));
            String second = planId(session.call("chat", body(QUERY,null)));
            revoked.set(true);
            assertThrows(SecurityException.class,()->session.call("chat",body(QUERY,second)));
            revoked.set(false);
            assertThrows(SecurityException.class,()->session.call("chat",body(QUERY,second)));
            assertEquals(0,business.get()); assertEquals(0,reportModels.get());
        }
    }
    @Test @Timeout(30) void interveningLegacyTurnCannotReuseOldPlan() throws Exception {
        try(var model = new OpenJiuwenIntentProposerTest.Fixture(PROPOSAL); var session = session(model.proposer())) {
            String id = planId(session.call("chat", body(QUERY,null)));
            assertEquals("ANSWERED",session.call("chat",HarnessJson.mapper().createObjectNode().put("query","你好")).get("status"));
            assertThrows(SecurityException.class,()->session.call("chat",body(QUERY,id)));
            assertEquals(0,business.get());
        }
    }
    @Test @Timeout(30) void cancellingPlanConsumesItWithoutModelOrBusinessCalls() throws Exception {
        try(var model = new OpenJiuwenIntentProposerTest.Fixture(PROPOSAL); var session = session(model.proposer())) {
            String id=planId(session.call("chat",body(QUERY,null)));
            var cancel=(com.fasterxml.jackson.databind.node.ObjectNode)body(QUERY,id);
            cancel.put("confirmationDecision","CANCEL");
            assertEquals("ANSWERED",session.call("chat",cancel).get("status"));
            assertThrows(SecurityException.class,()->session.call("chat",body(QUERY,id)));
            assertEquals(1,model.requests.size()); assertEquals(0,business.get());
        }
    }
    @Test @Timeout(30) void sameAliasWithChangedTrustedMappingCannotReuseConfirmation() throws Exception {
        try(var model = new OpenJiuwenIntentProposerTest.Fixture(PROPOSAL); var session = session(model.proposer())) {
            String id=planId(session.call("chat",body(QUERY,null)));
            scope.set("synthetic-scope-b");
            assertThrows(SecurityException.class,()->session.call("chat",body(QUERY,id)));
            assertEquals(0,business.get());
        }
    }
    @Test @Timeout(30) void rawProviderExceptionIsTerminalAndCannotBeRetriedByReusingConfirmation() throws Exception {
        throwsSource.set(true);
        try(var model = new OpenJiuwenIntentProposerTest.Fixture(PROPOSAL); var session = session(model.proposer())) {
            String id=planId(session.call("chat",body(QUERY,null)));
            var result=session.call("chat",body(QUERY,id));
            assertEquals("FAILED",result.get("status"));
            JsonNode report=HarnessJson.mapper().valueToTree(result.get("report"));
            assertEquals("NOT_RUN",report.path("explanationStatus").asText());
            assertEquals("PREPARATION_SOURCE_CALL_FAILED",report.path("failureCode").asText());
            assertEquals("INVOKED_SEND_UNOBSERVED",report.path("providerDispatch").asText());
            assertFalse(report.toString().contains("PRIVATE_SYNTHETIC_EXCEPTION_BODY"));
            var saved=session.call("latest",HarnessJson.mapper().createObjectNode());
            assertEquals(io.github.bitlhk.finance.harness.core.evidence.ScenarioEvidence.digest(report),
                    io.github.bitlhk.finance.harness.core.evidence.ScenarioEvidence.digest(saved.get("report")));
            assertThrows(SecurityException.class,()->session.call("chat",body(QUERY,id)));
            assertEquals(1,business.get()); assertEquals(0,reportModels.get());
        }
    }
    @Test @Timeout(30) void necessaryEntryEvidenceFailureStillPreventsProviderCall() throws Exception {
        try(var model = new OpenJiuwenIntentProposerTest.Fixture(PROPOSAL); var session = session(model.proposer())) {
            String id=planId(session.call("chat",body(QUERY,null)));
            java.nio.file.Files.createFile(files.resolve("decision-evidence"));
            assertThrows(io.github.bitlhk.finance.harness.core.evidence.ScenarioEvidence.Unavailable.class,
                    ()->session.call("chat",body(QUERY,id)));
            assertEquals(0,business.get()); assertEquals(0,reportModels.get());
        }
    }
    @Test @Timeout(30) void sourceExceptionAfterRevocationDiscardsWithoutReleasingOrRemainingRunning() throws Exception {
        throwsSource.set(true);revokeDuringCall.set(true);
        try(var model=new OpenJiuwenIntentProposerTest.Fixture(PROPOSAL);var session=session(model.proposer())) {
            String id=planId(session.call("chat",body(QUERY,null)));
            assertThrows(SecurityException.class,()->session.call("chat",body(QUERY,id)));
            assertEquals(1,business.get());assertEquals(0,reportModels.get());
            assertEquals(Map.of("found",false),session.call("latest",HarnessJson.mapper().createObjectNode()));
            try(var paths=java.nio.file.Files.walk(files)) {
                Path latest=paths.filter(p->p.getFileName().toString().equals("latest.json")).findFirst().orElseThrow();
                int revision=HarnessJson.mapper().readTree(java.nio.file.Files.readAllBytes(latest)).path("revision").asInt();
                var retained=HarnessJson.mapper().readTree(java.nio.file.Files.readAllBytes(latest.resolveSibling(String.format("v%06d.json",revision))));
                assertEquals("DISCARDED",retained.path("status").asText());
                assertThrows(SecurityException.class,()->session.call("read",HarnessJson.mapper().createObjectNode()
                        .put("reportId",retained.path("reportId").asText())));
                assertEquals("INVOKED_SEND_UNOBSERVED",retained.path("providerDispatch").asText());
                assertFalse(retained.has("facts"));assertFalse(retained.has("modelSuggestion"));
                assertFalse(retained.toString().contains("PRIVATE_SYNTHETIC_EXCEPTION_BODY"));
            }
        }
    }
    @Test @Timeout(30) void sourceExceptionAfterCancellationCannotOverwriteCancelledSnapshot() throws Exception {
        throwsSource.set(true);cancelDuringCall.set(true);
        try(var model=new OpenJiuwenIntentProposerTest.Fixture(PROPOSAL);var session=session(model.proposer())) {
            String id=planId(session.call("chat",body(QUERY,null)));
            assertThrows(java.util.concurrent.CancellationException.class,()->session.call("chat",body(QUERY,id)));
            JsonNode saved=HarnessJson.mapper().valueToTree(session.call("latest",HarnessJson.mapper().createObjectNode()).get("report"));
            assertEquals("CANCELLED",saved.path("status").asText());
            assertEquals("CANCELLED",saved.path("explanationStatus").asText());
            assertFalse(saved.has("failureCode"));assertFalse(saved.has("modelSuggestion"));
            assertEquals(1,business.get());assertEquals(0,reportModels.get());
        }
    }
    @Test @Timeout(30) void evidenceFailureAfterSourceExceptionWithholdsResultAndRetainsHonestState() throws Exception {
        throwsSource.set(true);breakEvidenceDuringCall.set(true);
        try(var model = new OpenJiuwenIntentProposerTest.Fixture(PROPOSAL); var session = session(model.proposer())) {
            String id=planId(session.call("chat",body(QUERY,null)));
            var error=assertThrows(io.github.bitlhk.finance.harness.core.evidence.ScenarioEvidence.Unavailable.class,
                    ()->session.call("chat",body(QUERY,id)));
            assertEquals("INVOKED_SEND_UNOBSERVED",error.providerDispatch());
            assertEquals(1,business.get());assertEquals(0,reportModels.get());
            try(var paths=java.nio.file.Files.walk(files)) {
                Path latest=paths.filter(p->p.getFileName().toString().equals("latest.json")).findFirst().orElseThrow();
                int revision=HarnessJson.mapper().readTree(java.nio.file.Files.readAllBytes(latest)).path("revision").asInt();
                var retained=HarnessJson.mapper().readTree(java.nio.file.Files.readAllBytes(latest.resolveSibling(String.format("v%06d.json",revision))));
                assertEquals("SCENARIO_EVIDENCE_UNAVAILABLE",retained.path("failureCode").asText());
                assertEquals("WITHHELD",retained.path("releaseStatus").asText());
                assertEquals("PARTIAL",retained.path("status").asText());
            }
        }
    }
    @Test void protocolAndConfirmationFieldsCannotLeakIntoLegacyOrUnknownContracts() {
        var valid = body(QUERY,"synthetic-confirmation");
        assertDoesNotThrow(()->CustomerManagerScenarioExtension.exactChat(valid));
        var unknown = valid.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode)unknown).put("conversationContract","manager-conversation.v999");
        assertThrows(IllegalArgumentException.class,()->CustomerManagerScenarioExtension.exactChat(unknown));
        var legacy = valid.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode)legacy).remove("conversationContract");
        assertThrows(IllegalArgumentException.class,()->CustomerManagerScenarioExtension.exactChat(legacy));
    }
}
