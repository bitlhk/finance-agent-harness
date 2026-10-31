package io.github.bitlhk.finance.harness.examples.wealth;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.bitlhk.finance.harness.core.CapabilityResultV1;

/** Optional institution evidence for this business report, separate from customer facts. */
public interface PreparationKnowledge extends AutoCloseable {
    // No customer, user text, CRM payload, or bank session crosses this interface.
    CapabilityResultV1 query(String requestId, String traceId, boolean productReview);
    void current();
    void authorizeSaved(JsonNode report);
    @Override void close();
}
