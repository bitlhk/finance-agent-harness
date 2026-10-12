package io.github.bitlhk.finance.builder.local;

import io.github.bitlhk.finance.harness.core.HarnessJson;
import java.math.BigDecimal;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class DisplayProjectionTest {
    String card(String amount) throws Exception {
        Object result=HarnessJson.mapper().readTree("{\"data\":{\"capabilityId\":\"account.balance.query\",\"resultStatus\":\"COMPLETED\",\"secret\":\"hidden\",\"accounts\":[{\"accountId\":\"internal-a\",\"currency\":\"CNY\",\"availableBalance\":"+amount+"}]}}");
        return HarnessJson.mapper().writeValueAsString(DisplayProjection.cards(List.of(result),Set.of("account.balance.query")));
    }
    @Test public void exactAmountAndNoPrivateIdentity() throws Exception {
        String result=card("\"90071992547409.91\"");
        assertTrue(result.contains("90071992547409.91")); assertFalse(result.contains("internal-a")); assertFalse(result.contains("hidden"));
    }
    @Test public void zeroAndMissingDiffer() throws Exception {
        assertTrue(card("0").contains("AVAILABLE")); assertTrue(card("null").contains("MISSING")); assertFalse(card("null").contains("\"amount\""));
    }
    @Test public void uninstalledCapabilitiesHaveNoCard() {
        assertTrue(DisplayProjection.cards(List.of(Map.of("data", Map.of("capabilityId","transfer.execute"))), Set.of("account.balance.query")).isEmpty());
    }
    @Test public void providerDecimalValuesSurviveTreeNormalization() {
        for (String amount : List.of("50000.00", "8650.00", "920.00", "128650.32", "0.00", "90071992547409.91")) {
            var tool = Map.of("data", Map.of("capabilityId", "account.balance.query", "resultStatus", "COMPLETED",
                    "accounts", List.of(Map.of("currency", "CNY", "availableBalance", new BigDecimal(amount)))));
            var cards = HarnessJson.mapper().valueToTree(DisplayProjection.cards(List.of(tool), Set.of("account.balance.query")));
            var row = cards.get(0).path("rows").get(0);
            assertEquals(amount, "AVAILABLE", row.path("availability").asText());
            assertEquals(0, new BigDecimal(amount).compareTo(new BigDecimal(row.path("amount").asText())));
            assertFalse(row.path("amount").asText().contains("E"));
        }
    }
    @Test public void invalidAmountsAreNotFilledFromOtherFields() throws Exception {
        for (String value : List.of("null", "true", "{}", "\"NaN\"", "\"Infinity\"", "\"1,000\"", "\"\"", "\"1e999999999\"")) {
            assertTrue(value, card(value).contains("MISSING"));
            assertFalse(value, card(value).contains("\"amount\""));
        }
    }
}
