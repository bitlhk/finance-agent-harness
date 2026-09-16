package io.github.bitlhk.finance.harness.core;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/** Host-supplied business date context. It carries no identity or authorization semantics. */
public record TrustedBusinessTimeV01(LocalDate businessDate, ZoneId timeZone, String source) {
    public TrustedBusinessTimeV01 {
        Objects.requireNonNull(businessDate, "businessDate");
        Objects.requireNonNull(timeZone, "timeZone");
        source = ContractChecks.requiredText(source, "source");
    }
}
