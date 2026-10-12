export function runtimeVerificationText(value) {
  const checked=value.verification?.validation;
  if(checked)return checked.reason||'运行证据不足，需核对对应报告';
  return value.validation?.status==='PASS'
    ?'原清单记载检查通过；未取得可重新核验的版本与执行证据'
    :'未取得此资产的专属试跑通过证据';
}

export function verificationRows(value) {
  const evidence=value.verification||{},runtime=evidence.runtime||{},actual=evidence.actualRuntime;
  const at=typeof evidence.createdAt==='number'&&Number.isFinite(evidence.createdAt)?new Date(evidence.createdAt*1000):null;
  const time=at&&!Number.isNaN(at.getTime())?new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false}).format(at)+'（北京时间）':'未记录';
  const model=evidence.model==='CONTROLLED_TEST'?'受控测试模型':evidence.model||'模型环境未记录';
  const source=evidence.source==='CONTROLLED_HTTP'?'隔离 HTTP 数据源':evidence.source||'数据环境未记录';
  const environment=actual?[actual.family,actual.language,actual.version].filter(Boolean).join(' · '):
    runtime.family?[runtime.family,runtime.language].filter(Boolean).join(' · ')+'（报告声明，实际版本证据未完整记录）':'未记录';
  return [['报告记录时间',time],['运行环境',environment],
    ['模型与来源',model+'；'+source],['验证范围',evidence.comparisonKind==='DEFAULT_VS_DRAFT_LOADING_AND_EXECUTION_NOT_QUALITY'?'方法加载与受控执行对照，不包含专业回答质量验收':'范围未记录，请核对原始验证依据']];
}
