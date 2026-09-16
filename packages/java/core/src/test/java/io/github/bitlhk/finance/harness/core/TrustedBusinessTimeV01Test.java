package io.github.bitlhk.finance.harness.core;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TrustedBusinessTimeV01Test {
    @Test void retainsHostDateZoneAndSourceWithoutAuthorizationMeaning() {
        var value = new TrustedBusinessTimeV01(LocalDate.parse("2026-09-15"),
                ZoneId.of("Asia/Shanghai"), "host-business-clock");
        assertEquals("2026-09-15", value.businessDate().toString());
        assertEquals("Asia/Shanghai", value.timeZone().getId());
        assertEquals("host-business-clock", value.source());
    }

    @Test void rejectsMissingSource() {
        assertThrows(IllegalArgumentException.class, () -> new TrustedBusinessTimeV01(
                LocalDate.parse("2026-09-15"), ZoneId.of("Asia/Shanghai"), " "));
    }
}
