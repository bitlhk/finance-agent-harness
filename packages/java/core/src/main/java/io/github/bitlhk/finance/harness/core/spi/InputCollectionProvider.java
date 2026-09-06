package io.github.bitlhk.finance.harness.core.spi;

import io.github.bitlhk.finance.harness.core.CapabilityRequestV1;
import io.github.bitlhk.finance.harness.core.CapabilityResultV1;
import java.util.Set;

/**
 * Optional adapter proof: returned fields are still missing and no bank task/write was attempted.
 * Missing means absent, null or a blank string; a listed nonempty value is never replaceable.
 * This is trusted adapter code, not a field read from model or upstream response metadata.
 */
public interface InputCollectionProvider extends FinanceProvider {
    Set<String> collectableInputs(CapabilityRequestV1 request, CapabilityResultV1 result);
}
