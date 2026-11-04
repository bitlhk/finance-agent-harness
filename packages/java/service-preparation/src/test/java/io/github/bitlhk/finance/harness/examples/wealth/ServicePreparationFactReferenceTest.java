package io.github.bitlhk.finance.harness.examples.wealth;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class ServicePreparationFactReferenceTest {
    @Test void normalizesOnlyTheKnownProductMissingInputsAlias() {
        var allowed=List.of("/productMissingInputs/0");
        assertEquals(allowed,ServicePreparation.normalizeFactRefs(
                List.of("/productReview/productMissingInputs/0"),allowed));
        assertEquals(List.of("/productReview/unknown/0"),ServicePreparation.normalizeFactRefs(
                List.of("/productReview/unknown/0"),allowed));
    }
}
