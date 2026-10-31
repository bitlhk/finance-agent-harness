package io.github.bitlhk.finance.harness.core.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.bitlhk.finance.harness.core.observation.ObservationRun;
import io.github.bitlhk.finance.harness.core.observation.ObservationCapabilitiesV01;
import java.util.List;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** Optional managed-Host scenario extension. Builder owns lifecycle and identity; extensions own domain actions. */
public interface ManagedScenarioExtensionV01 {
    String id();
    Set<String> actions();
    Session open(Context context) throws Exception;
    /** Locked-plan metadata construction only; must not acquire authority or contact a source. */
    default Map<String, FinanceProvider> installationProviders(Path artifactRoot) throws Exception { return Map.of(); }

    record ModelConnection(String clientProvider, String apiKey, String apiBase, String modelName) {
        @Override public String toString() {
            return "ModelConnection[clientProvider=" + clientProvider + ", apiKey=[REDACTED], apiBase="
                    + apiBase + ", modelName=" + modelName + "]";
        }
    }

    record Context(Path artifactRoot, Path privateStateRoot, String buildId,
                   String instanceId, String ownerRef, ModelConnection model,
                   Supplier<ObservationRun> currentObservation,
                   List<ObservationCapabilitiesV01> trustedCollectors) {
        public Context {
            Objects.requireNonNull(currentObservation);
            trustedCollectors = List.copyOf(trustedCollectors);
        }

        public Context(Path artifactRoot, Path privateStateRoot, String buildId,
                       String instanceId, String ownerRef, ModelConnection model,
                       Supplier<ObservationRun> currentObservation) {
            this(artifactRoot, privateStateRoot, buildId, instanceId, ownerRef, model,
                 currentObservation, List.of());
        }

        public Context(Path artifactRoot, Path privateStateRoot, String buildId,
                       String instanceId, String ownerRef, ModelConnection model) {
            this(artifactRoot, privateStateRoot, buildId, instanceId, ownerRef, model, () -> null);
        }
    }

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
