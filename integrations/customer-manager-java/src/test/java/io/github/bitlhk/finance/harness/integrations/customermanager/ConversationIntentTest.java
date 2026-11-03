package io.github.bitlhk.finance.harness.integrations.customermanager;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

final class ConversationIntentTest {
    private static final Set<String> OBJECTS = Set.of("synthetic-a", "synthetic-b");
    private static final Set<ConversationIntent.Kind> ACTIONS = Set.of(ConversationIntent.Kind.PREPARE);
    private static String proposal(String kind, String object, String report, String revision) {
        return "{\"schema\":\"finance-conversation-intent.v1\",\"kind\":\"" + kind
                + "\",\"objectRef\":" + object + ",\"reportRef\":" + report + ",\"revision\":" + revision + "}";
    }
    private static ConversationIntent parse(String text) { return ConversationIntent.parse(text, OBJECTS, ACTIONS); }
    private static void rejected(String text) {
        var error = assertThrows(IllegalArgumentException.class, () -> parse(text));
        assertEquals("CONVERSATION_INTENT_INVALID", error.getMessage());
        assertNull(error.getCause());
    }
    @Test void executionIsOnlyASuggestionRequiringConfirmation() {
        var value = parse(proposal("PREPARE", "\"synthetic-a\"", "null", "null"));
        assertTrue(value.requiresExecutionConfirmation());
        assertEquals("synthetic-a", value.objectRef());
        assertNull(parse(proposal("PREPARE", "null", "null", "null")).objectRef());
    }
    @Test void displayNameResolutionUsesOnlyExistingServerCandidates() {
        var value = ConversationIntent.parse(proposal("PREPARE", "\"Alice\"", "null", "null"),
                Set.of("合成客户Alice"), ACTIONS);
        assertEquals("合成客户Alice", value.objectRef());
        assertTrue(value.requiresExecutionConfirmation());
        assertThrows(IllegalArgumentException.class, () -> ConversationIntent.parse(
                proposal("PREPARE", "\"Unknown\"", "null", "null"), Set.of("合成客户Alice"), ACTIONS));
    }
    @Test void nonExecutionKindsCannotSmuggleAnObjectOrReport() {
        for (String kind : new String[]{"GREETING", "CAPABILITIES", "LIST_OBJECTS", "UNSUPPORTED"}) {
            assertFalse(parse(proposal(kind, "null", "null", "null")).requiresExecutionConfirmation());
            rejected(proposal(kind, "\"synthetic-a\"", "null", "null"));
            rejected(proposal(kind, "null", "\"report-a\"", "3"));
        }
    }
    @Test void frozenClarificationMayCarryOnlyAKnownNonExecutableObjectHint() {
        var frozen = "{\"schema\":\"finance-conversation-intent.v1\",\"kind\":\"CLARIFY\",\"objectRef\":\"周明远\",\"reportRef\":null,\"revision\":null}";
        var intent = ConversationIntent.parse(frozen, Set.of("合成客户周明远"), ACTIONS);
        assertEquals("合成客户周明远", intent.objectRef());
        assertFalse(intent.requiresExecutionConfirmation());
        assertFalse(parse(proposal("CLARIFY", "null", "null", "null")).requiresExecutionConfirmation());
        rejected(proposal("CLARIFY", "\"other-owner\"", "null", "null"));
        rejected(proposal("CLARIFY", "\"synthetic-a\"", "\"report-a\"", "3"));
        rejected(proposal("CLARIFY", "null", "null", "3"));
        assertThrows(IllegalArgumentException.class, () -> ConversationIntent.parse(frozen,
                Set.of("合成客户周明远", " 合成客户周明远"), ACTIONS));
    }
    @Test void reportExplanationRequiresAnExactVersionOrClarification() {
        var value = parse(proposal("EXPLAIN_REPORT", "\"synthetic-a\"", "\"report-a\"", "3"));
        assertFalse(value.requiresExecutionConfirmation());
        assertEquals(3, value.revision());
        assertNull(parse(proposal("EXPLAIN_REPORT", "null", "null", "null")).reportRef());
        rejected(proposal("EXPLAIN_REPORT", "null", "\"report-a\"", "null"));
        for (String version : new String[]{"0", "-1", "3.0", "\"3\"", "2147483648"})
            rejected(proposal("EXPLAIN_REPORT", "null", "\"report-a\"", version));
    }
    @Test void modelCannotInventAuthorityCapabilitiesOrExecutableArguments() {
        rejected(proposal("PREPARE", "\"other-owner-object\"", "null", "null"));
        rejected(proposal("PRODUCT_REVIEW", "\"synthetic-a\"", "null", "null"));
        rejected(proposal("TRANSFER", "null", "null", "null"));
        String valid = proposal("PREPARE", "\"synthetic-a\"", "null", "null");
        for (String field : new String[]{"owner", "confirmed", "scope", "query", "tool", "parameters"})
            rejected(valid.substring(0, valid.length()-1) + ",\"" + field + "\":true}");
    }
    @Test void knowledgeIntentRequiresTheBuildInstalledCapability() {
        String proposal = proposal("KNOWLEDGE_QUERY", "null", "null", "null");
        rejected(proposal);
        var value = ConversationIntent.parse(proposal, OBJECTS,
                Set.of(ConversationIntent.Kind.PREPARE, ConversationIntent.Kind.KNOWLEDGE_QUERY));
        assertEquals(ConversationIntent.Kind.KNOWLEDGE_QUERY, value.kind());
        assertFalse(value.requiresExecutionConfirmation());
    }
    @Test void knowledgeClassifierInstructionCoversNaturalPolicyQuestions() {
        assertTrue(OpenJiuwenIntentProposer.INSTRUCTION.contains("需要满足什么条件"));
        assertTrue(OpenJiuwenIntentProposer.INSTRUCTION.contains("应履行什么要求"));
        assertTrue(OpenJiuwenIntentProposer.INSTRUCTION.contains("availableActions包含KNOWLEDGE_QUERY"));
    }
    @Test void directPolicyQuestionRequiresInstalledKnowledgeCapability() {
        var installed = Set.of(ConversationIntent.Kind.KNOWLEDGE_QUERY);
        assertTrue(OpenJiuwenIntentProposer.isDirectKnowledgeQuestion("申请个人贷款应当具备哪些条件？", installed));
        assertTrue(OpenJiuwenIntentProposer.isDirectKnowledgeQuestion("向老年客户销售高风险产品时应履行什么义务？", installed));
        assertFalse(OpenJiuwenIntentProposer.isDirectKnowledgeQuestion("帮我准备周明远的回访", installed));
        assertFalse(OpenJiuwenIntentProposer.isDirectKnowledgeQuestion("申请个人贷款应当具备哪些条件？", Set.of()));
    }
    @Test void duplicateTrailingUnknownAndMalformedOutputFailClosedWithoutEcho() {
        String valid = proposal("GREETING", "null", "null", "null");
        rejected(valid + "{}");
        rejected(valid.replace("\"kind\":", "\"kind\":\"PREPARE\",\"kind\":"));
        rejected(valid.replace("intent.v1", "intent.v2"));
        rejected("```json\n" + valid + "\n```");
        rejected("{private synthetic content");
        rejected("x".repeat(4097));
        rejected("null");
    }
}
