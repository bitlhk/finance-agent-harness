package io.github.bitlhk.finance.harness.taskinteraction;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TaskInteractionActionsTest {
    @Test void requiresSeparateExecutionConfirmationAndExactMaterialVersion() throws Exception {
        var p=TaskInteractionMapper.json().createObjectNode();
        assertThrows(IllegalArgumentException.class,()->new TaskInteractionActions.Action("task_1","r1",TaskInteractionContractTest.HASH,
            TaskInteractionContractTest.HASH,"execute",p,false));
        var snapshot=TaskInteractionContractTest.map(TaskInteractionContractTest.fixture());
        var stale=new TaskInteractionActions.Action("task_1","r1",TaskInteractionContractTest.HASH,"sha256:"+"2".repeat(64),"approve",p,false);
        assertThrows(IllegalArgumentException.class,()->TaskInteractionActions.validate(snapshot,stale));
        var valid=new TaskInteractionActions.Action("task_1","r1",TaskInteractionContractTest.HASH,TaskInteractionContractTest.HASH,"approve",p,false);
        assertDoesNotThrow(()->TaskInteractionActions.validate(snapshot,valid));
    }
    @Test void arbitraryCallsAndUnsafeAttachmentsAreRejected() {
        var p=TaskInteractionMapper.json().createObjectNode().put("attachmentRef","../secret");
        assertThrows(IllegalArgumentException.class,()->new TaskInteractionActions.Action("task_1","r1",TaskInteractionContractTest.HASH,
            TaskInteractionContractTest.HASH,"supplement",p,false));
        assertThrows(IllegalArgumentException.class,()->TaskInteractionActions.role("shell"));
    }
}
