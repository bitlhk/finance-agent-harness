package io.github.bitlhk.finance.builder.local;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import static io.github.bitlhk.finance.builder.local.DisplayFields.*;

/** Business-specific, allowlisted projection of the existing result contract. */
final class BalanceDisplay {
    private BalanceDisplay() { }
    static List<Object> project(JsonNode data) {
        var cards = new ArrayList<Object>();
        var source = pick(data, "sourceSystem", "asOf");
        String capability = data.path("capabilityId").asText();
        var rows = new ArrayList<Object>();
        for (JsonNode account : data.path("accounts")) {
            var row = pick(account, "currency", "accountType");
            JsonNode amount = account.path("availableBalance");
            String number = decimalText(amount);
            boolean valid = number.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?");
            row.put("availability", valid && account.path("currency").asText().matches("[A-Z]{3}") ? "AVAILABLE" : "MISSING");
            if ("AVAILABLE".equals(row.get("availability"))) row.put("amount", number);
            // An internal accountId is not a card number. Never fabricate its 'last four'.
            if (account.path("maskedAccountNumber").isTextual()) row.put("accountLabel", account.path("maskedAccountNumber").asText());
            rows.add(row);
        }
        cards.add(Map.of("type", "balance", "version", "0.1", "rows", rows, "source", source,
                "scope", "AUTHORIZED_ACCOUNTS_RETURNED", "availability", rows.isEmpty() ? "MISSING" : "REPORTED"));

        return cards;
    }
}
