package io.github.bitlhk.finance.harness.integrations.material;
import io.github.bitlhk.finance.harness.core.spi.ManagedScenarioExtensionV01;
import java.util.Set;
/** Material profile for the existing independent managed Host. */
public final class MaterialProcessingExtensionV01 implements ManagedScenarioExtensionV01 {
    public static final String ID="financial-material-processing.v1";
    public String id(){return ID;}
    public Set<String> actions(){return Set.of("chat");}
    public Session open(Context context) throws Exception{return new MaterialProcessingSession(context);}
}
