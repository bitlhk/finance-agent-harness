package io.github.bitlhk.finance.harness.quotation;

import io.github.bitlhk.finance.harness.core.HarnessJson;
import java.nio.file.*;
import java.time.Instant;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class QuoteDisclosureV01Test {
    @TestFactory Stream<DynamicTest> frozenIndependentCases() throws Exception {
        var manifest = HarnessJson.mapper().readTree(Files.readString(Path.of("../../../conformance/fixtures/quotation/v0.1/cases.v1.json")));
        return StreamSupport.stream(manifest.get("cases").spliterator(), false).map(row ->
            DynamicTest.dynamicTest(row.get("id").asText() + " " + row.get("label").asText(), () -> {
                if (!row.get("schemaValid").asBoolean()) {
                    assertThrows(IllegalArgumentException.class, () -> QuotationJson.read(row.get("payload").toString()));
                } else {
                    var facts = QuotationJson.read(row.get("payload").toString());
                    var binding = QuotationJson.mapper().treeToValue(row.get("binding"), QuoteDisclosureV01.ExpectedBinding.class);
                    var result = QuoteDisclosureV01.validateForDisclosure(facts, binding, Instant.parse(row.get("now").asText()));
                    assertEquals(row.get("disclosureReason").asText(), result.reason());
                    assertEquals("ALLOWED".equals(result.reason()), result.projection() != null);
                }
            }));
    }
}
