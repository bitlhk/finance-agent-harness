package io.github.bitlhk.finance.harness.integrations.marketrisk;

import io.github.bitlhk.finance.harness.core.spi.ManagedScenarioExtensionV01;
import java.util.Set;

/** Opt-in managed local Host extension; no authority is derived from browser inputs. */
public final class MarketRiskScenarioExtensionV01 implements ManagedScenarioExtensionV01 {
    public static final String ID="market-risk-capital-readonly.v1";
    @Override public String id() { return ID; }
    @Override public Set<String> actions() { return Set.of("chat"); }
    @Override public Session open(Context context) throws Exception { return new MarketRiskSession(context); }
}
