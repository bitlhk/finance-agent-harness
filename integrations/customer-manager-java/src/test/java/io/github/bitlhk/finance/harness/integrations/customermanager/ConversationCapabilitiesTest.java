package io.github.bitlhk.finance.harness.integrations.customermanager;

import io.github.bitlhk.finance.harness.core.HarnessJson;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ConversationCapabilitiesTest {
    private static ConversationCapabilities catalog(String ids) throws Exception {
        return ConversationCapabilities.fromLock(HarnessJson.mapper().readTree(
                "{\"schemaVersion\":\"local-agent-service-lock.v0.1\",\"capabilities\":" + ids + "}"));
    }
    @Test void helpAndExecutionUseTheSameInstalledCapabilities() throws Exception {
        var basic = catalog("[\"wealth.previsit-context.query\"]");
        assertTrue(basic.description().contains("回访"));
        assertFalse(basic.description().contains("筛选待复核候选"));
        assertThrows(SecurityException.class, () -> basic.require(ConversationIntent.Kind.PRODUCT_REVIEW));
        var full = catalog("[\"wealth.previsit-context.query\",\"wealth.allocation-context.query\"]");
        assertTrue(full.description().contains("筛选待复核候选"));
        assertDoesNotThrow(() -> full.require(ConversationIntent.Kind.PRODUCT_REVIEW));
    }
    @Test void unknownOrIncompleteCompositionDoesNotAcquireAnExecutableAction() throws Exception {
        assertTrue(catalog("[\"unknown.query\"]").actions().isEmpty());
        assertTrue(catalog("[\"wealth.allocation-context.query\"]").actions().isEmpty());
        assertTrue(catalog("[]").actions().isEmpty());
    }
    @Test void malformedAndDuplicateDeclarationsFailClosed() {
        for (String ids : new String[]{"null", "{}", "[null]", "[42]", "[\"\"]", "[\"x\",\"x\"]"})
            assertThrows(SecurityException.class, () -> catalog(ids));
        assertThrows(SecurityException.class, () -> ConversationCapabilities.fromLock(
                HarnessJson.mapper().readTree("{\"schemaVersion\":\"unknown\",\"capabilities\":[]}")));
    }
}
