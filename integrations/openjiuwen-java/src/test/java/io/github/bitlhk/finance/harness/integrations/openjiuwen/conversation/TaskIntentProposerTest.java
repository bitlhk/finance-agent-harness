package io.github.bitlhk.finance.harness.integrations.openjiuwen.conversation;

import io.github.bitlhk.finance.harness.core.HarnessJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class TaskIntentProposerTest {
    private static final String CONTEXT="""
            {"availableActions":[{"ref":"VERIFY","description":"只读核验"}],
             "authorizedObjects":["合成对象B"],"pendingRequest":null}
            """;
    private static String intent(String kind,String action,String object) {
        return "{\"schema\":\"finance-task-intent.v1\",\"kind\":\""+kind+"\",\"actionRef\":"+action+",\"objectRef\":"+object+"}";
    }
    @ParameterizedTest
    @ValueSource(strings={"GREETING","CAPABILITIES","LIST_OBJECTS","CLARIFY","EXPLAIN_REPORT","UNSUPPORTED"})
    void nonExecutingKindsCannotCarryObjectOrAction(String kind) throws Exception {
        var context=HarnessJson.mapper().readTree(CONTEXT);
        assertEquals(kind,TaskIntentProposer.validate(intent(kind,"null","null"),context).path("kind").asText());
        assertThrows(IllegalArgumentException.class,()->TaskIntentProposer.validate(intent(kind,"\"VERIFY\"","null"),context));
        assertThrows(IllegalArgumentException.class,()->TaskIntentProposer.validate(intent(kind,"null","\"合成对象B\""),context));
    }
    @Test void executeIsOnlyProposalOfInstalledActionAndKnownObject() throws Exception {
        var context=HarnessJson.mapper().readTree(CONTEXT);
        assertEquals("EXECUTE",TaskIntentProposer.validate(intent("EXECUTE","\"VERIFY\"","\"合成对象B\""),context).path("kind").asText());
        assertTrue(TaskIntentProposer.validate(intent("EXECUTE","\"VERIFY\"","null"),context).path("objectRef").isNull());
        assertThrows(IllegalArgumentException.class,()->TaskIntentProposer.validate(intent("EXECUTE","\"TRANSFER\"","null"),context));
        assertThrows(IllegalArgumentException.class,()->TaskIntentProposer.validate(intent("EXECUTE","\"VERIFY\"","\"其他对象\""),context));
    }
    @Test void resolveNeedsPendingRequestAndDoesNotChangeAction() throws Exception {
        var context=(com.fasterxml.jackson.databind.node.ObjectNode)HarnessJson.mapper().readTree(CONTEXT);
        assertThrows(IllegalArgumentException.class,()->TaskIntentProposer.validate(intent("RESOLVE_OBJECT","null","\"合成对象B\""),context));
        context.putObject("pendingRequest").put("actionRef","VERIFY");
        assertEquals("RESOLVE_OBJECT",TaskIntentProposer.validate(intent("RESOLVE_OBJECT","null","\"合成对象B\""),context).path("kind").asText());
        assertThrows(IllegalArgumentException.class,()->TaskIntentProposer.validate(intent("RESOLVE_OBJECT","\"VERIFY\"","\"合成对象B\""),context));
    }
    @Test void continuityActionsRequireExplicitAdapterSupportAndCannotCarryExecution() throws Exception {
        var context=(com.fasterxml.jackson.databind.node.ObjectNode)HarnessJson.mapper().readTree(CONTEXT);
        String select=intent("SELECT_OBJECT","null","\"合成对象B\"");
        String cancel=intent("CANCEL_TASK","null","null");
        assertThrows(IllegalArgumentException.class,()->TaskIntentProposer.validate(select,context));
        assertThrows(IllegalArgumentException.class,()->TaskIntentProposer.validate(cancel,context));
        context.putObject("continuity").put("schema","conversation-continuity.v1");
        assertEquals("SELECT_OBJECT",TaskIntentProposer.validate(select,context).path("kind").asText());
        assertEquals("CANCEL_TASK",TaskIntentProposer.validate(cancel,context).path("kind").asText());
        assertThrows(IllegalArgumentException.class,()->TaskIntentProposer.validate(intent("SELECT_OBJECT","\"VERIFY\"","\"合成对象B\""),context));
        assertThrows(IllegalArgumentException.class,()->TaskIntentProposer.validate(intent("SELECT_OBJECT","null","\"未知对象\""),context));
        assertThrows(IllegalArgumentException.class,()->TaskIntentProposer.validate(intent("CANCEL_TASK","null","\"合成对象B\""),context));
    }
    @Test void optedInClarificationAndOrphanObjectAreHintsNotActions() throws Exception {
        var context=(com.fasterxml.jackson.databind.node.ObjectNode)HarnessJson.mapper().readTree(CONTEXT);
        context.putObject("continuity").put("schema","conversation-continuity.v1");
        for(String kind:new String[]{"CLARIFY","RESOLVE_OBJECT"}) {
            assertEquals(kind,TaskIntentProposer.validate(intent(kind,"null","\"合成对象B\""),context).path("kind").asText());
            assertThrows(IllegalArgumentException.class,()->TaskIntentProposer.validate(intent(kind,"\"VERIFY\"","\"合成对象B\""),context));
            assertThrows(IllegalArgumentException.class,()->TaskIntentProposer.validate(intent(kind,"null","\"未知对象\""),context));
        }
    }
    @ParameterizedTest
    @ValueSource(strings={
            "{\"schema\":\"finance-task-intent.v1\",\"kind\":\"GREETING\",\"actionRef\":null,\"objectRef\":null,\"role\":\"admin\"}",
            "{\"schema\":\"finance-task-intent.v1\",\"kind\":\"GREETING\",\"kind\":\"EXECUTE\",\"actionRef\":null,\"objectRef\":null}",
            "{\"schema\":\"finance-task-intent.v1\",\"kind\":\"EXECUTE\",\"actionRef\":[],\"objectRef\":null}",
            "{\"schema\":\"finance-task-intent.v1\",\"kind\":\"EXECUTE\",\"actionRef\":\"VERIFY\",\"objectRef\":{}}",
            "{\"schema\":\"finance-task-intent.v1\",\"kind\":\"GREETING\",\"actionRef\":null,\"objectRef\":null} {}",
            "null","{}","```json\n{}\n```"
    })
    void malformedOrAuthorityFieldsFailClosed(String raw) throws Exception {
        var context=HarnessJson.mapper().readTree(CONTEXT);
        assertThrows(IllegalArgumentException.class,()->TaskIntentProposer.validate(raw,context));
    }
}
