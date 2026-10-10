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

    record ModelConnection(String clientProvider, String apiKey, String apiBase, String modelName) {
        @Override public String toString() {
            return "ModelConnection[clientProvider=" + clientProvider + ", apiKey=[REDACTED], apiBase="
                    + apiBase + ", modelName=" + modelName + "]";
        }
    }

    record Context(Path artifactRoot, Path privateStateRoot, String buildId,
                   String instanceId, String ownerRef, ModelConnection model) {}

    interface Session extends AutoCloseable {
        @FunctionalInterface interface EventSink {
            void emit(Map<String,Object> event) throws Exception;
        }
        String sessionId();
        Map<String,Object> bootstrap() throws Exception;
        Map<String,Object> call(String action, JsonNode body) throws Exception;
        default Map<String,Object> callStreaming(String action, JsonNode body, EventSink sink) throws Exception {
            return call(action, body);
        }
        Map<String,Object> status() throws Exception;
        @Override void close();
    }
}
