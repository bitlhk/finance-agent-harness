package io.github.bitlhk.finance.harness.taskinteraction;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TaskInteractionContractTest {
    static final String HASH="sha256:"+"1".repeat(64);
    static ObjectNode fixture() {
        var n=TaskInteractionMapper.json().createObjectNode();
        n.put("schemaVersion","finance-task-snapshot.v1").put("taskRef","task_1").put("sourceObjectRef","SYNTHETIC_INS_001")
            .put("sourceCommit",TaskInteractionV1.SOURCE_COMMIT).put("sourceRelease",HASH).put("version",HASH).put("materialVersion",HASH)
            .put("originalState","pending_review").put("displayState","WAITING_REVIEW");
        n.putArray("allowedActions").add("approve").add("reject");n.putArray("inputRequired");n.putArray("unresolvedRequestIds");
        n.putObject("rawResult").put("id","SYNTHETIC_INS_001").put("status","pending_review");return n;
    }
    static TaskInteractionV1 map(ObjectNode n) throws Exception {
        return TaskInteractionMapper.snapshot(TaskInteractionMapper.json().writeValueAsBytes(n),new TaskInteractionMapper.Expected("task_1","SYNTHETIC_INS_001",HASH));
    }
    @Test void bindsSourceAndDefensivelyCopiesOriginalResult() throws Exception {
        var original=fixture();var mapped=map(original);
        original.with("rawResult").put("status","executed");
        ((ObjectNode)mapped.rawResult()).put("status","executed");
        assertEquals("pending_review",mapped.rawResult().path("status").asText());
    }
    @Test void rejectsWrongIdentityExtraFieldsAndFalseSuccess() {
        var wrong=fixture().put("taskRef","other_task");assertThrows(IllegalArgumentException.class,()->map(wrong));
        var extra=fixture().put("actorRef","forged");assertThrows(IllegalArgumentException.class,()->map(extra));
        var falseSuccess=fixture().put("displayState","SUCCEEDED");assertThrows(IllegalArgumentException.class,()->map(falseSuccess));
    }
    @Test void unresolvedOriginalOperationIsUnknownAndNotActionable() throws Exception {
        var n=fixture();n.withArray("unresolvedRequestIds").add("req_1");
        assertThrows(IllegalArgumentException.class,()->map(n));
        n.put("displayState","UNKNOWN");n.withArray("allowedActions").removeAll();
        assertEquals("UNKNOWN",map(n).displayState());
    }
}
