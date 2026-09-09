package io.github.bitlhk.finance.harness.cashflow;

import io.github.bitlhk.finance.harness.core.HarnessJson;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.bitlhk.finance.harness.cashflow.CashflowFactsV1.*;

class CashflowContractTest {
    @Test void sharedSchemaFixturesAreAlsoConsumedByJava() throws Exception {
        var root = Path.of("../../../conformance/fixtures/cashflow/v0.1");
        int count = 0;
        try (var paths = Files.walk(root)) {
            for (var file : paths.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                String body = Files.readString(file);
                if (file.getParent().getFileName().toString().equals("invalid"))
                    assertThrows(Exception.class, () -> HarnessJson.mapper().readValue(body, CashflowFactsV1.class), file.toString());
                else assertNotNull(CashflowConsumer.describe(HarnessJson.mapper().readValue(body, CashflowFactsV1.class)));
                count++;
            }
        }
        assertEquals(12, count, "All shared positive and negative cases must execute");
    }
    @Test void decimalsStayExactAndArithmeticIsAnAdditionalJavaGate() {
        assertEquals("90071992547409.91", new Totals("90071992547409.91", "0.00", "90071992547409.91").income());
        assertThrows(IllegalArgumentException.class, () -> new Totals("1.00", "0.00", "2.00"));
        assertThrows(IllegalArgumentException.class, () -> new Totals("-1.00", "0.00", "-1.00"));
        assertThrows(Exception.class, () -> HarnessJson.mapper().readValue("{\"income\":10.12,\"expense\":\"0.00\",\"netCashflow\":\"10.12\"}", Totals.class));
    }
    @Test void publicConsumerNeverTreatsMissingCategoryOrTrendAsComplete() throws Exception {
        var original = HarnessJson.mapper().readValue(Files.readString(Path.of("../../../conformance/fixtures/cashflow/v0.1/valid/analysis.json")), CashflowFactsV1.class);
        assertThrows(IllegalArgumentException.class, () -> copy(original, List.of(), List.of(), List.of()));
        var partial = copy(original, List.of(), List.of(), List.of("CATEGORIES_NOT_RETURNED", "TREND_NOT_RETURNED"));
        assertEquals(CashflowConsumer.Completeness.PARTIAL, CashflowConsumer.describe(partial).completeness());
        var reversed = new ArrayList<>(original.trend()); Collections.reverse(reversed);
        assertThrows(IllegalArgumentException.class, () -> copy(original, original.categories(), reversed, original.gaps()));
    }
    @Test void futureSourcesCannotClaimObservedFacts() {
        var now = Instant.parse("2026-09-01T00:00:00Z");
        assertThrows(IllegalArgumentException.class, () -> new Source("bank", "a", "bank", "v1", now.plusSeconds(1), now, "p", now));
    }
    private static CashflowFactsV1 copy(CashflowFactsV1 source, List<Category> categories, List<TrendPoint> trend, List<String> gaps) {
        return new CashflowFactsV1(VERSION, source.section(), source.period(), source.currency(), source.coverage(),
                source.totals(), categories, trend, source.source(), gaps);
    }
}
