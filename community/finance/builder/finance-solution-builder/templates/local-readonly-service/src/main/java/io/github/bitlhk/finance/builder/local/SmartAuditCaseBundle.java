package io.github.bitlhk.finance.builder.local;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import java.io.IOException;
import java.util.*;

/**
 * Host-owned contract for a complete personal-loan review batch.
 *
 * <p>This recognizes only the browser's bounded demonstration bundle. It does
 * not interpret material contents as commands and it never produces a credit
 * decision. The caller uses the returned, schema-checked material projection
 * to invoke already-installed governed capabilities.</p>
 */
final class SmartAuditCaseBundle {
    static final List<String> PERSONAL_CAPABILITIES = List.of(
            "smartaudit.personal.borrower-qualification.query",
            "smartaudit.personal.income-verification.query",
            "smartaudit.personal.asset-liability.query",
            "smartaudit.personal.repayment-capacity.query");

    private static final String MARKER = "SMART_AUDIT_CASE_BUNDLE_V1";
    private static final Set<String> REQUIRED_CATEGORIES = Set.of(
            "身份证", "户口本", "结婚证", "贷款申请表", "收入证明", "流水明细",
            "个人所得税完税证明", "社会保险参保证明", "不动产权证书", "不动产信息查询结果", "贷款结清证明");
    private static final int MAX_MATERIAL_CONTENT = 3_000;
    private static final Map<String, Set<String>> MATERIALS_BY_CAPABILITY = Map.of(
            PERSONAL_CAPABILITIES.get(0), Set.of("身份证", "户口本", "结婚证", "贷款申请表"),
            PERSONAL_CAPABILITIES.get(1), Set.of("收入证明", "流水明细", "个人所得税完税证明", "社会保险参保证明"),
            PERSONAL_CAPABILITIES.get(2), Set.of("不动产权证书", "不动产信息查询结果", "贷款结清证明"),
            PERSONAL_CAPABILITIES.get(3), Set.of("个人所得税完税证明", "流水明细", "贷款申请表"));
    /**
     * The uploaded bundle deliberately uses the terms shown to a loan officer.
     * The governed provider accepts its immutable interface vocabulary instead.
     * This is a closed, host-owned adapter: it does not infer a category from
     * content and rejects every category outside {@link #REQUIRED_CATEGORIES}.
     */
    private static final Map<String, String> PROVIDER_CATEGORY_BY_DISPLAY_CATEGORY = Map.of(
            "流水明细", "流水",
            "个人所得税完税证明", "个税",
            "社会保险参保证明", "社保",
            "不动产权证书", "不动产权证",
            "不动产信息查询结果", "产权查询");

    private SmartAuditCaseBundle() { }

    record Bundle(List<Map<String, Object>> materials, Map<String, Object> arguments, List<String> capabilityIds) {
        Bundle {
            materials = List.copyOf(materials);
            arguments = Map.copyOf(arguments);
            capabilityIds = List.copyOf(capabilityIds);
        }

        Map<String, Object> argumentsFor(String capabilityId) {
            Set<String> allowed = MATERIALS_BY_CAPABILITY.get(capabilityId);
            if (allowed == null) throw new IllegalArgumentException("SMART_AUDIT_CAPABILITY_UNSUPPORTED");
            List<Map<String, Object>> selected = materials.stream()
                    .filter(material -> allowed.contains(material.get("category")))
                    .map(SmartAuditCaseBundle::providerMaterial)
                    .toList();
            if (selected.size() != allowed.size()) throw new IllegalStateException("SMART_AUDIT_MATERIAL_SUBSET_INCOMPLETE");
            return Map.of("materials", selected);
        }
    }

    static Bundle from(String query) throws IOException {
        if (query == null || !query.startsWith(MARKER)) return null;
        int objectStart = query.indexOf('{', MARKER.length());
        if (objectStart < 0) throw new IllegalArgumentException("SMART_AUDIT_BUNDLE_JSON_MISSING");
        JsonNode bundle = HarnessJson.mapper().readTree(query.substring(objectStart));
        if (!bundle.isObject() || !"smart-audit-demo-materials.v1".equals(bundle.path("schemaVersion").asText())
                || !"personal-loan-audit".equals(bundle.path("scenario").asText()) || !bundle.path("materials").isArray())
            throw new IllegalArgumentException("SMART_AUDIT_BUNDLE_INVALID");

        Set<String> received = new HashSet<>();
        List<Map<String, Object>> materials = new ArrayList<>();
        int characters = 0;
        for (JsonNode material : bundle.path("materials")) {
            if (!material.isObject() || material.size() != 3
                    || !material.path("category").isTextual() || !material.path("content").isTextual()
                    || !material.path("declaredFormat").isTextual())
                throw new IllegalArgumentException("SMART_AUDIT_MATERIAL_INVALID");
            String category = material.path("category").asText();
            String content = material.path("content").asText();
            String format = material.path("declaredFormat").asText();
            if (!REQUIRED_CATEGORIES.contains(category) || !received.add(category) || content.isBlank()
                    || !Set.of("TEXT", "JSON").contains(format))
                throw new IllegalArgumentException("SMART_AUDIT_MATERIAL_INVALID");
            characters += content.length();
            if (characters > MAX_MATERIAL_CONTENT) throw new IllegalArgumentException("SMART_AUDIT_MATERIAL_TOO_LARGE");
            materials.add(Map.of(
                    "category", category,
                    "providerCategory", PROVIDER_CATEGORY_BY_DISPLAY_CATEGORY.getOrDefault(category, category),
                    "content", content,
                    "declaredFormat", format));
        }
        if (!received.equals(REQUIRED_CATEGORIES)) throw new IllegalArgumentException("SMART_AUDIT_MATERIAL_INCOMPLETE");
        return new Bundle(materials, Map.of("materials", List.copyOf(materials)), PERSONAL_CAPABILITIES);
    }

    private static Map<String, Object> providerMaterial(Map<String, Object> material) {
        return Map.of(
                "category", material.get("providerCategory"),
                "content", material.get("content"),
                "declaredFormat", material.get("declaredFormat"));
    }
}
