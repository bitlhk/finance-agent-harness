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
    @Test void nonExecutionKindsCannotSmuggleAnObjectOrReport() {
        for (String kind : new String[]{"GREETING", "CAPABILITIES", "LIST_OBJECTS", "CLARIFY", "UNSUPPORTED"}) {
            assertFalse(parse(proposal(kind, "null", "null", "null")).requiresExecutionConfirmation());
            rejected(proposal(kind, "\"synthetic-a\"", "null", "null"));
            rejected(proposal(kind, "null", "\"report-a\"", "3"));
        }
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
