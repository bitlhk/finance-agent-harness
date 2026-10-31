package io.github.bitlhk.finance.harness.providers.knowledge;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.assertEquals;

class KnowledgeUpstreamFailureTest {
    private static String classify(int status, String json) {
        return KnowledgeProvider.upstreamFailure(status, json.getBytes(StandardCharsets.UTF_8));
    }

    @ParameterizedTest
    @ValueSource(strings={"FORBIDDEN", "MANIFEST_MISMATCH", "RAGFLOW_BUSINESS_ERROR", "RETRIEVAL_INDEX_MISMATCH"})
    void retainsKnownRejectionWithoutChangingOutcome(String code) {
        assertEquals(code, classify(403, "{\"code\":403,\"error\":\"" + code + "\"}"));
    }

    @ParameterizedTest
    @ValueSource(strings={"not json", "null", "[]", "{\"code\":403,\"error\":\"secret-value\"}",
        "{\"code\":403,\"error\":\"FORBIDDEN\",\"body\":\"private-data\"}",
        "{\"code\":200,\"error\":\"FORBIDDEN\"}", "{\"code\":403.0,\"error\":\"FORBIDDEN\"}"})
    void neverDisclosesUnknownMalformedOrExtendedErrors(String json) {
        assertEquals("UPSTREAM_REJECTED", classify(403, json));
    }

    @Test void enforcesStatusAndSmallBodyLimit() {
        assertEquals("UPSTREAM_REJECTED", classify(503, "{\"code\":403,\"error\":\"FORBIDDEN\"}"));
        assertEquals("UPSTREAM_REJECTED", classify(403, " ".repeat(4097)));
    }
}
