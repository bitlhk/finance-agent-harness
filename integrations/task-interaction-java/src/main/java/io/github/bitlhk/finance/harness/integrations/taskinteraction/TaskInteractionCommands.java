package io.github.bitlhk.finance.harness.integrations.taskinteraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.bitlhk.finance.harness.taskinteraction.*;
import java.util.Set;

/** No caller-supplied identity, confirmation event, URL or code in the HTTP/session command surface. */
public final class TaskInteractionCommands {
    public static final Set<String> ACTIONS=Set.of("query","taskSnapshot","propose","status","history","material","cancel","end");
    private TaskInteractionCommands(){}
    public static void validate(String action,JsonNode body){
        if(!ACTIONS.contains(action))throw new IllegalArgumentException("TASK_COMMAND_UNSUPPORTED");
        if(action.equals("query")){
            TaskInteractionMapper.fields(body,Set.of("query"));
            if(TaskInteractionMapper.text(body,"query").length()>2000)TaskInteractionMapper.invalid();return;
        }
        if(action.equals("material")){TaskInteractionMapper.fields(body,Set.of("materialRef"));TaskInteractionMapper.id(TaskInteractionMapper.text(body,"materialRef"));return;}
        if(action.equals("propose")){
            TaskInteractionMapper.fields(body,Set.of("action","expectedVersion","materialVersion","parameters"));
            TaskInteractionMapper.hash(TaskInteractionMapper.text(body,"expectedVersion"));TaskInteractionMapper.hash(TaskInteractionMapper.text(body,"materialVersion"));
            // Validate the finite action independently; only the Host can later supply an Approval object.
            new TaskInteractionActions.Action("validation_task","validation_request",body.path("expectedVersion").asText(),body.path("materialVersion").asText(),body.path("action").asText(),body.get("parameters"),true);return;
        }
        TaskInteractionMapper.fields(body,Set.of());
    }
}
