package io.github.bitlhk.finance.harness.quotation;

import java.nio.file.*;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QuoteFactsV01Test {
    private String sample() throws Exception {
        return HarnessJson.mapper().readTree(Files.readString(Path.of("../../../conformance/fixtures/quotation/v0.1/cases.v1.json")))
            .get("cases").get(0).get("payload").toString();
    }
    @Test void rejectsDuplicateTrailingMissingAndCoercedFields() throws Exception {
        String value = sample();
        assertThrows(IllegalArgumentException.class, () -> QuotationJson.read(value.replace("\"priceFactorBps\":9000", "\"priceFactorBps\":9000.0")));
        assertThrows(IllegalArgumentException.class, () -> QuotationJson.read(value.replace("\"priceFactorBps\":9000", "\"priceFactorBps\":\"9000\"")));
        assertThrows(IllegalArgumentException.class, () -> QuotationJson.read(value.replace("\"priceFactorBps\":9000,", "")));
        assertThrows(IllegalArgumentException.class, () -> QuotationJson.read(value.replace("\"state\":\"AVAILABLE\"", "\"state\":\"EXPIRED\",\"state\":\"AVAILABLE\"")));
        assertThrows(IllegalArgumentException.class, () -> QuotationJson.read(value + "{}"));
        assertThrows(IllegalArgumentException.class, () -> QuotationJson.read(value.replace("\"priceFactorBps\":9000", "\"priceFactorBps\":null")));
    }
    @Test void valuesAreImmutableAndCoreNullPolicyIsUntouched() throws Exception {
        var facts = QuotationJson.read(sample());
        assertThrows(UnsupportedOperationException.class, () -> facts.quote().claimCodes().add("GUARANTEED"));
        assertThrows(UnsupportedOperationException.class, () -> facts.quote().conditions().clear());
        var unavailable = new QuoteFactsV01(QuoteFactsV01.VERSION, facts.subjectRef(), facts.productRef(), "CNY",
            QuoteFactsV01.State.UNAVAILABLE, null, java.util.List.of("SOURCE_UNAVAILABLE"), facts.source());
        String wire = QuotationJson.mapper().writeValueAsString(unavailable);
        assertTrue(wire.contains("\"quote\":null"));
        assertEquals(unavailable, QuotationJson.read(wire));
        assertFalse(HarnessJson.mapper().writeValueAsString(unavailable).contains("\"quote\":null"));
    }
}
