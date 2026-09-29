package io.github.bitlhk.finance.harness.integrations.agentscope;

import io.github.bitlhk.finance.harness.assembly.ModelSnapshotReceiver;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Per-request opt-in. Capture permission is independent of the customer's financial grant. */
public record ModelDiagnostics(boolean enabled, BooleanSupplier captureAuthorized, ModelSnapshotReceiver receiver) {
    public ModelDiagnostics { Objects.requireNonNull(captureAuthorized); Objects.requireNonNull(receiver); }
    public static ModelDiagnostics disabled() { return new ModelDiagnostics(false, () -> false, (id, boundary, state, body) -> {}); }
    boolean capture(String id, String boundary, Map<String, Object> value) {
        try {
            if (!enabled || !captureAuthorized.getAsBoolean()) return true;
            byte[] bytes = HarnessJson.mapper().writeValueAsBytes(value);
            if (bytes.length > 48000) receiver.capture(id, boundary, "TRUNCATED", Map.of("reason", "BODY_SIZE_LIMIT"));
            else {
                Map<String, Object> frozen = HarnessJson.mapper().readValue(bytes, new com.fasterxml.jackson.core.type.TypeReference<>() {});
                receiver.capture(id, boundary, "CAPTURED", frozen);
            }
            return true;
        } catch (Exception failed) { return false; }
    }
}
