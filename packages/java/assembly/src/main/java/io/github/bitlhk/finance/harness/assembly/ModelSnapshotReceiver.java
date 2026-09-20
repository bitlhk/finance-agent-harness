package io.github.bitlhk.finance.harness.assembly;

import java.util.Map;

/** Existing synchronous client diagnostic boundary; Host owns filtering, permissions and storage. */
@FunctionalInterface
public interface ModelSnapshotReceiver {
    void capture(String callId, String boundary, String state, Map<String, Object> value);
}
