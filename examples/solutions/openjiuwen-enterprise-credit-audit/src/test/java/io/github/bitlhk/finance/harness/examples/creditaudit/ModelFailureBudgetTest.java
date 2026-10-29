package io.github.bitlhk.finance.harness.examples.creditaudit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelFailureBudgetTest {
    @Test
    void model_failure_finishes_before_the_host_request_budget() {
        assertEquals(0, EnterpriseCreditAuditApplication.MODEL_MAX_RETRIES);
        assertTrue(EnterpriseCreditAuditApplication.MODEL_TIMEOUT_SECONDS <= 90);
    }
}
