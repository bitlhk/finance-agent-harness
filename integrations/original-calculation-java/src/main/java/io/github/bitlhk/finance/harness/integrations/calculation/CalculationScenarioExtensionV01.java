package io.github.bitlhk.finance.harness.integrations.calculation;

import io.github.bitlhk.finance.harness.core.spi.ManagedScenarioExtensionV01;
import java.util.Set;

/** Optional managed scenario; no new Core/SPI method is required. */
public final class CalculationScenarioExtensionV01 implements ManagedScenarioExtensionV01 {
    public static final String ID="original-calculation-readonly.v1";
    @Override public String id(){return ID;}
    @Override public Set<String> actions(){return Set.of("chat","calculate","result","cancel","status","history");}
    @Override public Session open(Context context) throws Exception{return new CalculationSession(context);}
}
