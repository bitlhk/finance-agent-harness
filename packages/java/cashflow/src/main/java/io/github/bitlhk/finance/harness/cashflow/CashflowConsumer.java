package io.github.bitlhk.finance.harness.cashflow;

import java.util.List;

/** The consumer only reads the common contract, never source-specific response fields. */
public final class CashflowConsumer {
    private CashflowConsumer() { }
    public enum Completeness { PARTIAL, COMPLETE_WITHIN_DECLARED_SCOPE }
    public record Description(Completeness completeness, String period, String currency,
            CashflowFactsV1.Coverage coverage, CashflowFactsV1.Totals totals, List<String> gaps) { }
    public static Description describe(CashflowFactsV1 facts) {
        boolean partial = !facts.totals().complete() || facts.source().asOf() == null
                || facts.gaps().stream().anyMatch(g -> g.endsWith("NOT_RETURNED") || g.endsWith("NOT_VERIFIED") || g.endsWith("CONFLICT"));
        return new Description(partial ? Completeness.PARTIAL : Completeness.COMPLETE_WITHIN_DECLARED_SCOPE,
                facts.period(), facts.currency(), facts.coverage(), facts.totals(), facts.gaps());
    }
    public static boolean comparable(CashflowFactsV1 a, CashflowFactsV1 b) {
        return describe(a).completeness() == Completeness.COMPLETE_WITHIN_DECLARED_SCOPE
                && describe(b).completeness() == Completeness.COMPLETE_WITHIN_DECLARED_SCOPE
                && a.source().system().equals(b.source().system()) && a.source().recordVersion().equals(b.source().recordVersion())
                && a.source().namespace().equals(b.source().namespace()) && a.source().subjectRef().equals(b.source().subjectRef())
                && a.period().equals(b.period()) && a.currency().equals(b.currency()) && a.coverage() == b.coverage()
                && a.source().asOf() != null && a.source().asOf().equals(b.source().asOf())
                && a.totals().complete() && b.totals().complete();
    }
}
