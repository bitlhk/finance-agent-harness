---
name: spending-analysis-journey
description: >-
  Use this skill when the user wants bank 收支分析. First get today's date
  (cap.qieman.get-current-time), then monthly totals, then category/trend.
  Also 收支都看看, 财务概况和摘要, 这个月收支情况都查一下. Sequence
  cap-qieman-get-current-time → finance-transaction-summary-query →
  finance-spending-analysis-query. period is YYYY-MM from today unless the
  user already gave an absolute month; trend lookback is always 6 months.
  Do not use for a single 本月收支摘要, a single 财务概况/花销分析,
  且慢收入支出分析, FAQ, budget, or purchase.
version: 0.3.0
license: Apache-2.0
allowed-tools: cap-qieman-get-current-time finance-transaction-summary-query finance-spending-analysis-query
metadata:
  capabilityId: skill.finance.spending-analysis-journey
  ownerAgentId: agent.finance_assistant
---

# 银行收支分析

模型不知道今天几号。未给绝对月份时，**先对时**，再查进出，再做分类/趋势。

本 Skill 是 **NL 规划规约**。`allowed-tools` 是白名单。

真源门：

| 说法 | TOOL |
| --- | --- |
| 本月收支摘要（只要数字） | 缺绝对月则先 `cap.qieman.get-current-time`，再 `finance.transaction-summary.query` |
| 财务概况 / 花销分析（只要分类趋势） | 缺绝对月则先对时，再 `finance.spending-analysis.query` |
| 收支分析 / 收支都看看 / 财务概况和摘要 | `cap.qieman.get-current-time` → `finance.transaction-summary.query` → `finance.spending-analysis.query` |
| 用户已给 `YYYY-MM` / 2026年3月 | 跳过对时，后两步共用该 `period` |
| 用且慢…收入支出分析 | 不在本白名单 |

## 周期

银行接口只有 `period=YYYY-MM`（锚点月）。对时用目录已有的 `cap.qieman.get-current-time`（且慢 `GetCurrentTime`），不自造时间卡。

- 未指定 / 本月 / 上个月 / 近半年 → 先对时，把返回日期收成 `YYYY-MM`（上个月再减一月）
- 已给绝对月份 → 不对时
- 分析卡 `trend` 由政策 `TREND_MONTHS=6` 生成：锚点月往前共 6 个月。不要把「半年」塞进 `period`
- 对时失败才退回卡上默认 `2026-08`

## 规划规约

1. 「收支分析」默认三步：对时 → 摘要 → 分析，后两步同一 `period`。
2. 只点名摘要或概况 → 仍按上面「缺绝对月先对时」，不要硬凑另一扇查询门。
3. `period` 必须是 `YYYY-MM`。
4. 改口去明细 / 余额 / 预算 / 且慢收支分析，走正常意图。

## 不做

- 不调用且慢 `AnalyzeIncomeExpense`（对时工具除外）
- 不办预算，不查交易明细
- 不自造银行 capabilityId，不写 Java 时钟
