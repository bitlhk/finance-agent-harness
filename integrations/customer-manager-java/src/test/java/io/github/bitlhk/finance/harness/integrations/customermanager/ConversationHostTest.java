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
    final AtomicInteger authorityReads = new AtomicInteger();
    final AtomicBoolean authorityUnavailable = new AtomicBoolean();
    final AtomicBoolean revoked = new AtomicBoolean();
    final AtomicBoolean throwsSource = new AtomicBoolean();
    final AtomicBoolean breakEvidenceDuringCall = new AtomicBoolean();
    final AtomicBoolean revokeDuringCall = new AtomicBoolean();
    final AtomicBoolean cancelDuringCall = new AtomicBoolean();
    final AtomicReference<ServicePreparation> taskReference = new AtomicReference<>();
    final AtomicReference<String> scope = new AtomicReference<>("synthetic-scope-a");
    Map<String,String> customerMapping = Map.of("synthetic-a","customer-a");
    Set<ConversationIntent.Kind> installedActions = Set.of(ConversationIntent.Kind.PREPARE);

    private CustomerManagerScenarioExtension.Managed session(OpenJiuwenIntentProposer proposer) throws Exception {
        return session(proposer,null);
    }
    private CustomerManagerScenarioExtension.Managed session(OpenJiuwenIntentProposer proposer,PreparationAccess override) throws Exception {
        PreparationAccess access = override!=null?override:new PreparationAccess() {
            public Snapshot authorize(String ref) {
                authorityReads.incrementAndGet();
                if(authorityUnavailable.get()) throw new IllegalStateException("SYNTHETIC_AUTHORITY_UNAVAILABLE");
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
                () -> new ServicePreparation.Mapping("mapping-a", customerMapping),
                (selectedMethod,goal,ids,context) -> { reportModels.incrementAndGet(); return HarnessJson.mapper().createObjectNode(); },
                files, Map.of(), method);
        taskReference.set(task);
        return new CustomerManagerScenarioExtension.Managed("owner-a", "session-a", task, method,
                new ConversationCapabilities(installedActions, List.of("wealth.previsit-context.query")),
                "instance-a", proposer,scope::get,
                new io.github.bitlhk.finance.harness.core.evidence.ScenarioEvidence(files,Map::of));
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
    private static final String SAVED_ANSWER="{\"schema\":\"finance-saved-report-answer.v1\",\"answer\":\"此版本执行失败，不能据此视为已取得完整客户事实。\",\"refs\":[\"/status\"]}";
    private static final String CAPABILITIES = "{\"schema\":\"finance-conversation-intent.v1\",\"kind\":\"CAPABILITIES\",\"objectRef\":null,\"reportRef\":null,\"revision\":null}";
    @Test @Timeout(30) void frozenThreeTurnClassificationRetainsObjectWithoutInventingExecutableGoal() throws Exception {
        customerMapping=Map.of("合成客户周明远","customer-a");
        installedActions=Set.of(ConversationIntent.Kind.PREPARE,ConversationIntent.Kind.PRODUCT_REVIEW);
        String resolve=RESOLVE_OBJECT.replace("synthetic-a","周明远");
        String clarify=PROPOSAL.replace("PREPARE","CLARIFY").replace("synthetic-a","周明远");
        try(var model=new OpenJiuwenIntentProposerTest.Fixture(CAPABILITIES.replace("CAPABILITIES","CLARIFY"),
                "请补充客户和筛选条件",resolve,"已记录客户，请明确筛选条件",clarify,"请补充金额、期限和渠道");
                var session=session(model.proposer())) {
            var request=(com.fasterxml.jackson.databind.node.ObjectNode)body("帮我给我负责的客户筛选产品",null);
            request.put("customerAlias","");
            for(String query:List.of("帮我给我负责的客户筛选产品","周明远吧","产品删选")) {
                var result=session.call("chat",request.deepCopy().put("query",query).put("runId",UUID.randomUUID().toString()));
                assertEquals("NEED_CLARIFICATION",result.get("status"));
                assertFalse(result.containsKey("confirmation"));assertFalse(result.containsKey("report"));
            }
            var last=HarnessJson.mapper().readTree(model.requests.get(4).path("messages").get(1).path("content").asText());
            assertEquals("合成客户周明远",last.path("selectedObject").asText());
            assertTrue(last.path("previousDialogue").toString().contains("帮我给我负责的客户筛选产品"));
            assertEquals(0,business.get());assertEquals(0,reportModels.get());
            assertEquals(6,model.requests.size());
        }
    }
    @Test @Timeout(30) void knownProductGoalSurvivesClarificationBeforeCustomerSupplement() throws Exception {
        installedActions=Set.of(ConversationIntent.Kind.PRODUCT_REVIEW);
        String product=MISSING_OBJECT.replace("PREPARE","PRODUCT_REVIEW");
        try(var model=new OpenJiuwenIntentProposerTest.Fixture(product,CAPABILITIES.replace("CAPABILITIES","CLARIFY"),
                "尚缺客户，不会批量执行",RESOLVE_OBJECT);var session=session(model.proposer())) {
            var request=(com.fasterxml.jackson.databind.node.ObjectNode)body("帮我给我负责的客户筛选产品",null);
            request.put("customerAlias","");
            var first=session.call("chat",request);
            assertEquals("NEED_CLARIFICATION",first.get("status"));
            session.call("chat",request.deepCopy().put("query","需要哪些条件").put("runId",UUID.randomUUID().toString()));
            var result=session.call("chat",request.deepCopy().put("query","synthetic-a吧").put("runId",UUID.randomUUID().toString()));
            planId(result);
            var confirmation=(Map<?,?>)result.get("confirmation");
            assertEquals("PRODUCT_REVIEW",confirmation.get("action"));
            assertTrue(confirmation.get("query").toString().startsWith("帮我给我负责的客户筛选产品"));
            assertEquals(0,business.get());assertEquals(0,reportModels.get());
        }
    }
    @Test @Timeout(30) void clarificationObjectStillRequiresLiveAuthorityAndCannotSurviveRevocation() throws Exception {
        String clarify=PROPOSAL.replace("PREPARE","CLARIFY");
        try(var model=new OpenJiuwenIntentProposerTest.Fixture(clarify);var session=session(model.proposer())) {
            revoked.set(true);
            var request=(com.fasterxml.jackson.databind.node.ObjectNode)body("synthetic-a",null);request.put("customerAlias","");
            assertThrows(SecurityException.class,()->session.call("chat",request));
            assertEquals(0,business.get());assertEquals(0,reportModels.get());
        }
    }
    @Test @Timeout(30) void clarificationCannotRestoreCancelledGoal() throws Exception {
        String clarify=PROPOSAL.replace("PREPARE","CLARIFY");
        try(var model=new OpenJiuwenIntentProposerTest.Fixture(MISSING_OBJECT,
                CAPABILITIES.replace("CAPABILITIES","CANCEL_TASK"),clarify,"请说明新的目标",RESOLVE_OBJECT,"请明确目标");
                var session=session(model.proposer())) {
            var request=(com.fasterxml.jackson.databind.node.ObjectNode)body("准备回访",null);request.put("customerAlias","");
            session.call("chat",request);
            session.call("chat",request.deepCopy().put("query","取消").put("runId",UUID.randomUUID().toString()));
            session.call("chat",request.deepCopy().put("query","synthetic-a").put("runId",UUID.randomUUID().toString()));
            var result=session.call("chat",request.deepCopy().put("query","这个客户").put("runId",UUID.randomUUID().toString()));
            assertFalse(result.containsKey("confirmation"));assertEquals(0,business.get());
            var payload=HarnessJson.mapper().readTree(model.requests.get(4).path("messages").get(1).path("content").asText());
            assertTrue(payload.path("pendingRequest").isNull());
        }
    }
    @Test @Timeout(30) void unboundOldReportProposalClarifiesBusinessGoalWithoutInventingReportOrQuery() throws Exception {
        customerMapping=Map.of("synthetic-a","customer-a","synthetic-b","customer-b");
        String other=PROPOSAL.replace("synthetic-a","synthetic-b");
        try(var model=new OpenJiuwenIntentProposerTest.Fixture(
                CAPABILITIES.replace("CAPABILITIES","EXPLAIN_REPORT"),other);var session=session(model.proposer())) {
            var result=session.call("chat",body("看下synthetic-b的回访",null));
            assertEquals("NEED_CLARIFICATION",result.get("status"));
            assertTrue(result.get("response").toString().contains("准备新的回访"));
            assertFalse(result.get("response").toString().contains("版本"));
            assertFalse(result.containsKey("confirmation"));
            assertEquals(0,business.get());assertEquals(0,reportModels.get());
            var confirmed=session.call("chat",body("为synthetic-b准备新的回访",null));
            assertEquals("synthetic-b",((Map<?,?>)confirmed.get("confirmation")).get("customerAlias"));
            assertEquals(0,business.get());assertEquals(0,reportModels.get());
        }
    }
    @Test @Timeout(30) void directoryResultReachesNextInterpretationWithoutBeingAnExecutionGrant() throws Exception {
        try(var model=new OpenJiuwenIntentProposerTest.Fixture(CAPABILITIES.replace("CAPABILITIES","LIST_OBJECTS"),
                CAPABILITIES.replace("CAPABILITIES","CLARIFY"),"请选择本次目标");var session=session(model.proposer())) {
            var request=(com.fasterxml.jackson.databind.node.ObjectNode)body("查看客户名单",null);
            request.put("customerAlias","");
            session.call("chat",request);
            session.call("chat",request.deepCopy().put("query","synthetic-a").put("runId",UUID.randomUUID().toString()));
            assertTrue(model.requests.get(1).path("messages").toString().contains("你当前可服务的客户有"));
            assertEquals(0,business.get());
        }
    }
    @Test @Timeout(30) void objectThenGoalProducesBoundConfirmationWithoutRepeatingDirectory() throws Exception {
        String select=PROPOSAL.replace("PREPARE","SELECT_OBJECT");
        try(var model=new OpenJiuwenIntentProposerTest.Fixture(select,"请说明目标",PROPOSAL);var session=session(model.proposer())) {
            var request=(com.fasterxml.jackson.databind.node.ObjectNode)body("synthetic-a",null);
            request.put("customerAlias","");
            assertEquals("NEED_CLARIFICATION",session.call("chat",request).get("status"));
            var result=session.call("chat",request.deepCopy().put("query","回访").put("runId",UUID.randomUUID().toString()));
            assertNotNull(planId(result));
            assertEquals("synthetic-a",((Map<?,?>)result.get("confirmation")).get("customerAlias"));
            var payload=HarnessJson.mapper().readTree(model.requests.get(2).path("messages").get(1).path("content").asText());
            assertEquals("synthetic-a",payload.path("selectedObject").asText());
            assertEquals(0,business.get());
        }
    }
    @Test @Timeout(30) void unresolvedNewObjectCannotSilentlyFallBackToPreviousCustomer() throws Exception {
        try(var model=new OpenJiuwenIntentProposerTest.Fixture(PROPOSAL.replace("PREPARE","SELECT_OBJECT"),"请说明目标",MISSING_OBJECT);
                var session=session(model.proposer())) {
            var request=(com.fasterxml.jackson.databind.node.ObjectNode)body("synthetic-a",null);request.put("customerAlias","");
            session.call("chat",request);
            var result=session.call("chat",request.deepCopy().put("query","为另一个不明确的客户准备回访").put("runId",UUID.randomUUID().toString()));
            assertFalse(result.containsKey("confirmation"));assertEquals(0,business.get());
        }
    }
    @Test @Timeout(30) void conversationalSelectionOverridesUnchangedOldUiSelectionButNotNewSelection() throws Exception {
        customerMapping=Map.of("synthetic-a","customer-a","synthetic-b","customer-b");
        String other=PROPOSAL.replace("synthetic-a","synthetic-b");
        try(var model=new OpenJiuwenIntentProposerTest.Fixture(other.replace("PREPARE","SELECT_OBJECT"),"请说明目标",other,PROPOSAL);
                var session=session(model.proposer())) {
            session.call("chat",body("换成synthetic-b",null));
            assertEquals("synthetic-b",((Map<?,?>)session.call("chat",body("回访",null)).get("confirmation")).get("customerAlias"));
            var input=HarnessJson.mapper().readTree(model.requests.get(2).path("messages").get(1).path("content").asText());
            assertEquals("synthetic-b",input.path("selectedObject").asText());
            // Explicitly changing the page selection resets the remembered conversational selection.
            var changed=(com.fasterxml.jackson.databind.node.ObjectNode)body("为synthetic-a回访",null);
            changed.put("customerAlias","");
            session.call("chat",changed);
            input=HarnessJson.mapper().readTree(model.requests.get(3).path("messages").get(1).path("content").asText());
            assertTrue(input.path("selectedObject").isNull());assertEquals(0,business.get());
        }
    }
    @Test @Timeout(30) void capabilityInterjectionDoesNotConsumePendingGoal() throws Exception {
        try(var model=new OpenJiuwenIntentProposerTest.Fixture(MISSING_OBJECT,CAPABILITIES,"可准备回访",RESOLVE_OBJECT);var session=session(model.proposer())) {
            var first=(com.fasterxml.jackson.databind.node.ObjectNode)body("准备回访",null);first.put("customerAlias","");
            session.call("chat",first);
            session.call("chat",first.deepCopy().put("query","你能做什么").put("runId",UUID.randomUUID().toString()));
            var result=session.call("chat",body("synthetic-a",null));
            assertNotNull(planId(result));assertEquals(0,business.get());
        }
    }
    @Test @Timeout(30) void cancelledGoalIsNotResumedByObjectSupplement() throws Exception {
        try(var model=new OpenJiuwenIntentProposerTest.Fixture(MISSING_OBJECT,CAPABILITIES.replace("CAPABILITIES","CANCEL_TASK"),
                RESOLVE_OBJECT,"请说明新的目标");var session=session(model.proposer())) {
            session.call("chat",body("准备回访",null));
            assertTrue(session.call("chat",body("算了，不执行",null)).get("response").toString().contains("已取消"));
            var result=session.call("chat",body("synthetic-a",null));
            assertFalse(result.containsKey("confirmation"));assertEquals(0,business.get());
            var afterCancel=HarnessJson.mapper().readTree(model.requests.get(2).path("messages").get(1).path("content").asText());
            assertEquals(1,afterCancel.path("previousDialogue").size());
            assertEquals("算了，不执行",afterCancel.path("previousDialogue").get(0).path("userText").asText());
        }
    }
    @Test @Timeout(30) void revokedDirectoryIsNotReplayedToModelAndDoesNotBlockGreeting() throws Exception {
        try(var model=new OpenJiuwenIntentProposerTest.Fixture(CAPABILITIES.replace("CAPABILITIES","LIST_OBJECTS"),
                CAPABILITIES.replace("CAPABILITIES","GREETING"),"你好");var session=session(model.proposer())) {
            var first=(com.fasterxml.jackson.databind.node.ObjectNode)body("名单",null);first.put("customerAlias","");
            session.call("chat",first);revoked.set(true);
            var result=session.call("chat",first.deepCopy().put("query","你好").put("runId",UUID.randomUUID().toString()));
            assertEquals("ANSWERED",result.get("status"));
            assertFalse(model.requests.get(1).path("messages").toString().contains("synthetic-a"));
            assertFalse(model.requests.get(2).path("messages").toString().contains("synthetic-a"));
            assertEquals(0,business.get());
        }
    }
    @Test @Timeout(30) void objectSelectionMustRecheckAuthorityBeforeWording() throws Exception {
        try(var model=new OpenJiuwenIntentProposerTest.Fixture(PROPOSAL.replace("PREPARE","SELECT_OBJECT"));var session=session(model.proposer())) {
            revoked.set(true);
            var request=(com.fasterxml.jackson.databind.node.ObjectNode)body("synthetic-a",null);request.put("customerAlias","");
            assertThrows(SecurityException.class,()->session.call("chat",request));
            assertEquals(1,model.requests.size());assertEquals(0,business.get());
        }
    }
    @Test @Timeout(30) void intentEntryEvidenceFailurePreventsModelAndBusiness() throws Exception {
        java.nio.file.Files.createFile(files.resolve("decision-evidence"));
        try(var model=new OpenJiuwenIntentProposerTest.Fixture(PROPOSAL);var session=session(model.proposer())) {
            assertThrows(io.github.bitlhk.finance.harness.core.evidence.ScenarioEvidence.Unavailable.class,
                    ()->session.call("chat",body(QUERY,null)));
            assertTrue(model.requests.isEmpty());assertEquals(0,business.get());
        }
    }
    @Test @Timeout(30) void directoryRevokedDuringWordingWithholdsLateText() throws Exception {
        try(var model=new OpenJiuwenIntentProposerTest.Fixture(CAPABILITIES.replace("CAPABILITIES","LIST_OBJECTS"),
                CAPABILITIES,"不应释放");var session=session(model.proposer())) {
            var request=(com.fasterxml.jackson.databind.node.ObjectNode)body("名单",null);request.put("customerAlias","");
            session.call("chat",request);
            model.onRequest=ignored->{if(model.requests.size()==3) revoked.set(true);};
            var output=new ArrayList<Map<String,Object>>();
            assertThrows(Exception.class,()->session.callStreaming("chat",request.deepCopy().put("query","你能做什么")
                    .put("runId",UUID.randomUUID().toString()),output::add));
            assertTrue(output.isEmpty());assertEquals(0,business.get());
        }
    }
    @Test @Timeout(30) void returnedReportIsRememberedAsOperationNotReplayedFinancialFacts() throws Exception {
        try(var model=new OpenJiuwenIntentProposerTest.Fixture(PROPOSAL,CAPABILITIES.replace("CAPABILITIES","GREETING"),"不客气");var session=session(model.proposer())) {
            String id=planId(session.call("chat",body(QUERY,null)));
            assertTrue(session.call("chat",body(QUERY,id)).containsKey("report"));
            session.call("chat",body("谢谢",null));
            assertTrue(model.requests.get(1).toString().contains("returnedReports"));
            var payload=HarnessJson.mapper().readTree(model.requests.get(1).path("messages").get(1).path("content").asText());
            assertEquals(1,payload.path("continuity").path("returnedReports").asInt());
            assertEquals(1,business.get());
        }
    }
    @Test @Timeout(30) void clarificationNumberUsesServerDialogueButStillRequiresConfirmation() throws Exception {
        String clarification="不能单独查询资料。可以为synthetic-a准备回访：1.确认准备；2.暂不执行。";
        try(var model=new OpenJiuwenIntentProposerTest.Fixture(CAPABILITIES.replace("CAPABILITIES","CLARIFY"),clarification,PROPOSAL);
            var session=session(model.proposer())) {
            var first=(com.fasterxml.jackson.databind.node.ObjectNode)body("帮我看看synthetic-a的情况",null);
            first.put("customerAlias","");
            assertEquals("NEED_CLARIFICATION",session.callStreaming("chat",first,ignored->{}).get("status"));
            var second=(com.fasterxml.jackson.databind.node.ObjectNode)body("1",null);second.put("customerAlias","");
            var result=session.callStreaming("chat",second,ignored->{});
            assertNotNull(planId(result));
            String actual=model.requests.get(2).path("messages").toString();
            assertTrue(actual.contains("previousDialogue"));assertTrue(actual.contains("帮我看看synthetic-a的情况"));
            assertTrue(actual.contains("确认准备"));assertEquals(0,business.get());
        }
    }
    @Test @Timeout(30) void newConversationDoesNotReceiveEarlierClarification() throws Exception {
        try(var model=new OpenJiuwenIntentProposerTest.Fixture(CAPABILITIES.replace("CAPABILITIES","CLARIFY"),"SYNTHETIC_OLD_OPTIONS",CAPABILITIES,"当前能力说明");
            var session=session(model.proposer())) {
            var first=(com.fasterxml.jackson.databind.node.ObjectNode)body("SYNTHETIC_OLD_GOAL",null);first.put("conversationContext","chat-a");
            session.callStreaming("chat",first,ignored->{});
            var second=(com.fasterxml.jackson.databind.node.ObjectNode)body("你能做什么",null);second.put("conversationContext","chat-b");
            session.callStreaming("chat",second,ignored->{});
            for(int i:List.of(2,3))assertFalse(model.requests.get(i).toString().contains("SYNTHETIC_OLD_"));
            assertEquals(0,business.get());
        }
    }
    @Test @Timeout(30) void naturalCapabilityReplyUsesRealSdkAndManagedReleaseChecksWithoutBusiness() throws Exception {
        try(var model=new OpenJiuwenIntentProposerTest.Fixture(CAPABILITIES,"我可以帮助准备客户回访，但不能购买产品。");
            var session=session(model.proposer())) {
            var events=new ArrayList<Map<String,Object>>();
            var result=session.callStreaming("chat",body("你能干啥",null),events::add);
            assertEquals("我可以帮助准备客户回访，但不能购买产品。",result.get("response"));
            assertEquals("ANSWERED",result.get("status"));
            assertEquals(2,model.requests.size(),"Intent and answer are two actual local SDK HTTP requests");
            assertEquals(0,business.get());assertEquals(0,reportModels.get());
            assertEquals(1,events.size());
            assertEquals("finance.conversation.progress",events.getFirst().get("name"));
            var progress=(Map<?,?>)events.getFirst().get("value");
            assertEquals(result.get("response"),progress.get("text"));
            assertEquals(false,progress.get("businessExecuted"));
            assertEquals(1,progress.get("sequence"));
            assertFalse(model.requests.get(1).toString().contains("synthetic-scope-a"));
            assertFalse(model.requests.get(1).toString().contains("owner-a"));
            try(var paths=java.nio.file.Files.list(files.resolve("decision-evidence"))) {
                var stages=paths.map(p->{try{return HarnessJson.mapper().readTree(java.nio.file.Files.readAllBytes(p)).path("stage").asText();}
                    catch(Exception failure){throw new AssertionError(failure);}}).toList();
                assertTrue(stages.containsAll(List.of("RESPONSE_ENTRY","RESPONSE_TEXT_RELEASE","RESPONSE_COMPLETE_RELEASE")));
            }
        }
    }
    @Test @Timeout(30) void greetingWithSlowReleaseKeepsAllSdkChunksWithoutBusinessOrSelectedCustomer() throws Exception {
        String answer = "你好。我可以介绍当前能力，不需要先选择客户，也没有查询客户事实。";
        try(var model=new OpenJiuwenIntentProposerTest.Fixture(CAPABILITIES.replace("CAPABILITIES","GREETING"),answer);
            var session=session(model.proposer())) {
            model.onRequest=ignored->{if(model.requests.size()==1)model.chunkCharacters=2;};
            var events=new ArrayList<Map<String,Object>>();
            var request=(com.fasterxml.jackson.databind.node.ObjectNode)body("你好",null);
            request.put("customerAlias","");
            var result=session.callStreaming("chat",request,event->{
                events.add(event);
                try { Thread.sleep(200); } catch(InterruptedException failure) { Thread.currentThread().interrupt();throw new AssertionError(failure); }
            });
            assertEquals(answer,result.get("response"));
            assertEquals("ANSWERED",result.get("status"));
            assertEquals(2,model.requests.size());
            assertEquals(0,business.get());assertEquals(0,reportModels.get());
            assertFalse(events.isEmpty());
            assertEquals(answer,((Map<?,?>)events.getLast().get("value")).get("text"));
        }
    }
    @Test @Timeout(30) void greetingAndCapabilitiesDoNotDependOnCrmAvailability() throws Exception {
        for(String kind:List.of("GREETING","CAPABILITIES","CLARIFY","UNSUPPORTED")) {
            try(var model=new OpenJiuwenIntentProposerTest.Fixture(CAPABILITIES.replace("CAPABILITIES",kind),"请说明目标；尚未查询客户事实。");
                var session=session(model.proposer())) {
                authorityUnavailable.set(true);
                authorityReads.set(0);
                var request=(com.fasterxml.jackson.databind.node.ObjectNode)body("你好，你能做什么？",null);
                request.put("customerAlias","");
                var result=session.callStreaming("chat",request,ignored->{});
                assertEquals(kind.equals("CLARIFY")?"NEED_CLARIFICATION":"ANSWERED",result.get("status"));
                assertEquals(0,authorityReads.get(),"Plain conversation cannot depend on CRM relationship lookup");
                assertFalse(model.requests.getFirst().path("messages").toString().contains("synthetic-a"),
                        "Server-side mapping hints must not disclose the customer directory to the intent model");
                assertEquals(0,business.get());assertEquals(0,reportModels.get());
            }
        }
    }
    @Test @Timeout(30) void dataIntentStillRequiresLiveAuthorityEvenWhenItStartsWithGreeting() throws Exception {
        for(String intent:List.of(PROPOSAL,CAPABILITIES.replace("CAPABILITIES","LIST_OBJECTS"))) {
            try(var model=new OpenJiuwenIntentProposerTest.Fixture(intent);var session=session(model.proposer())) {
                authorityUnavailable.set(true);
                assertThrows(RuntimeException.class,()->session.call("chat",body("你好，帮我看看客户",null)));
                assertEquals(0,business.get());assertEquals(0,reportModels.get());
            }
        }
    }
    @Test @Timeout(30) void realCrmHttpOutageIsNotAGreetingDependencyButStillBlocksData() throws Exception {
        var crm=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        var requests=new AtomicInteger();
        crm.createContext("/",exchange->{requests.incrementAndGet();exchange.sendResponseHeaders(503,-1);exchange.close();});
        crm.start();
        try(var model=new OpenJiuwenIntentProposerTest.Fixture(CAPABILITIES.replace("CAPABILITIES","GREETING"),"你好。尚未查询客户事实。",PROPOSAL)) {
            var authority=new io.github.bitlhk.finance.harness.providers.customercrm.CustomerCrmAuthority(
                    "owner-a","employee-a","revision-a",Set.of("customer-a"),java.time.Instant.now().plusSeconds(120),FinanceContextV1.AuthenticationLevel.BASIC);
            var client=new io.github.bitlhk.finance.harness.providers.customercrm.CustomerCrmClient(
                    java.net.URI.create("http://127.0.0.1:"+crm.getAddress().getPort()),java.time.Duration.ofSeconds(2),()->"synthetic-only");
            var provider=new io.github.bitlhk.finance.harness.providers.customercrm.CustomerCrmProvider("owner-a",client,()->authority,java.time.Clock.systemUTC());
            try(var session=session(model.proposer(),new DirectCrmPreparationAccess("owner-a","session-a",provider))) {
                assertEquals("ANSWERED",session.call("chat",body("你好",null)).get("status"));
                assertEquals(0,requests.get(),"Actual local CRM HTTP requests, not a mock counter");
                var error=assertThrows(io.github.bitlhk.finance.harness.core.ConversationFailure.class,
                        ()->session.call("chat",body("你好，帮我分析客户",null)));
                assertEquals("CONVERSATION_AUTHORITY_UNAVAILABLE",error.getMessage());
                assertEquals(1,requests.get());
                assertEquals(0,reportModels.get());
            }
        } finally { crm.stop(0); }
    }
    @Test @Timeout(30) void changedScopeWhileAnsweringBlocksTextAndFinalResult() throws Exception {
        try(var model=new OpenJiuwenIntentProposerTest.Fixture(CAPABILITIES,"不应释放");var session=session(model.proposer())) {
            model.onRequest=ignored->{if(model.requests.size()==2)scope.set("changed");};
            var events=new ArrayList<Map<String,Object>>();
            assertThrows(SecurityException.class,()->session.callStreaming("chat",body("你是谁",null),events::add));
            assertTrue(events.isEmpty());assertEquals(0,business.get());
        }
    }
    @Test @Timeout(30) void unavailableEvidencePreventsIntentAndAnswerRequests() throws Exception {
        try(var model=new OpenJiuwenIntentProposerTest.Fixture(CAPABILITIES,"不应调用");var session=session(model.proposer())) {
            java.nio.file.Files.createFile(files.resolve("decision-evidence"));
            var events=new ArrayList<Map<String,Object>>();
            assertThrows(io.github.bitlhk.finance.harness.core.evidence.ScenarioEvidence.Unavailable.class,
                    ()->session.callStreaming("chat",body("你是谁",null),events::add));
            assertEquals(0,model.requests.size());assertTrue(events.isEmpty());assertEquals(0,business.get());
        }
    }
    @Test @Timeout(30) void answerReleaseEvidenceFailureDoesNotPublishText() throws Exception {
        try(var model=new OpenJiuwenIntentProposerTest.Fixture(CAPABILITIES,"不应释放");var session=session(model.proposer())) {
            model.onRequest=ignored->{
                if(model.requests.size()==2)try{
                    java.nio.file.Files.move(files.resolve("decision-evidence"),files.resolve("retained-response-entry"));
                    java.nio.file.Files.createFile(files.resolve("decision-evidence"));
                }catch(Exception failure){throw new AssertionError(failure);}
            };
            var events=new ArrayList<Map<String,Object>>();
            var failure=assertThrows(io.github.bitlhk.finance.harness.core.evidence.ScenarioEvidence.Unavailable.class,
                    ()->session.callStreaming("chat",body("你是谁",null),events::add));
            assertEquals("RESPONSE_TEXT_RELEASE",failure.stage());
            assertEquals("INVOKED_SEND_UNOBSERVED",failure.providerDispatch());
            assertEquals(2,model.requests.size());assertTrue(events.isEmpty());assertEquals(0,business.get());
        }
    }
    @Test @Timeout(30) void selectedVersionExplanationUsesNoNewBusinessQueryAndDoesNotRewriteReport() throws Exception {
        String[] replies={PROPOSAL,"",SAVED_ANSWER};
        try(var model=new OpenJiuwenIntentProposerTest.Fixture(replies);var session=session(model.proposer())) {
            String plan=planId(session.call("chat",body(QUERY,null)));
            JsonNode saved=HarnessJson.mapper().valueToTree(session.call("chat",body(QUERY,plan)).get("report"));
            String reportId=saved.path("reportId").asText();int revision=saved.path("reportRevision").asInt();
            replies[1]="{\"schema\":\"finance-conversation-intent.v1\",\"kind\":\"EXPLAIN_REPORT\",\"objectRef\":null,\"reportRef\":\""+reportId+"\",\"revision\":"+revision+"}";
            var question=(com.fasterxml.jackson.databind.node.ObjectNode)body("为什么这份报告不完整？",null);
            question.put("reportId",reportId).put("revision",revision);
            var reply=session.call("chat",question);
            assertEquals("ANSWERED",reply.get("status"));
            JsonNode explanation=HarnessJson.mapper().valueToTree(reply.get("savedExplanation"));
            assertEquals(reportId,explanation.path("binding").path("reportId").asText());
            assertEquals(revision,explanation.path("binding").path("revision").asInt());
            assertEquals(0,explanation.path("businessQueries").asInt());
            assertEquals(1,explanation.path("explanationModelInvocations").asInt());
            assertEquals(1,business.get());assertEquals(0,reportModels.get());assertEquals(3,model.requests.size());
            JsonNode after=HarnessJson.mapper().valueToTree(session.call("latest",HarnessJson.mapper().createObjectNode()).get("report"));
            assertEquals(io.github.bitlhk.finance.harness.core.evidence.ScenarioEvidence.digest(saved),
                    io.github.bitlhk.finance.harness.core.evidence.ScenarioEvidence.digest(after));
            assertFalse(model.requests.get(2).toString().contains("synthetic-authority"));
            assertFalse(model.requests.get(2).toString().contains("owner-a"));
        }
    }
    @Test @Timeout(30) void revocationDuringSavedExplanationBlocksLateAnswerWithoutBusinessRetry() throws Exception {
        String[] replies={PROPOSAL,"",SAVED_ANSWER};
        try(var model=new OpenJiuwenIntentProposerTest.Fixture(replies);var session=session(model.proposer())) {
            String plan=planId(session.call("chat",body(QUERY,null)));
            JsonNode saved=HarnessJson.mapper().valueToTree(session.call("chat",body(QUERY,plan)).get("report"));
            String id=saved.path("reportId").asText();int version=saved.path("reportRevision").asInt();
            replies[1]="{\"schema\":\"finance-conversation-intent.v1\",\"kind\":\"EXPLAIN_REPORT\",\"objectRef\":null,\"reportRef\":\""+id+"\",\"revision\":"+version+"}";
            model.onRequest=ignored->{if(model.requests.size()==3)revoked.set(true);};
            var question=(com.fasterxml.jackson.databind.node.ObjectNode)body("解释这份报告",null);
            question.put("reportId",id).put("revision",version);
            assertThrows(SecurityException.class,()->session.call("chat",question));
            assertEquals(1,business.get());assertEquals(3,model.requests.size());
        }
    }
    @Test @Timeout(30) void savedExplanationEvidenceFailureAfterModelWithholdsAnswer() throws Exception {
        String[] replies={PROPOSAL,"",SAVED_ANSWER};
        try(var model=new OpenJiuwenIntentProposerTest.Fixture(replies);var session=session(model.proposer())) {
            String plan=planId(session.call("chat",body(QUERY,null)));
            JsonNode saved=HarnessJson.mapper().valueToTree(session.call("chat",body(QUERY,plan)).get("report"));
            String id=saved.path("reportId").asText();int version=saved.path("reportRevision").asInt();
            replies[1]="{\"schema\":\"finance-conversation-intent.v1\",\"kind\":\"EXPLAIN_REPORT\",\"objectRef\":null,\"reportRef\":\""+id+"\",\"revision\":"+version+"}";
            model.onRequest=ignored->{
                if(model.requests.size()==3)try {
                    java.nio.file.Files.move(files.resolve("decision-evidence"),files.resolve("preserved-read-receipts"));
                    java.nio.file.Files.createFile(files.resolve("decision-evidence"));
                }catch(java.io.IOException failure){throw new AssertionError(failure);}
            };
            var question=(com.fasterxml.jackson.databind.node.ObjectNode)body("解释这份报告",null);
            question.put("reportId",id).put("revision",version);
            var failure=assertThrows(io.github.bitlhk.finance.harness.core.evidence.ScenarioEvidence.Unavailable.class,()->session.call("chat",question));
            assertEquals("EXPLANATION_RELEASE_AFTER_MODEL",failure.stage());
            assertEquals(1,business.get());assertEquals(3,model.requests.size());
        }
    }
    @Test @Timeout(30) void missingVersionRejectsBeforeIntentOrExplanationModel() throws Exception {
        try(var model=new OpenJiuwenIntentProposerTest.Fixture(PROPOSAL);var session=session(model.proposer())) {
            String plan=planId(session.call("chat",body(QUERY,null)));
            JsonNode saved=HarnessJson.mapper().valueToTree(session.call("chat",body(QUERY,plan)).get("report"));
            var question=(com.fasterxml.jackson.databind.node.ObjectNode)body("解释这份报告",null);
            question.put("reportId",saved.path("reportId").asText()).put("revision",999);
            assertThrows(IllegalArgumentException.class,()->session.call("chat",question));
            assertEquals(1,business.get());assertEquals(1,model.requests.size());
        }
    }
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
            java.nio.file.Files.move(files.resolve("decision-evidence"),files.resolve("retained-intent-evidence"));
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
