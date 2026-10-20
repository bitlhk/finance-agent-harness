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
    @Test void protocolAndConfirmationFieldsCannotLeakIntoLegacyOrUnknownContracts() {
        var valid = body(QUERY,"synthetic-confirmation");
        assertDoesNotThrow(()->CustomerManagerScenarioExtension.exactChat(valid));
        var unknown = valid.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode)unknown).put("conversationContract","manager-conversation.v999");
        assertThrows(IllegalArgumentException.class,()->CustomerManagerScenarioExtension.exactChat(unknown));
        var legacy = valid.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode)legacy).remove("conversationContract");
        assertThrows(IllegalArgumentException.class,()->CustomerManagerScenarioExtension.exactChat(legacy));
    }
}
