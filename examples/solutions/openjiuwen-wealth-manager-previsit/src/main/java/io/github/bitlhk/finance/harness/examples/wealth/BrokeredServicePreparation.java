package io.github.bitlhk.finance.harness.examples.wealth;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.bitlhk.finance.harness.integrations.employeeagent.EmployeeAgentPreparationBroker;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Managed-host facade. Remote authority checks never run under the task monitor. */
public final class BrokeredServicePreparation implements AutoCloseable {
    private final String owner;
    private final EmployeeAgentPreparationBroker broker;
    private final ServicePreparation task;

    public BrokeredServicePreparation(String owner, EmployeeAgentPreparationBroker broker,
            ServicePreparation.Explanation explanation, Path reports, Map<String,String> assets) throws Exception {
        this.owner=owner;this.broker=broker;
        broker.refresh();
        task=new ServicePreparation(owner,new EmployeeAgentPreparationAccess(broker.openSession()),()->new ServicePreparation.Mapping(
            broker.authority().binding().authorityRef(),broker.customers()),(goal,ids,context)->{
                try { return explanation.selectFocus(goal,ids,context); }
                finally { broker.refresh(); }
            },reports,assets);
    }
    private void authorize(String caller) throws Exception {
        if(!owner.equals(caller)) throw new SecurityException("REPORT_OWNER_REJECTED");
        broker.refresh();
    }
    public List<String> customers(String caller) throws Exception { authorize(caller);return task.customers(caller); }
    public long select(String caller,String customer,String goal) throws Exception {
        authorize(caller);return task.select(caller,customer,goal);
    }
    public ObjectNode prepare(String caller,long generation) throws Exception {
        authorize(caller);return task.prepare(caller,generation);
    }
    public ObjectNode read(String caller,String reportId) throws Exception {
        authorize(caller);return task.read(caller,reportId);
    }
    public ObjectNode review(String caller,String reportId,int revision,String opinion) throws Exception {
        authorize(caller);return task.review(caller,reportId,revision,opinion);
    }
    @Override public void close() { task.close();broker.close(); }
}
