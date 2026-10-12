package io.github.bitlhk.finance.harness.providers.smartaudit;

import io.github.bitlhk.finance.harness.core.CapabilityRequestV1;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The immutable routing table for the two Smart Audit agents. */
public final class SmartAuditCapabilityCatalog {
    public static final String PERSONAL_BORROWER_QUALIFICATION =
            "smartaudit.personal.borrower-qualification.query";
    public static final String PERSONAL_INCOME_VERIFICATION =
            "smartaudit.personal.income-verification.query";
    public static final String PERSONAL_ASSET_LIABILITY = "smartaudit.personal.asset-liability.query";
    public static final String PERSONAL_REPAYMENT_CAPACITY =
            "smartaudit.personal.repayment-capacity.query";
    public static final String ENTERPRISE_FINANCIAL_REPORT =
            "smartaudit.enterprise.financial-report.query";
    public static final String ENTERPRISE_FINANCIAL_SUMMARY =
            "smartaudit.enterprise.financial-summary.query";
    public static final String ENTERPRISE_TRANSACTION_ANALYSIS =
            "smartaudit.enterprise.transaction-analysis.query";
    public static final String ENTERPRISE_TRANSACTION_REPORT =
            "smartaudit.enterprise.transaction-report.query";
    public static final String ENTERPRISE_DUE_DILIGENCE = "smartaudit.enterprise.due-diligence.query";

    private static final List<TaskDefinition> TASKS = List.of(
            personal(PERSONAL_BORROWER_QUALIFICATION, "borrower-qualification", "borrower-qualification",
                    "smart-audit:personal:borrower-qualification:read",
                    List.of("身份证", "户口本", "结婚证", "贷款申请表"),
                    List.of("主体资格", "一致性核验", "风险与补件"),
                    List.of("qualificationIncomePreGeneratedRules")),
            personal(PERSONAL_INCOME_VERIFICATION, "income-verification", "income-verification",
                    "smart-audit:personal:income:read", List.of("收入证明", "流水", "个税", "社保"),
                    List.of("收入交叉验证", "口径差异", "风险与补件"),
                    List.of("qualificationIncomePreGeneratedRules")),
            personal(PERSONAL_ASSET_LIABILITY, "asset-liability", "asset-liability",
                    "smart-audit:personal:asset-liability:read", List.of("不动产权证", "产权查询", "贷款结清证明"),
                    List.of("资产权属", "负债与限制", "风险与补件"),
                    List.of("assetLiabilityDefaultRules")),
            personal(PERSONAL_REPAYMENT_CAPACITY, "repayment-capacity", "repayment-capacity",
                    "smart-audit:personal:repayment-capacity:read", List.of("个税", "流水", "贷款申请表"),
                    List.of("稳定收入", "偿债比", "风险与补件"),
                    List.of("repaymentCapacityDefaultRules")),
            enterprise(ENTERPRISE_FINANCIAL_REPORT, "financial-report", "financial-report",
                    "smart-audit:enterprise:financial-report:read", List.of("资产负债表"),
                    List.of("分类", "逻辑与勾稽", "异常与补件")),
            enterprise(ENTERPRISE_FINANCIAL_SUMMARY, "financial-summary", "financial-summary",
                    "smart-audit:enterprise:financial-summary:read", List.of("财务数据", "财务概述资料"),
                    List.of("盈利", "偿债", "运营", "发展")),
            enterprise(ENTERPRISE_TRANSACTION_ANALYSIS, "transaction-analysis", "transaction-analysis",
                    "smart-audit:enterprise:transaction:read", List.of("客户资料", "经营者资料", "逐笔流水"),
                    List.of("资料概览", "异常事实", "人工复核关注点")),
            enterprise(ENTERPRISE_TRANSACTION_REPORT, "transaction-report", "transaction-report",
                    "smart-audit:enterprise:transaction-report:read", List.of("流水统计信息", "分析问题"),
                    List.of("流水透视", "异常事实与待核验事项", "人工复核关注点")),
            enterprise(ENTERPRISE_DUE_DILIGENCE, "due-diligence", "due-diligence",
                    "smart-audit:enterprise:due-diligence:read",
                    List.of("客户资料", "授信资料", "财务资料", "行业资料", "担保资料", "还款来源资料"),
                    List.of("尽调报告", "资料缺口", "人工复核关注点")));
    private static final Map<String, TaskDefinition> BY_CAPABILITY = index(TASKS);

    private SmartAuditCapabilityCatalog() { }

    public static List<TaskDefinition> tasks() { return TASKS; }

    public static TaskDefinition require(String capabilityId) {
        TaskDefinition task = BY_CAPABILITY.get(capabilityId);
        if (task == null) throw new IllegalArgumentException("CAPABILITY_NOT_SUPPORTED");
        return task;
    }

    public static List<io.github.bitlhk.finance.harness.core.ProviderDescriptorV1.CapabilitySupport> descriptorCapabilities() {
        return TASKS.stream().map(task -> new io.github.bitlhk.finance.harness.core.ProviderDescriptorV1.CapabilitySupport(
                task.capabilityId(), CapabilityRequestV1.Operation.QUERY)).toList();
    }

    private static TaskDefinition personal(String capabilityId, String taskId, String skillId, String scope,
                                           List<String> categories, List<String> sections, List<String> ruleSubset) {
        return new TaskDefinition(capabilityId, taskId, skillId, "PERSONAL_LOAN_AUDIT", scope,
                true, categories, sections, ruleSubset);
    }

    private static TaskDefinition enterprise(String capabilityId, String taskId, String skillId, String scope,
                                             List<String> categories, List<String> sections) {
        return new TaskDefinition(capabilityId, taskId, skillId, "ENTERPRISE_CREDIT_AUDIT", scope,
                false, categories, sections, List.of());
    }

    private static Map<String, TaskDefinition> index(List<TaskDefinition> tasks) {
        Map<String, TaskDefinition> indexed = new LinkedHashMap<>();
        for (TaskDefinition task : tasks) {
            if (indexed.put(task.capabilityId(), task) != null) throw new IllegalStateException("duplicate capability");
        }
        return Map.copyOf(indexed);
    }

    public record TaskDefinition(String capabilityId, String taskId, String skillId, String purpose,
                                 String requiredScope, boolean personal, List<String> materialCategories,
                                 List<String> outputSections, List<String> ruleSubset) {
        public TaskDefinition {
            materialCategories = List.copyOf(materialCategories);
            outputSections = List.copyOf(outputSections);
            ruleSubset = List.copyOf(ruleSubset);
        }
    }
}
