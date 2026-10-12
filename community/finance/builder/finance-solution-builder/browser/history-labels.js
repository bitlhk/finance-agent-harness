// Legacy interpretation only. New records use their frozen component-owned descriptions.
export const interfaceLabels = {
  'GET /api/v1/sessions/current': ['核验当前银行登录身份', '核对银行会话、客户归属与有效期'],
  'POST /api/v1/sessions': ['建立银行登录会话', '向模拟银行申请登录会话'],
  'GET /api/v1/accounts': ['读取账户余额列表', '查询已授权客户的账户信息'],
  'GET /api/v1/accounts/{accountId}': ['读取指定账户余额', '查询已授权的指定账户'],
  'GET /api/v1/wealth/aggregate': ['读取本行资产概览', '获取来源系统报告的资产范围与金额'],
  'GET /api/v1/finance/risk-assessment': ['读取风险测评记录', '获取测评等级与有效期，不代表适当性结论'],
  'GET /api/v1/finance/transaction-summary/readiness': ['检查收支摘要数据是否可用', '查询该项数据的可用性'],
  'GET /api/v1/finance/transaction-summary': ['读取月度收支摘要', '获取收入、支出与结余事实'],
  'GET /api/v1/finance/spending-analysis/readiness': ['检查收支分析数据是否可用', '查询分类与趋势数据的可用性'],
  'GET /api/v1/finance/spending-analysis': ['读取收支分类与趋势', '获取分类、趋势等分析事实'],
};
const capabilities = {
  'account.balance.query': '查询账户余额',
  'wealth.facts.describe': '描述财富事实',
  'wealth.customer-context.query': '获取客户财富上下文',
  'finance.transaction-summary.query': '查询月度收支摘要',
  'finance.spending-analysis.query': '查询收支分类与趋势',
};
const host = {
  request: ['处理本轮请求', '本轮服务端处理区间'],
  'native-lock-wait': ['等待当前 Agent 空闲', '等待同一实例的执行锁，不是银行接口耗时'],
  authorization: ['检查当前查询权限', '检查本地实授策略及银行会话；不会因已授权过而跳过复核'],
  assembly: ['准备本轮金融能力', '装配本次使用的方法、工具与适配器'],
  'installation-lock': ['记录实际安装版本', '保存本轮安装清单与版本摘要'],
};
export function describeSpan(span, spans = [], display = null) {
  const links = span.links || {};
  const legacy = !display || display.state === 'LEGACY_FALLBACK';
  const capabilityName = id => display?.capabilities?.find(item => item.capabilityId === id)?.title
    || (legacy ? capabilities[id] : null);
  if (span.component === 'host' && host[span.operation]) return host[span.operation];
  if (span.component === 'model') return ['模型处理与生成', 'SDK 调用区间；模型 HTTP 是否发出未观测'];
  if (span.component === 'capability') return [capabilityName(links.capabilityId) || '调用金融能力', links.capabilityId || '能力标识未记录'];
  if (span.component === 'runtime-tool') {
    const ids = [...new Set(spans.filter(s => s.parentSpanId === span.spanId && s.component === 'capability').map(s => s.links?.capabilityId).filter(Boolean))];
    return [ids.length === 1 && capabilityName(ids[0]) ? `执行工具：${capabilityName(ids[0])}` : '执行金融工具', '工具执行区间；业务结果需单独核对'];
  }
  if (['provider-http', 'authorization-http'].includes(span.component)) {
    const key = `${links.method || ''} ${links.route || ''}`;
    const source = display?.sources?.find(item => item.providerId === links.providerId);
    const operation = source?.operations?.find(item => item.method === links.method && item.route === links.route);
    if (operation) return [operation.title, `${operation.description} · ${source.providerId}@${source.version} · ${operation.operationId}`];
    return (legacy && interfaceLabels[key]) || ['调用来源系统接口', `未保存匹配的业务说明：${links.providerId || '来源未记录'} ${key}`];
  }
  return ['其他执行步骤', '业务名称尚未映射'];
}
export function spanStatus(span) {
  if (span.links?.businessStatus) return ({COMPLETED:'银行业务已完成',FAILED_FINAL:'银行业务明确失败',FAILED:'业务失败',
    CANCELLED:'业务已取消',PROCESSING:'银行业务处理中',UNKNOWN:'银行业务结果待查证',NEED_CONFIRMATION:'业务等待用户确认',
    REJECTED:'业务请求已拒绝',DENIED:'业务请求已拒绝'})[span.links.businessStatus] || `业务状态：${span.links.businessStatus}`;
  if (span.links?.httpStatus) return `来源接口已返回 HTTP ${span.links.httpStatus}，不单独代表银行业务完成`;
  return ({COMPLETED:'步骤完成', RETURNED:'调用已返回', DENIED:'已阻断', ERROR:'执行异常',
    UNFINISHED:'结束未记录', INCOMPLETE:'记录不完整', INTERRUPTED:'已中断', CANCELLED:'本地已取消', CANCEL_REQUESTED:'已请求取消'})[span.outcome] || span.outcome;
}
