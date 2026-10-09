package io.github.bitlhk.finance.harness.providers.curatedmcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CuratedOutputContractTest {
    @Test void everyRequiredFieldRejectsMissingNullAndWrongTypeRecursively() {
        int checked = 0;
        for (var tool : CuratedTools.ALL) {
            String ref = tool.selector().equals("customer_id") ? "C005"
                    : tool.selector().equals("product_id") ? "P009" : tool.defaultRef();
            var args = tool.selector().isEmpty() ? Map.<String, Object>of() : Map.<String, Object>of(tool.selector(), ref);
            ObjectNode data = HarnessJson.mapper().valueToTree(CuratedMcpProviderTest.fixture(tool, HarnessJson.mapper().valueToTree(args), false));
            assertDoesNotThrow(() -> CuratedTools.validateResult(tool, args, data), tool.name());
            var pointers = new ArrayList<String>();collect(data, "", pointers);
            for (String pointer : pointers) {
                // The source explicitly permits the extra disclosure for other products too.
                if (pointer.equals("/product/riskDisclosure")) continue;
                for (int mode = 0; mode < 3; mode++) {
                    ObjectNode changed = data.deepCopy();
                    int slash = pointer.lastIndexOf('/');String field = pointer.substring(slash + 1);
                    JsonNode parent = changed.at(pointer.substring(0, slash));
                    JsonNode previous = changed.at(pointer);
                    JsonNode bad = mode == 1 ? NullNode.instance : previous.isTextual() ? IntNode.valueOf(7) : TextNode.valueOf("wrong-type");
                    if (mode == 1 && previous.isNull()) continue;
                    if (parent instanceof ObjectNode object) {
                        if (mode == 0) object.remove(field);else object.set(field, bad);
                    } else if (parent instanceof ArrayNode array) {
                        if (mode == 0) continue;
                        array.set(Integer.parseInt(field), bad);
                    }
                    assertThrows(RuntimeException.class, () -> CuratedTools.validateResult(tool, args, changed), tool.name() + pointer + ":" + mode);
                    checked++;
                }
            }
        }
        assertTrue(checked > 600, "All sixteen nested output structures must be exercised");
    }
    @Test void alternateProfilesDegradedAndUnknownRemainValidButCannotClaimReady() {
        for (String id : List.of("C006", "I001")) {
            var profile = CuratedTools.named("get_customer_profile");var args = Map.<String, Object>of("customer_id", id);
            ObjectNode data = HarnessJson.mapper().valueToTree(CuratedMcpProviderTest.fixture(profile, HarnessJson.mapper().valueToTree(args), false));
            assertDoesNotThrow(() -> CuratedTools.validateResult(profile, args, data));
            ((ObjectNode) data.get("profile")).remove(id.equals("I001") ? "vehicle" : "maturityAmount");
            assertThrows(IllegalArgumentException.class, () -> CuratedTools.validateResult(profile, args, data));
        }
        var tool = CuratedTools.named("prepare_wealth_allocation_context");
        for (String id : List.of("C005", "C006")) {
            var args = Map.<String, Object>of("customer_id", id);
            ObjectNode data = HarnessJson.mapper().valueToTree(CuratedMcpProviderTest.fixture(tool, HarnessJson.mapper().valueToTree(args), false));
            data.put("readiness", "DEGRADED");
            for (String field : List.of("policy", "currentPortfolio", "investableAmount", "liquidityFloor", "minimumHorizonYears", "calculationBasis", "excludedProducts")) data.remove(field);
            assertDoesNotThrow(() -> CuratedTools.validateResult(tool, args, data));
            data.put("readiness", "READY");
            assertThrows(IllegalArgumentException.class, () -> CuratedTools.validateResult(tool, args, data));
        }
        var risk = CuratedTools.named("fraud_transfer_risk_assess");
        var args = Map.<String, Object>of("reference", "bene-unknown");
        ObjectNode data = HarnessJson.mapper().valueToTree(CuratedMcpProviderTest.fixture(risk, HarnessJson.mapper().valueToTree(args), false));
        assertDoesNotThrow(() -> CuratedTools.validateResult(risk, args, data));
        data.put("status", "READY");data.put("decision", "NO_KNOWN_HIT");
        assertThrows(IllegalArgumentException.class, () -> CuratedTools.validateResult(risk, args, data));
    }
    @Test void jsonRejectsDuplicateKeysTrailingValuesAndDeepNesting() {
        for (String text : List.of("{\"synthetic\":false,\"synthetic\":true}", "{} {}", "[".repeat(65) + "0" + "]".repeat(65)))
            assertThrows(java.io.IOException.class, () -> CuratedTools.responseMapper().readTree(text));
    }
    @Test void secondaryResourceIdentitiesCannotContradictTheAuthorizedReference() {
        for (String name : List.of("get_product_detail", "account_balance_query", "get_post_loan_risk_snapshot")) {
            var tool = CuratedTools.named(name);var args = Map.<String, Object>of("reference", tool.defaultRef());
            ObjectNode data = HarnessJson.mapper().valueToTree(CuratedMcpProviderTest.fixture(tool, HarnessJson.mapper().valueToTree(args), false));
            if (name.equals("get_product_detail")) data.put("productId", "other");
            else if (name.equals("account_balance_query")) data.put("accountRef", "other");
            else ((ObjectNode) data.get("data")).put("enterpriseId", "other");
            assertThrows(SecurityException.class, () -> CuratedTools.validateResult(tool, args, data));
        }
    }
    private static void collect(JsonNode node, String pointer, List<String> pointers) {
        if (node.isObject()) node.fields().forEachRemaining(field -> {
            String child = pointer + "/" + field.getKey();pointers.add(child);collect(field.getValue(), child, pointers);
        });
        else if (node.isArray()) for (int i = 0; i < node.size(); i++) {
            String child = pointer + "/" + i;pointers.add(child);collect(node.get(i), child, pointers);
        }
    }
}
