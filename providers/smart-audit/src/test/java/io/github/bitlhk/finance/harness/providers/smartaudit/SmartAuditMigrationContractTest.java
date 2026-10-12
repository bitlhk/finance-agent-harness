package io.github.bitlhk.finance.harness.providers.smartaudit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;

class SmartAuditMigrationContractTest {
    @Test
    void exposes_the_query_only_smart_audit_provider() {
        assertDoesNotThrow(() -> Class.forName(
                "io.github.bitlhk.finance.harness.providers.smartaudit.SmartAuditModelProvider"));
    }
}
