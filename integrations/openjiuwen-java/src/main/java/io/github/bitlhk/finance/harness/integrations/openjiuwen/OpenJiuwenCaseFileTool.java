package io.github.bitlhk.finance.harness.integrations.openjiuwen;

import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.foundation.tool.function.LocalFunction;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.runner.base.TagMatchStrategy;
import com.openjiuwen.core.singleagent.BaseAgent;
import io.github.bitlhk.finance.harness.execution.CaseFileWorkspaceV1;
import java.util.*;

/** Host-managed auxiliary tool; mounting uses the same SDK resource lock as financial tools. */
public final class OpenJiuwenCaseFileTool implements AutoCloseable {
    private final BaseAgent agent;
    private final String owner;
    private boolean closed;
    public OpenJiuwenCaseFileTool(BaseAgent agent, String owner, CaseFileWorkspaceV1 files, Runnable authorize) {
        this.agent = Objects.requireNonNull(agent); this.owner = Objects.requireNonNull(owner);
        Objects.requireNonNull(files); Objects.requireNonNull(authorize);
        if (agent.getAbilityManager().get(CaseFileWorkspaceV1.TOOL) != null
                || Runner.resourceMgr().getTool(CaseFileWorkspaceV1.TOOL) != null)
            throw new IllegalStateException("CASE_TOOL_NAME_CONFLICT");
        var card = ToolCard.builder().id(CaseFileWorkspaceV1.TOOL).name(CaseFileWorkspaceV1.TOOL)
                .description(CaseFileWorkspaceV1.DESCRIPTION).inputParams(CaseFileWorkspaceV1.PARAMETERS).build();
        var tool = new LocalFunction(card, arguments -> {
            authorize.run();
            var result = files.read(arguments);
            authorize.run();
            return result;
        });
        agent.getAbilityManager().add(card);
        try {
            var added = Runner.resourceMgr().addTool(tool, List.of(owner), true);
            if (added.isError()) throw new IllegalStateException("CASE_TOOL_INSTALLATION_FAILED");
        } catch (RuntimeException failed) { agent.getAbilityManager().remove(CaseFileWorkspaceV1.TOOL); throw failed; }
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        agent.getAbilityManager().remove(CaseFileWorkspaceV1.TOOL);
        Runner.resourceMgr().removeTool(CaseFileWorkspaceV1.TOOL, List.of(owner), TagMatchStrategy.ALL, true);
    }
}
