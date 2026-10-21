package io.github.bitlhk.finance.harness.core;

import java.util.Map;

/** Safe conversational boundary diagnostics; raw causes are never part of the public projection. */
public final class ConversationFailure extends RuntimeException {
    private static final Map<String,String> STAGES = Map.of(
            "CONVERSATION_AUTHORITY_UNAVAILABLE", "BUSINESS_AUTHORITY",
            "CONVERSATION_MODEL_STREAM_FAILED", "MODEL_STREAM",
            "CONVERSATION_MODEL_CLEANUP_FAILED", "MODEL_CLEANUP");
    private final String stage;
    public ConversationFailure(String code, String stage, Throwable cause) {
        super(code, cause);
        if (!stage.equals(STAGES.get(code))) throw new IllegalArgumentException("INVALID_CONVERSATION_FAILURE");
        this.stage=stage;
    }
    public String stage() { return stage; }
}
