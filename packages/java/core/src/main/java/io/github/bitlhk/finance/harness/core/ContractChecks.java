package io.github.bitlhk.finance.harness.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ContractChecks {
    private ContractChecks() {
    }

    static String requiredText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    static String optionalText(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    static <K, V> Map<K, V> immutableMap(Map<K, V> value) {
        return value == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }

    static <T> List<T> immutableList(List<T> value) {
        return value == null ? List.of() : List.copyOf(value);
    }

    static <T> Set<T> immutableSet(Set<T> value) {
        return value == null ? Set.of() : Set.copyOf(value);
    }

    static void schemaVersion(String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException("schemaVersion must be " + expected);
        }
    }
}
