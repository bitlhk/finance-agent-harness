package io.github.bitlhk.finance.harness.providers.nonaibanking;

import java.util.List;
import io.github.bitlhk.finance.harness.core.observation.ObservationDisplayV01.*;

final class ReferenceObservationLabels {
    private ReferenceObservationLabels() { }
    static List<SourceLabel> descriptions() {
        return List.of(new SourceLabel(NonAiBankingProvider.PROVIDER_ID, "0.1.0", List.of(
                new OperationLabel("session.current", "GET", "/api/v1/sessions/current", "核验当前银行登录身份", "核对银行会话、客户归属与有效期"),
                new OperationLabel("session.create", "POST", "/api/v1/sessions", "建立银行登录会话", "向模拟银行申请登录会话"),
                new OperationLabel("accounts.list", "GET", "/api/v1/accounts", "读取账户余额列表", "查询已授权客户的账户信息"),
                new OperationLabel("accounts.detail", "GET", "/api/v1/accounts/{accountId}", "读取指定账户余额", "查询已授权的指定账户"),
                new OperationLabel("wealth.aggregate", "GET", "/api/v1/wealth/aggregate", "读取本行资产概览", "获取来源系统报告的资产范围与金额"),
                new OperationLabel("risk.assessment", "GET", "/api/v1/finance/risk-assessment", "读取风险测评记录", "获取测评等级与有效期，不代表适当性结论"),
                new OperationLabel("cashflow.summary.readiness", "GET", "/api/v1/finance/transaction-summary/readiness", "检查收支摘要数据是否可用", "查询该项数据的可用性"),
                new OperationLabel("cashflow.summary", "GET", "/api/v1/finance/transaction-summary", "读取月度收支摘要", "获取收入、支出与结余事实"),
                new OperationLabel("cashflow.analysis.readiness", "GET", "/api/v1/finance/spending-analysis/readiness", "检查收支分析数据是否可用", "查询分类与趋势数据的可用性"),
                new OperationLabel("cashflow.analysis", "GET", "/api/v1/finance/spending-analysis", "读取收支分类与趋势", "获取分类、趋势等分析事实")
        )));
    }
}
