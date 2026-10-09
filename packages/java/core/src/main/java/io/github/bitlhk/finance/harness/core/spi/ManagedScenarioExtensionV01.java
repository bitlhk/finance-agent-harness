package io.github.bitlhk.finance.harness.core.spi;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/** Optional managed-Host scenario extension. Builder owns lifecycle and identity; extensions own domain actions. */
public interface ManagedScenarioExtensionV01 {
    String id();
    Set<String> actions();
    Session open(Context context) throws Exception;

    record Context(Path artifactRoot, Path privateStateRoot, String buildId,
                   String instanceId, String ownerRef) {}

    interface Session extends AutoCloseable {
        String sessionId();
        Map<String,Object> bootstrap() throws Exception;
        Map<String,Object> call(String action, JsonNode body) throws Exception;
        Map<String,Object> status() throws Exception;
        @Override void close();
    }
}
