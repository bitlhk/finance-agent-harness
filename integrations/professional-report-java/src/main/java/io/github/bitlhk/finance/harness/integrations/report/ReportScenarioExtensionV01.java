package io.github.bitlhk.finance.harness.integrations.report;
import io.github.bitlhk.finance.harness.core.spi.ManagedScenarioExtensionV01;
import java.util.Set;

/** Opt-in report domain over the existing managed Host lifecycle. */
public final class ReportScenarioExtensionV01 implements ManagedScenarioExtensionV01 {
    public static final String ID="professional-report-readonly.v1";
    @Override public String id(){return ID;}
    @Override public Set<String> actions(){return ReportActions.ACTIONS;}
    @Override public Session open(Context c) throws Exception{return new ReportSession(c);}
}
