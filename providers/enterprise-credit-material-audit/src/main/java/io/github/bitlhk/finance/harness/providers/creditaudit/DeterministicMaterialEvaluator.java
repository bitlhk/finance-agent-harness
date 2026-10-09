package io.github.bitlhk.finance.harness.providers.creditaudit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class DeterministicMaterialEvaluator {
    static final String FINANCIAL = "enterprise.financial-statement.verify";
    static final String CROSS_MATERIAL = "enterprise.application-contract.verify";

    Evaluation evaluateFinancial(MaterialSet set, DemoRuleSet rules) {
        var statement = one(set, "FINANCIAL_STATEMENT");
        if (statement == null) return missing("financial-statement-equation", rules,
                List.of("FINANCIAL_STATEMENT"), emptyBasis("SINGLE_MATERIAL"));
        Map<String, Object> verificationBasis = singleMaterialBasis(statement);
        var missing = new ArrayList<String>();
        BigDecimal assets = amount(statement, "assets", missing);
        BigDecimal liabilities = amount(statement, "liabilities", missing);
        BigDecimal equity = amount(statement, "equity", missing);
        if (!missing.isEmpty()) return missing("financial-statement-equation", rules, missing, verificationBasis);
        if (blank(statement.currency()) || blank(statement.unit())) {
            return notComparable("financial-statement-equation", rules, "币种或计量单位缺失，无法可靠计算。",
                    evidence(statement, List.of("assets", "liabilities", "equity")), verificationBasis);
        }
        int scale = rules.amountScale();
        RoundingMode rounding = RoundingMode.valueOf(rules.roundingMode());
        assets = assets.setScale(scale, rounding);
        liabilities = liabilities.setScale(scale, rounding);
        equity = equity.setScale(scale, rounding);
        BigDecimal expected = liabilities.add(equity).setScale(scale, rounding);
        BigDecimal difference = assets.subtract(expected).setScale(scale, rounding);
        AuditStatus status = difference.compareTo(BigDecimal.ZERO.setScale(scale)) == 0
                ? AuditStatus.CONSISTENT : AuditStatus.INCONSISTENT;
        var values = new LinkedHashMap<String, Object>();
        values.put("actualAssets", assets.toPlainString());
        values.put("expectedAssets", expected.toPlainString());
        values.put("difference", difference.toPlainString());
        values.put("currency", statement.currency());
        values.put("unit", statement.unit());
        return new Evaluation(status, "financial-statement-equation", rules.ruleSetId(), rules.version(),
                status == AuditStatus.CONSISTENT
                        ? "当前字段在演示规则下勾稽一致；不代表财报真实。"
                        : "资产与负债加权益不一致，需人工复核来源字段。",
                Map.copyOf(values), evidence(statement, List.of("assets", "liabilities", "equity")),
                List.of(), verificationBasis);
    }

    Evaluation evaluateCrossMaterial(MaterialSet set, DemoRuleSet rules) {
        var application = one(set, "CREDIT_APPLICATION");
        var contract = one(set, "USE_CONTRACT");
        var missingMaterials = new ArrayList<String>();
        if (application == null) missingMaterials.add("CREDIT_APPLICATION");
        if (contract == null) missingMaterials.add("USE_CONTRACT");
        if (!missingMaterials.isEmpty()) return missing("application-contract-consistency", rules,
                missingMaterials, emptyBasis("CROSS_MATERIAL"));
        Map<String, Object> verificationBasis = comparisonBasis(application, contract);
        var missing = new ArrayList<String>();
        BigDecimal requested = amount(application, "requestedAmount", missing);
        BigDecimal contractAmount = amount(contract, "contractAmount", missing);
        if (blank(application.subject())) missing.add(application.materialId() + ":subject");
        if (blank(contract.subject())) missing.add(contract.materialId() + ":subject");
        if (!missing.isEmpty()) return missing("application-contract-consistency", rules, missing, verificationBasis);

        String incomparable = comparabilityFailure(application, contract);
        List<Map<String, Object>> evidence = new ArrayList<>();
        evidence.addAll(evidence(application, List.of("requestedAmount")));
        evidence.addAll(evidence(contract, List.of("contractAmount")));
        if (incomparable != null) {
            return notComparable("application-contract-consistency", rules, incomparable, evidence,
                    verificationBasis);
        }
        int scale = rules.amountScale();
        RoundingMode rounding = RoundingMode.valueOf(rules.roundingMode());
        requested = requested.setScale(scale, rounding);
        contractAmount = contractAmount.setScale(scale, rounding);
        BigDecimal difference = requested.subtract(contractAmount).setScale(scale, rounding);
        AuditStatus status = difference.compareTo(BigDecimal.ZERO.setScale(scale)) == 0
                ? AuditStatus.CONSISTENT : AuditStatus.INCONSISTENT;
        Map<String, Object> values = Map.of(
                "applicationAmount", requested.toPlainString(),
                "contractAmount", contractAmount.toPlainString(),
                "difference", difference.toPlainString(),
                "subject", application.subject(),
                "currency", application.currency(),
                "businessBasis", application.businessBasis());
        return new Evaluation(status, "application-contract-consistency", rules.ruleSetId(), rules.version(),
                status == AuditStatus.CONSISTENT
                        ? "申请与用途合同在当前可比字段下保持一致；不代表材料真实。"
                        : "申请与用途合同金额不一致，需核对用途范围或补充差额说明。",
                values, List.copyOf(evidence), List.of(), verificationBasis);
    }

    private static String comparabilityFailure(MaterialSet.Material left, MaterialSet.Material right) {
        if (!Objects.equals(left.subject(), right.subject())) return "材料主体不一致，不能直接认定金额冲突。";
        if (left.period() == null || right.period() == null || !Objects.equals(left.period(), right.period()))
            return "材料期间不一致或缺失，不能直接比较。";
        if (blank(left.currency()) || !Objects.equals(left.currency(), right.currency()))
            return "材料币种不一致或缺失，不能直接比较。";
        if (blank(left.unit()) || !Objects.equals(left.unit(), right.unit()))
            return "计量单位不一致或缺失，不能直接比较。";
        if (blank(left.businessBasis()) || !Objects.equals(left.businessBasis(), right.businessBasis()))
            return "业务口径不一致或缺失，不能直接比较。";
        return null;
    }

    private static MaterialSet.Material one(MaterialSet set, String category) {
        return set.materials().stream().filter(m -> category.equals(m.category())).findFirst().orElse(null);
    }

    private static BigDecimal amount(MaterialSet.Material material, String name, List<String> missing) {
        MaterialSet.Field field = material.fields().get(name);
        if (field == null || field.status() != MaterialSet.FieldStatus.EXTRACTED || field.value() == null) {
            missing.add(material.materialId() + ":" + name);
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(field.value()).trim());
        } catch (NumberFormatException exception) {
            missing.add(material.materialId() + ":" + name);
            return null;
        }
    }

    private static List<Map<String, Object>> evidence(MaterialSet.Material material, List<String> fields) {
        var result = new ArrayList<Map<String, Object>>();
        for (String name : fields) {
            MaterialSet.Field field = material.fields().get(name);
            if (field == null) continue;
            var item = new LinkedHashMap<String, Object>();
            item.put("materialId", material.materialId());
            item.put("materialVersion", material.version());
            item.put("materialDigest", material.sha256());
            item.put("field", name);
            item.put("value", field.value());
            item.put("fieldStatus", field.status().name());
            if (field.locator() != null) item.put("sourceLocator", Map.of(
                    "kind", field.locator().kind(), "value", field.locator().value()));
            result.add(Map.copyOf(item));
        }
        return List.copyOf(result);
    }

    private static Map<String, Object> singleMaterialBasis(MaterialSet.Material material) {
        return Map.of(
                "mode", "SINGLE_MATERIAL",
                "outcome", "RECORDED",
                "dimensions", List.of(
                        dimension("subject", "主体", List.of(basisValue(material, "subject", material.subject())),
                                blank(material.subject()) ? "MISSING" : "RECORDED"),
                        dimension("period", "期间", List.of(basisValue(material, "period", periodValue(material.period()))),
                                material.period() == null ? "MISSING" : "RECORDED"),
                        dimension("currency", "币种", List.of(basisValue(material, "currency", material.currency())),
                                blank(material.currency()) ? "MISSING" : "RECORDED"),
                        dimension("unit", "计量单位", List.of(basisValue(material, "unit", material.unit())),
                                blank(material.unit()) ? "MISSING" : "RECORDED"),
                        dimension("businessBasis", "业务口径",
                                List.of(basisValue(material, "businessBasis", material.businessBasis())),
                                blank(material.businessBasis()) ? "MISSING" : "RECORDED")));
    }

    private static Map<String, Object> comparisonBasis(MaterialSet.Material left, MaterialSet.Material right) {
        List<Map<String, Object>> dimensions = List.of(
                comparedDimension("subject", "主体", left, right, left.subject(), right.subject()),
                comparedDimension("period", "期间", left, right,
                        periodValue(left.period()), periodValue(right.period())),
                comparedDimension("currency", "币种", left, right, left.currency(), right.currency()),
                comparedDimension("unit", "计量单位", left, right, left.unit(), right.unit()),
                comparedDimension("businessBasis", "业务口径", left, right,
                        left.businessBasis(), right.businessBasis()));
        boolean comparable = dimensions.stream().allMatch(item -> "MATCH".equals(item.get("outcome")));
        return Map.of("mode", "CROSS_MATERIAL", "outcome", comparable ? "COMPARABLE" : "NOT_COMPARABLE",
                "dimensions", dimensions);
    }

    private static Map<String, Object> comparedDimension(String name, String label,
                                                          MaterialSet.Material left, MaterialSet.Material right,
                                                          Object leftValue, Object rightValue) {
        String outcome = leftValue == null || rightValue == null || blankValue(leftValue) || blankValue(rightValue)
                ? "MISSING" : Objects.equals(leftValue, rightValue) ? "MATCH" : "MISMATCH";
        return dimension(name, label, List.of(
                basisValue(left, name, leftValue), basisValue(right, name, rightValue)), outcome);
    }

    private static Map<String, Object> dimension(String name, String label,
                                                  List<Map<String, Object>> values, String outcome) {
        return Map.of("name", name, "label", label, "outcome", outcome, "values", values);
    }

    private static Map<String, Object> basisValue(MaterialSet.Material material, String name, Object value) {
        var result = new LinkedHashMap<String, Object>();
        result.put("materialId", material.materialId());
        result.put("materialVersion", material.version());
        result.put("materialDigest", material.sha256());
        if (value == null) result.put("valueStatus", "MISSING");
        else result.put("value", value);
        result.put("sourceLocator", Map.of("kind", "JSON_POINTER", "value", "/" + name));
        return Map.copyOf(result);
    }

    private static Map<String, Object> periodValue(MaterialSet.Period period) {
        if (period == null) return null;
        var value = new LinkedHashMap<String, Object>();
        if (period.start() != null) value.put("start", period.start().toString());
        if (period.end() != null) value.put("end", period.end().toString());
        if (period.label() != null) value.put("label", period.label());
        return Map.copyOf(value);
    }

    private static boolean blankValue(Object value) {
        return value instanceof String text && text.isBlank();
    }

    private static Map<String, Object> emptyBasis(String mode) {
        return Map.of("mode", mode, "outcome", "INCOMPLETE", "dimensions", List.of());
    }

    private static Evaluation missing(String check, DemoRuleSet rules, List<String> missing,
                                      Map<String, Object> verificationBasis) {
        return new Evaluation(AuditStatus.MISSING_REQUIRED_FIELDS, check, rules.ruleSetId(), rules.version(),
                "必要材料或字段缺失，无法计算。", Map.of(), List.of(), List.copyOf(missing), verificationBasis);
    }

    private static Evaluation notComparable(String check, DemoRuleSet rules, String reason,
                                             List<Map<String, Object>> evidence,
                                             Map<String, Object> verificationBasis) {
        return new Evaluation(AuditStatus.NOT_COMPARABLE, check, rules.ruleSetId(), rules.version(), reason,
                Map.of(), List.copyOf(evidence), List.of(), verificationBasis);
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    record Evaluation(AuditStatus status, String checkId, String ruleSetId, String ruleVersion,
                      String summary, Map<String, Object> values, List<Map<String, Object>> evidence,
                      List<String> missingInputs, Map<String, Object> verificationBasis) { }
}
