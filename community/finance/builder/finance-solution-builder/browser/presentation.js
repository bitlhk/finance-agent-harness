export const esc = (value) =>
  String(value ?? "").replace(
    /[&<>"']/g,
    (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[
        c
      ],
  );
export const terminals = [
  { id: "MOBILE_WEB", label: "手机端", icon: "smartphone" },
  { id: "DESKTOP_WEB", label: "电脑端", icon: "monitor" },
  { id: "ATM_WEB", label: "ATM端", icon: "landmark" },
];
export const defaultUI = () => ({
  schemaVersion: "finance-interface.v0.1",
  assistantName: "我的金融助手",
  brandIcon: "landmark",
  theme: "FINANCE_RED",
  balanceCard: "balance-summary-v1",
  fontSize: "STANDARD",
  shortcuts: ["BALANCE", "WEALTH", "CASHFLOW_SUMMARY", "CASHFLOW_ANALYSIS"],
  voiceInput: false,
  sourcePanelExpanded: true,
  layoutVersion: "terminal-layout.v0.1",
});
export const themeName = (theme) =>
  theme === "EXAMPLE_TEAL" ? "示例银行主题" : "默认主题";
export const terminalName = (id) =>
  terminals.find((t) => t.id === id)?.label || "手机端";
export const icon = (name) =>
  `<svg class="icon" aria-hidden="true"><use href="${location.pathname.startsWith("/experience/") ? "./" : "/"}icons.svg#${name}"></use></svg>`;
const labels = {
  AVAILABLE: "已返回",
  REPORTED: "已返回",
  MISSING: "未返回",
  UNAVAILABLE: "来源不可用",
  NOT_COVERED: "未覆盖",
  EXPIRED: "已过期",
  CONFLICT: "存在冲突",
  COMPLETED: "查询完成",
  PARTIAL_FOR_DESCRIPTION: "部分信息待核实",
  COMPLETE_FOR_DESCRIPTION: "描述所需信息齐备",
  NOT_CHECKED: "未核验",
  SOURCE_REPORTED_UNSPECIFIED: "来源报告资产，统计范围待核实",
  OWN_BANK: "本行资产",
  OWN_BANK_ASSETS: "本行资产",
  AUTHORIZED_ACCOUNTS_RETURNED: "本次获授权返回的账户",
  OWN_BANK_ACCOUNT_STATEMENT: "本行账户流水汇总（账户列表最多100项，按UTC月份）",
  OWN_BANK_BOOKED_WITH_CATEGORIES: "本行已入账人民币流水（按来源分类）",
  ACCOUNT_LIST_LIMIT_100: "汇总最多覆盖100个账户",
  SOURCE_BOOKED_STATUS_NOT_VERIFIED: "汇总来源未限定已入账状态，不与分类口径混加",
  ANALYSIS_PERIOD_TIMEZONE_NOT_VERIFIED: "分类统计所用月份时区尚未核实",
  PERIOD_COVERAGE_NOT_VERIFIED: "历史月份覆盖未核实；来源返回零不代表无遗漏",
  TREND_COMPARABILITY_NOT_VERIFIED: "月份可比性未核实，不计算环比或改善幅度",
  OTHER_BANKS_AND_CURRENCIES_NOT_COVERED: "不覆盖他行及其他币种，不进行汇率折算",
  CREDIT_CARD_BILLS_NOT_ADDED_TO_CASHFLOW: "未将信用卡账单重复计入收支",
  UNCLASSIFIED: "未分类",
  TOTALS_NOT_RETURNED: "收支合计信息未完整返回",
  CATEGORIES_NOT_RETURNED: "分类信息未返回，不按空分类或零交易处理",
  TREND_NOT_RETURNED: "月份记录未返回",
  SOURCE_TIME_NOT_RETURNED: "来源事实时间未返回",
  TREND_TOTALS_CONFLICT: "月份记录与当期合计存在冲突",
  CLASSIFICATION_NOT_RETURNED: "部分流水没有分类信息，保留为未分类",
  CNY: "人民币",
  "assets.valuationAt": "资产估值时间",
  "assets.coverage": "资产覆盖范围",
  "assets.scope": "资产统计范围",
  "risk.scheme": "风险评级体系",
  "risk.assessmentVersion": "测评版本",
  "risk.expiresOn": "测评有效期",
  USD: "美元",
  HKD: "港币",
  SETTLEMENT_ACCOUNT_I: "Ⅰ类账户",
  SETTLEMENT_ACCOUNT_II: "Ⅱ类账户",
  SETTLEMENT_ACCOUNT_III: "Ⅲ类账户",
  SAVINGS: "储蓄账户",
  FOREIGN_CURRENCY: "外币账户",
};
export const label = (value) => labels[value] || value || "未提供";
const gapLabel = (field) =>
  typeof field === "string" && field.startsWith("assets.missingSources:")
    ? "未覆盖来源：" + field.slice("assets.missingSources:".length)
    : label(field);
export function decimal(value) {
  if (typeof value !== "string" || !/^-?\d+(\.\d+)?$/.test(value))
    return "未返回";
  const [whole, fraction] = value.split(".");
  return (
    whole.replace(/\B(?=(\d{3})+(?!\d))/g, ",") +
    (fraction === undefined ? "" : "." + fraction)
  );
}
function source(source) {
  return `<details class="fact-source"><summary>${icon("file-check-2")}数据来源</summary><dl><dt>来源</dt><dd>${esc(source?.sourceSystem || source?.reference || source?.system || "未提供")}</dd><dt>${source?.asOf ? "数据时间" : "观察时间"}</dt><dd>${esc(source?.asOf || source?.observedAt || "未提供")}</dd>${source?.contract ? `<dt>合同</dt><dd>${esc(source.contract)}</dd>` : ""}${source?.recordVersion ? `<dt>来源版本</dt><dd>${esc(source.recordVersion)}</dd>` : ''}${source?.policyRef ? `<dt>来源规则</dt><dd>${esc(source.policyRef)}</dd><dt>规则时间</dt><dd>${esc(source.policyAsOf || '未提供')}</dd>` : ''}</dl></details>`;
}
function cashflow(card) {
  const analysis = card.section === 'ANALYSIS';
  const rows = analysis ? (card.categories || []) : [];
  const amounts = ['income', 'expense', 'netCashflow'];
  const titles = ['收入', '支出', '净收支'];
  const emptyCategories = card.gaps?.includes('CATEGORIES_NOT_RETURNED')
    ? '分类信息未返回，不按零交易处理。'
    : '本次统计口径返回0条分类记录，不代表全行没有交易。';
  const classification = [...new Set(rows.map(row=>row.classificationVersion).filter(Boolean))];
  // Simulator V130, spending-category-v1. Other source taxonomies keep their original values.
  const categoryLabel = row => row.classificationVersion === 'spending-category-v1'
    ? ({DAILY_LIVING:'日常消费',INCOME:'收入',UTILITIES:'生活缴费'}[row.name] || label(row.name)) : label(row.name);
  return `<article class="fact-card cashflow-card" data-component="cashflow-${analysis ? 'analysis' : 'summary'}-v1"><header>${icon('chart-column')}<h3>${analysis ? '收支分类' : '收支汇总'}</h3><span>${esc(card.period || '月份未提供')}</span></header>
    <dl>${amounts.map((key,i)=>`<dt>${titles[i]}</dt><dd>${esc(decimal(card.totals?.[key]))} ${esc(card.currency || '币种未提供')}</dd>`).join('')}</dl>
    <p class="fact-scope">${esc(label(card.coverage))}</p>
    ${rows.length ? `<div class="fact-table" tabindex="0" role="region" aria-label="收支分类明细"><table><thead><tr><th>来源分类</th><th>收入</th><th>支出</th><th>笔数</th></tr></thead><tbody>${rows.map(row=>`<tr data-category="${esc(row.name)}"><td>${esc(categoryLabel(row))}</td><td>${esc(decimal(row.totals?.income))}</td><td>${esc(decimal(row.totals?.expense))}</td><td>${esc(row.transactionCount ?? '未提供')}</td></tr>`).join('')}</tbody></table></div>` : analysis ? `<p class="fact-gap">${emptyCategories}</p>` : ''}
    ${card.trend?.length ? `<details><summary>来源返回的月份记录</summary><div class="fact-table" tabindex="0" role="region" aria-label="月份收支记录"><table><thead><tr><th>月份</th><th>收入</th><th>支出</th><th>净收支</th></tr></thead><tbody>${card.trend.map(row=>`<tr><td>${esc(row.period)}</td>${amounts.map(key=>`<td>${esc(decimal(row.totals?.[key]))}</td>`).join('')}</tr>`).join('')}</tbody></table></div></details>` : ''}
    ${card.gaps?.length ? `<ul class="fact-gap">${card.gaps.map(g=>`<li data-gap="${esc(g)}">${esc(label(g))}</li>`).join('')}</ul>` : ''}
    ${source(card.source)}${classification.length ? `<details class="fact-source"><summary>分类与来源版本</summary>${rows.map(row=>`<p><code>${esc(row.name)}</code> · ${esc(row.classificationVersion || '未提供')}</p>`).join('')}</details>` : ''}</article>`;
}
function balanceSummary(card) {
  return `<article class="fact-card balance-summary" data-component="balance-summary-v1"><header>${icon("wallet")}<h3>账户余额</h3></header>${card.rows?.map((row, i) => `<div class="balance-row"><div class="account-heading"><strong>${esc(row.accountLabel || "账户 " + (i + 1))}</strong>${row.accountType ? `<span>${esc(label(row.accountType))}</span>` : ''}</div><span class="value-label">可用余额</span>${money(row.availability === 'AVAILABLE' ? row.amount : undefined,row.currency)}<small class="fact-availability ${row.availability === 'AVAILABLE' ? '' : 'fact-gap'}">${esc(label(row.availability))}</small></div>`).join("") || '<p class="fact-gap">账户结果未返回，不代表余额为零。</p>'}<p class="fact-scope">${esc(label(card.scope))}</p><p class="fact-time">数据时间：${esc(card.source?.asOf || '未提供')}</p>${source(card.source)}</article>`;
}
function money(value, currency) {
  const formatted = decimal(value), long = formatted.length > 18 ? ' money-long' : '';
  return `<div class="money${long}"><span class="money-number">${esc(formatted)}</span><small>${esc(currency || '币种未返回')}</small></div>`;
}
function balanceLedger(card) {
  return `<article class="fact-card balance-ledger" data-component="balance-ledger-v1"><header><h3>可用资金明细</h3>${icon("landmark")}</header><div class="fact-table" tabindex="0" role="region" aria-label="账户明细表"><table><thead><tr><th>账户</th><th>币种</th><th>可用金额</th></tr></thead><tbody>${card.rows?.map((row, i) => `<tr><td>${esc(row.accountLabel || "账户 " + (i + 1))}</td><td class="currency-cell">${esc(row.currency || "未返回")}</td><td class="amount-cell">${esc(decimal(row.availability === 'AVAILABLE' ? row.amount : undefined))}<small>${esc(label(row.availability))}</small></td></tr>`).join("") || '<tr><td colspan="3">账户结果未返回，不代表余额为零。</td></tr>'}</tbody></table></div><footer>${esc(label(card.scope))}<p class="fact-time">数据时间：${esc(card.source?.asOf || '未提供')}</p></footer>${source(card.source)}</article>`;
}
export function cards(display, config = defaultUI()) {
  if (!display) return "";
  if (
    display.schemaVersion !== "finance-display.v0.1" ||
    !Array.isArray(display.cards)
  )
    return '<p class="fact-gap">结果展示版本暂不支持。</p>';
  return display.cards
    .map((card) => {
      if (card.type === 'records') {
        if (card.version !== '0.1' || !Array.isArray(card.columns) || !Array.isArray(card.rows))
          return '<p class="fact-gap">结果展示版本暂不支持。</p>';
        const columns = card.columns.slice(0, 16), rows = card.rows.slice(0, 100);
        const scope = card.context || {};
        return `<article class="fact-card" data-component="records-v1"><header><h3>${esc(card.title)}</h3></header>
          <p class="fact-scope">${esc(card.scope)}</p>
          ${scope.period ? `<p>月份：${esc(scope.period)}</p>` : ''}
          ${scope.dateFrom && scope.dateTo ? `<p>日期范围：${esc(scope.dateFrom)} 至 ${esc(scope.dateTo)}</p>` : ''}
          <div class="fact-table" tabindex="0" role="region" aria-label="查询结果"><table><thead><tr>${columns.map(c => `<th>${esc(c.label)}</th>`).join('')}</tr></thead>
          <tbody>${rows.map(row => `<tr>${columns.map(c => `<td>${esc(row[c.key] ?? '未提供')}</td>`).join('')}</tr>`).join('')}</tbody></table></div>
          ${rows.length ? '' : '<p class="fact-gap">当前查询范围未返回记录，不代表其他范围没有数据。</p>'}
          ${scope.hasMore === true || scope.hasMore === 'true' ? '<p>仍有后续页面，本次未自动读取。</p>' : ''}${source(card.source)}</article>`;
      }
      if (card.type === "cashflow") return cashflow(card);
      if (card.type === "balance")
        return config.balanceCard === "balance-ledger-v1"
          ? balanceLedger(card)
          : balanceSummary(card);
      if (card.type === "wealth")
        return `<article class="fact-card"><header>${icon("blocks")}<h3>已知资产</h3><span>${esc(label(card.availability))}</span></header>${card.amounts.map((row) => `<div class="balance-row"><div class="money">${esc(decimal(row.money?.value))}<small>${esc(row.money?.currency || "未返回")}</small></div><p>${esc(label(row.scope))}</p><small>估值时间：${esc(row.valuationAt || "未提供")}</small></div>`).join("") || '<p class="fact-gap">资产金额未返回，不按零计算。</p>'}<p class="fact-scope">来源报告范围，不代表全部家庭资产；不与账户余额重复加总。</p>${card.missingSources?.length ? `<p class="fact-gap">未覆盖：${card.missingSources.map(esc).join("、")}</p>` : ""}${source(card.source)}</article>`;
      if (card.type === "risk") {
        const r = card.record;
        return `<article class="fact-card"><header>${icon("shield-check")}<h3>风险测评记录</h3><span>${esc(label(r.availability))}</span></header><dl><dt>原始等级</dt><dd>${esc(r.level || "未返回")}</dd><dt>评级体系</dt><dd>${esc(r.scheme || "未明确")}</dd><dt>版本</dt><dd>${esc(r.assessmentVersion || "未提供")}</dd><dt>测评日期</dt><dd>${esc(r.assessedOn || "未提供")}</dd><dt>有效期至</dt><dd>${esc(r.expiresOn || "未提供")}</dd></dl><p class="fact-scope">未进行产品适当性判断，不构成投资建议。</p>${source(card.source)}</article>`;
      }
      if (card.type === "gaps")
        return `<article class="fact-card gaps-card"><header>${icon("file-check-2")}<h3>信息核验</h3></header><p>${esc(label(card.readiness))}</p>${card.gaps.length ? `<ul>${card.gaps.map((g) => `<li data-field="${esc(g.field)}">${esc(gapLabel(g.field))}：${esc(label(g.reason))}</li>`).join("")}</ul>` : ""}</article>`;
      return '<p class="fact-gap">该结果暂无兼容展示，不提供额外操作。</p>';
    })
    .join("");
}
export function preview(config, terminal, capabilities, page = "RESULT") {
  const sample = {
    schemaVersion: "finance-display.v0.1",
    cards: capabilities.includes("account.balance.query")
      ? [
          {
            type: "balance",
            scope: "AUTHORIZED_ACCOUNTS_RETURNED",
            rows: [
              {
                accountLabel: "示例账户 · 尾号 1234",
                amount: "128650.32",
                currency: "CNY",
                availability: "AVAILABLE",
              },
            ],
            source: {
              sourceSystem: "固定布局样例",
              asOf: "2026-09-01T10:00:00+08:00",
            },
          },
        ]
      : capabilities.includes("wealth.facts.describe")
        ? [
            {
              type: "wealth",
              availability: "AVAILABLE",
              amounts: [
                {
                  money: { value: "360000.00", currency: "CNY" },
                  scope: "SOURCE_REPORTED_UNSPECIFIED",
                },
              ],
              missingSources: ["外部资产"],
              source: { reference: "固定布局样例" },
            },
          ]
        : capabilities.includes('finance.transaction-summary.query') ? [{
            type: 'cashflow', section: 'SUMMARY', period: '2026-08', currency: 'CNY',
            coverage: 'OWN_BANK_ACCOUNT_STATEMENT', totals: {income:'10000.00',expense:'1250.50',netCashflow:'8749.50'},
            categories: [], trend: [], gaps: ['PERIOD_COVERAGE_NOT_VERIFIED'],
            source: {system:'固定布局样例',asOf:'2026-09-01T01:00:00Z'},
          }] : [],
  };
  const shortcuts = (config.shortcuts || []).map(key => key === 'BALANCE' && capabilities.includes('account.balance.query') ? `<span>${icon('wallet')}查询余额</span>` : key === 'WEALTH' && capabilities.includes('wealth.facts.describe') ? `<span>${icon('chart-pie')}查看资产</span>` : key === 'CASHFLOW_SUMMARY' && capabilities.includes('finance.transaction-summary.query') ? `<span>${icon('chart-column')}收支汇总</span>` : key === 'CASHFLOW_ANALYSIS' && capabilities.includes('finance.spending-analysis.query') ? `<span>${icon('chart-column')}收支分类</span>` : '').join('');
  const question = capabilities.includes('account.balance.query') ? '我的账户余额是多少？' : capabilities.includes('wealth.facts.describe') ? '查看我的已知资产' : '查看2026-08收支汇总';
  const body = page === 'HOME' ? `<div class="preview-welcome"><span class="preview-emblem">${icon(config.brandIcon || 'landmark')}</span><h3>${terminal === 'ATM_WEB' ? '欢迎使用' : '您好，有什么可以帮您？'}</h3><div class="preview-shortcuts">${shortcuts || '<p>尚未选择业务能力</p>'}</div></div>` : `<div class="preview-dialogue"><p class="preview-question">${question}</p><span class="preview-assistant">${icon(config.brandIcon || 'landmark')}金融助手</span></div><div class="preview-result">${cards(sample,config) || '<p class="fact-gap">尚未选择业务能力</p>'}${capabilities.includes('wealth.facts.describe') && capabilities.includes('account.balance.query') ? `<div class="preview-next">${icon('chart-pie')}了解已知资产 ${icon('arrow-up')}</div>` : ''}</div>`;
  return `<div class="terminal-preview ${terminal.toLowerCase()} theme-${config.theme}" data-preview="true"><header><span class="preview-brand">${icon(config.brandIcon || "landmark")}</span><strong>${esc(config.assistantName)}</strong></header><span class="preview-environment">固定示例数据 · 不调用银行或模型</span><div class="preview-body ${page === 'HOME' ? 'is-home' : 'is-result'}" tabindex="0" role="region" aria-label="预览内容">${body}</div><footer>${terminal === "ATM_WEB" ? '<span>返回</span><span>帮助</span><span>结束服务</span>' : `<span>输入您的问题</span><span class="preview-send">${icon('arrow-up')}</span>`}</footer></div>`;
}
