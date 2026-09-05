package io.github.bitlhk.finance.harness.wealth;

import java.time.ZoneId;

/** Host-owned mapping basis. Null metadata means unknown, never an inferred business fact. */
public record WealthSourceSemantics(String namespace, String contract, String basisReference,
                                   String assetScope, String currency, String riskScheme,
                                   String riskVersion, ZoneId dateZone, boolean dataTimeIsValuationTime) {
    public WealthSourceSemantics {
        WealthFactsV1.text(namespace); WealthFactsV1.text(contract); WealthFactsV1.text(basisReference);
        if (currency != null && !currency.matches("[A-Z]{3}")) throw new IllegalArgumentException("Invalid currency");
        for (String value : new String[]{assetScope, riskScheme, riskVersion}) if (value != null) WealthFactsV1.text(value);
    }
}
