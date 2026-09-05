package io.github.bitlhk.finance.harness.wealth;

import java.util.List;

/** Shared consumer: no EA/mobile field knowledge, no recommendations or suitability decisions. */
public final class WealthFactsConsumer {
    private WealthFactsConsumer() { }
    public record Description(String taskReadiness, List<String> assetStatements, String riskStatement,
                              List<WealthFactsV1.Gap> gaps, String suitability, String limitation) { }
    public static Description describe(WealthFactsV1 facts) {
        List<String> assets = facts.assets().amounts().stream().map(item ->
                item.scope() + ": " + item.money().value() + " " + item.money().currency()
                        + " (valuation=" + (item.valuationAt() == null ? "not established" : item.valuationAt())
                        + ", state=" + facts.assets().availability() + ")").toList();
        String risk = facts.risk().availability().name() + "; level=" + value(facts.risk().level())
                + "; scheme=" + value(facts.risk().scheme()) + "; version=" + value(facts.risk().assessmentVersion())
                + "; expires=" + value(facts.risk().expiresOn());
        boolean complete = facts.gaps().isEmpty()
                && facts.assets().availability() == WealthFactsV1.Availability.AVAILABLE
                && !facts.assets().amounts().isEmpty()
                && facts.assets().amounts().stream().allMatch(item -> item.valuationAt() != null
                        && !"SOURCE_REPORTED_UNSPECIFIED".equals(item.scope()))
                && facts.risk().availability() == WealthFactsV1.Availability.AVAILABLE
                && facts.risk().scheme() != null && facts.risk().assessmentVersion() != null
                && facts.risk().expiresOn() != null
                && (facts.risk().expiresOn().length() != 10 || facts.risk().dateZone() != null);
        return new Description(complete ? "COMPLETE_FOR_DESCRIPTION" : "PARTIAL_FOR_DESCRIPTION",
                assets, risk, facts.gaps(), "NOT_EVALUATED",
                "Source-reported assets only; not household wealth, authorization, suitability, or an investment recommendation.");
    }
    private static String value(String text) { return text == null ? "not established" : text; }
}
