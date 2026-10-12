import { describeSpan, spanStatus } from './history-labels.js';
export function snapshotState(state) {
  return ({REDACTED:'已脱敏', CAPTURED:'已采集', TRUNCATED:'已截断',
    FILTERED:'已过滤/脱敏', BODY_NOT_VERIFIED:'待读取核验',
    NOT_CAPTURED:'正文不可用', CLIENT_ERROR:'模型调用失败', EXPIRED:'正文已过期', PENDING:'待读取核验',
    VIEW_NOT_GRANTED:'未获正文查看授权', READ_REJECTED:'正文读取被拒', READ_FAILED:'正文查看失败，原因未确认', REFERENCE_INVALID:'正文引用无效'})[state] || state || '待读取核验';
}
export function observationHtml(value, esc) {
  const labels = { LEGACY_UNASSESSED:'旧资产未声明', UNKNOWN:'尚未确认', READY:'启动检查通过',
    OPTIONAL_GAPS:'存在可选缺口', REQUIRED:'必需', OPTIONAL:'可选', AUTHORIZED_DIAGNOSTIC:'授权诊断',
    SUPPORTED:'支持', UNSUPPORTED:'不支持', VERIFIED:'已有验证记录', NOT_CHECKED:'未核验',
    DISABLED:'未启用', NOT_AUTHORIZED:'未获采集授权', CAPTURED_METADATA:'元数据已记录',
    INCOMPLETE:'记录不完整', FAILED:'采集不可用', UNVERIFIED:'尚未验证', MISMATCH:'声明不匹配' };
  const name = v => esc(labels[v] || v || '尚未确认');
  return `<details data-observation-requirements><summary>观测要求与本次记录 · ${name(value?.status || 'LEGACY_UNASSESSED')}</summary>
    <div class="history-table-wrap"><table class="history-table"><thead><tr><th>要求 / 采集边界</th><th>支持与验证</th><th>启动状态</th><th>本次记录</th></tr></thead><tbody>
    ${(value?.items || []).map(item => `<tr><td>${esc(item.semanticId)} · ${name(item.obligation)}<br>${esc(item.boundary)}</td>
      <td>SDK: ${name(item.sdkSupport)}<br>采集器: ${name(item.support)} / ${name(item.verification)}<br>${esc(item.collectorId)} @ ${esc(item.collectorVersion)}</td>
      <td>${name(item.startupReadiness)}</td><td>${name(item.actualCapture)}
      ${(item.missingFields || []).length ? `<small>缺少字段：${esc(item.missingFields.join(', '))}</small>` : ''}
      ${item.bodyVerification === 'NOT_CHECKED' ? '<small>正文摘要与内容尚未核验</small>' : ''}</td></tr>`).join('')}
    </tbody></table></div></details>`;
}
export function snapshotBody(value) {
  if (typeof value.body !== 'string') return value.body == null ? '正文不可用' : JSON.stringify(value.body, null, 2);
  try { return JSON.stringify(JSON.parse(value.body), null, 2); }
  catch { return value.body; }
}
export function snapshotSummary(value) {
  const names = { COLLECTION_LIMIT: '集合截断', PROPERTY_LIMIT: '属性截断', DEPTH_LIMIT: '深度限制',
    NODE_LIMIT: '节点数量限制', BYTE_LIMIT: '正文大小限制（字节）', REDACTED: '已脱敏',
    PYTHON_PROJECTION_LIMIT: '客户端采集大小或结构超限，正文已省略', PYTHON_PREFILTERED: '客户端已过滤或脱敏',
    FIELD_NOT_ALLOWLISTED: '字段未纳入采集', PROPERTY_NAME_FILTERED: '属性名已过滤',
    UNSUPPORTED_OBJECT: '对象未纳入采集', UNSUPPORTED_VALUE: '值未纳入采集' };
  const lines = [`采集状态：${snapshotState(value.state)}；采集不证明模型 HTTP 已发送。`];
  if (!Array.isArray(value.changes)) lines.push('处理明细未记录（旧格式或正文不可用），不能据此认定完整。');
  else if (!value.changes.length) lines.push('本次结构投影未记录过滤或截断。');
  else for (const change of value.changes) {
    const counts = Number.isFinite(change.originalCount) && Number.isFinite(change.retainedCount)
      ? `；原始 ${change.originalCount}，保留 ${change.retainedCount}` : '；数量未记录';
    lines.push(`${names[change.reason] || change.reason}：${change.path || '/'}${change.pathAbbreviated ? '（路径已缩短）' : ''}${counts}`);
  }
  if (value.changesOmitted > 0) lines.push(`另有 ${value.changesOmitted} 项处理明细未保存。`);
  if (value.reason) lines.push(`正文不可用：${value.reason}`);
  return lines.join('\n');
}
const resultLabels = {COMPLETED:'服务端完成',RETURNED:'调用已返回',ERROR:'执行异常',DENIED:'已拒绝',INTERRUPTED:'已中断',UNFINISHED:'结束未记录',INCOMPLETE:'记录不完整',CANCEL_REQUESTED:'请求取消',CANCELLED:'本地执行已取消'};
const failed = span => ['ERROR','DENIED','INTERRUPTED','INCOMPLETE','UNFINISHED'].includes(span.outcome)
  || Number(span.links?.httpStatus)>=400 || /^FAILED(?:_|$)/.test(span.links?.businessStatus||'') || ['ERROR','DENIED','REJECTED'].includes(span.links?.businessStatus);
const outcomeLabel = span => ['ERROR','DENIED','INTERRUPTED','INCOMPLETE','UNFINISHED'].includes(span.outcome)
  ? resultLabels[span.outcome]+(span.links?.businessStatus?' · '+spanStatus(span):'') : spanStatus(span);
const duration = value => Number.isFinite(value) ? (value/1e6).toFixed(1)+' ms' : '未记录';
const json = value => JSON.stringify(value,null,2);
export function snapshotReason(value){
  return ({BODY_QUEUE_REJECTED:'正文写入队列拒绝接收',BODY_PREPARATION_FAILED:'正文序列化或结构投影失败',
    BODY_WRITE_FAILED:'正文写入失败',BODY_STORAGE_QUOTA:'正文存储配额不足',
    BODY_UNAVAILABLE_OR_NOT_FLUSHED:'正文暂不可读取，具体原因未记录',BODY_DIGEST_MISMATCH:'正文摘要不匹配',
    BODY_RETENTION_EXPIRED:'正文已按保留策略过期',NO_SNAPSHOT_REFERENCE:'没有正文引用',
    SNAPSHOT_VIEW_NOT_GRANTED:'未获得正文查看授权',SNAPSHOT_UNAVAILABLE:'正文存储访问不可用',
    NO_REFERENCE:'没有正文引用',EXPIRED:'正文已过期',DIGEST_MISMATCH:'正文摘要不匹配',
    MISSING:'正文文件缺失',FILE_MISSING:'有写入回执，但正文文件缺失',UNAVAILABLE:'正文不可用，具体原因未记录',STORAGE_UNAVAILABLE:'正文存储不可用',WRITE_FAILED:'有明确正文写入失败记录'})[value]||value||'具体原因未记录';
}
function snapshotAvailability(value){
  const reason=value?.reason||value?.gapReason;
  if(value?.state==='EXPIRED'||reason==='BODY_RETENTION_EXPIRED'||reason==='EXPIRED')
    return '模型正文已按保留策略过期，当前无法还原当时内容。';
  if(reason==='BODY_DIGEST_MISMATCH'||reason==='DIGEST_MISMATCH')
    return '模型正文摘要不一致，为避免展示不可信内容，本次不提供正文。';
  if(['BODY_WRITE_FAILED','BODY_STORAGE_QUOTA','WRITE_FAILED'].includes(reason))
    return '有明确的模型正文写入失败记录，因此本次没有可查看的正文。';
  if(['FILE_MISSING','MISSING'].includes(reason))
    return '记录显示正文曾写入，但当前文件缺失，无法查看当时内容。';
  if(value?.state==='NOT_CAPTURED'||value?.body==null)
    return '这次没有可查看的模型正文记录，现有证据无法确认原因。';
  return '';
}
function snapshotAbsence(record){
  const item=record.observation?.items?.find(value=>value.semanticId==='model.client.snapshot');
  if(item?.support==='UNSUPPORTED')return '正文引用未记录；启动观测记录：采集不受支持。';
  if(item?.startupReadiness==='DISABLED')return '正文引用未记录；启动观测记录：采集未启用。';
  if(item?.startupReadiness==='NOT_AUTHORIZED')return '正文引用未记录；启动观测记录：未获采集授权。';
  return '这次没有可查看的模型正文记录，现有证据无法确认原因。';
}
function gapMessage(code){
  return ({MISSING_RUNTIME_LOCK:'缺少本次运行的安装版本记录，无法完整核对运行依赖。请检查该实例是否完成安装记录写入。',
    MISSING_SERVICE_LOCK:'缺少生成制品版本记录，无法完整核对本次运行资产。请检查制品 Lock 是否仍可读取。',
    RUNTIME_LOCK_DIGEST_UNVERIFIED:'安装版本记录摘要不一致，当前版本证据不能作为完整依据。',
    SERVICE_LOCK_DIGEST_UNVERIFIED:'生成制品版本记录摘要不一致，当前资产证据不能作为完整依据。',
    MISSING_TERMINAL_EVENT:'没有记录请求如何结束，无法确认最终执行状态。',
    OBSERVATION_DEGRADED:'部分执行事件未能保存，排查结论受记录完整性限制。',
    UNCLOSED_SPANS:'部分步骤没有结束记录，无法确认其持续时间和结果。',
    CORRUPT_OR_PARTIAL_RECORD:'记录内容不完整或无法解析，部分步骤可能不可见。'})[code]
    ||('存在记录缺口（'+code+'），请在技术信息中核对原始标识。');
}
function failureExplanation(span){
  if(/^FAILED(?:_|$)/.test(span.links?.businessStatus||'')||['ERROR','DENIED','REJECTED','CANCELLED'].includes(span.links?.businessStatus))
    return ['权威业务结果表明该步骤未成功完成。','本次请求不能据此认定业务成功。','检查业务状态、对应来源操作及关联标识。'];
  if(Number(span.links?.httpStatus)>=400)
    return ['来源接口返回错误状态。','相关数据或业务结果可能没有取得。','检查接口状态、来源操作和请求关联标识。'];
  if(span.outcome==='DENIED')return ['执行边界拒绝了该步骤。','受限数据或操作没有继续开放。','检查当前身份、实授权限和请求用途。'];
  if(span.outcome==='ERROR')return ['该步骤记录了执行异常。','后续结果可能缺失或请求已经结束。','检查异常类型及其父步骤的关联证据。'];
  return ['该步骤没有形成完整成功记录。','现有证据不足以确认该步骤完成。','检查结束状态、父子关系和记录完整性。'];
}
export function timelineGroups(spans=[]) {
  const counts=new Map();for(const span of spans)counts.set(span.spanId,(counts.get(span.spanId)||0)+1);
  const byId=new Map(spans.map(span=>[span.spanId,span]));
  const invalid=new Map();
  for(const span of spans){
    let current=span;const visited=new Set();
    while(current){
      if(!current.spanId||counts.get(current.spanId)!==1){invalid.set(span,'步骤标识缺失或重复');break;}
      if(visited.has(current.spanId)){invalid.set(span,'父关系存在循环');break;}
      visited.add(current.spanId);
      if(!current.parentSpanId)break;
      const parent=byId.get(current.parentSpanId);
      if(!parent){invalid.set(span,'父步骤未记录');break;}
      current=parent;
    }
  }
  const main=spans.filter(span=>!invalid.has(span)&&['model','runtime-tool'].includes(span.component));
  const groups=new Map(main.map(span=>[span.spanId,[]])), other=[];
  const root=spans.find(span=>!invalid.has(span)&&span.component==='host'&&span.operation==='request');
  for(const span of spans){
    if(invalid.has(span)){other.push(span);continue;}
    if(groups.has(span.spanId)||span===root)continue;
    let parent=span.parentSpanId,owner=null;const visited=new Set([span.spanId]);
    while(parent&&!visited.has(parent)){
      visited.add(parent);
      if(groups.has(parent)){owner=parent;break;}
      parent=byId.get(parent)?.parentSpanId;
    }
    (owner?groups.get(owner):other).push(span);
  }
  return {main:[...main].sort((a,b)=>(a.startNanos||0)-(b.startNanos||0)),groups,other,root,invalid};
}
export function latestSnapshotRefs(record){
  const refs=new Map();for(const ref of record.snapshots||[])refs.set(JSON.stringify([ref.callId,ref.part]),ref);
  return [...refs.values()];
}
export function recordedBusinessResults(record){
  if(Array.isArray(record.businessResults))return record.businessResults;
  return (record.events||[]).filter(event=>event.phase==='END'&&event.component==='capability'&&event.links?.businessStatus)
    .map(event=>({capabilityId:event.links.capabilityId,businessStatus:event.links.businessStatus}));
}
export function executedCapabilityIds(record){
  const ordered=[];
  const add=id=>{if(id&&!ordered.includes(id))ordered.push(id);};
  for(const span of [...(record.spans||[])].sort((a,b)=>(a.startNanos||0)-(b.startNanos||0)))
    if(span.component==='capability')add(span.links?.capabilityId);
  for(const result of recordedBusinessResults(record))add(result.capabilityId);
  return ordered;
}
export function executionNames(record,compact=false){
  const names=executedCapabilityIds(record).map(id=>record.display?.capabilities?.find(item=>item.capabilityId===id)?.title||id);
  if(names.length)return compact&&names.length>2?names.slice(0,2).join(' / ')+' · 另 '+(names.length-2)+' 项':names.join(' / ');
  const requestTypes={CONVERSATION_TURN:'对话请求',CONVERSATION_TURN_AG_UI:'对话请求',TASK_LIST:'办理任务查询',
    TASK_CHALLENGE:'办理补充验证',TASK_CONFIRM:'办理确认',TASK_DECLINE:'办理拒绝',TASK_RESUME:'办理恢复',TASK_STEP_UP:'加强验证'};
  return requestTypes[record.requestType]||record.requestType||'请求记录';
}
export function beijingDateTime(value){
  const parsed=new Date(value);if(!value||Number.isNaN(parsed.getTime()))return '未记录';
  const parts=new Intl.DateTimeFormat('zh-CN',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hourCycle:'h23'}).formatToParts(parsed);
  const get=type=>parts.find(item=>item.type===type)?.value;
  return `${get('year')}-${get('month')}-${get('day')} ${get('hour')}:${get('minute')}:${get('second')}`;
}
export function beijingDayRange(value){
  if(!/^\d{4}-\d{2}-\d{2}$/.test(value||''))return null;
  const start=new Date(value+'T00:00:00+08:00');if(Number.isNaN(start.getTime()))return null;
  const before=new Date(start.getTime()+86400000);
  return {createdFrom:start.toISOString(),createdBefore:before.toISOString()};
}
export function defaultHistorySpan(record){
  const layout=waterfallLayout(record),grouped=timelineGroups(record.spans||[]);
  const ordered=layout.rows.filter(span=>failed(span)).sort((a,b)=>(a.startNanos||0)-(b.startNanos||0));
  const nonRoot=ordered.filter(span=>span!==grouped.root);
  if(nonRoot.length)return nonRoot[0];
  if(ordered.length)return ordered[0];
  return grouped.main.filter(span=>Number.isFinite(span.durationNanos)).sort((a,b)=>b.durationNanos-a.durationNanos)[0]||null;
}
export function waterfallLayout(record){
  const spans=Array.isArray(record?.spans)?record.spans:[],grouped=timelineGroups(spans);
  const order={'host':0,'model':1,'runtime-tool':2,'capability':3,'authorization-http':4,'provider-http':5};
  const rows=spans.filter(span=>!grouped.invalid.has(span)&&Number.isFinite(span.startNanos)&&span.startNanos>=0)
    .map(span=>({...span,clockScope:span.clockScope||span.processBootId||record?.processBootId||'request',endNanos:Number.isFinite(span.durationNanos)?span.startNanos+span.durationNanos:null}))
    .sort((a,b)=>a.startNanos-b.startNanos||(order[a.component]??9)-(order[b.component]??9));
  const scopes=[...new Set(rows.map(span=>span.clockScope))].map(scope=>{
    const scopedRows=rows.filter(span=>span.clockScope===scope);
    return {scope,rows:scopedRows,maximum:Math.max(1,...scopedRows.map(span=>span.endNanos??span.startNanos))};
  });
  return {rows,scopes,maximum:scopes.length===1?scopes[0].maximum:null,invalid:[...grouped.invalid.entries()].map(([span,reason])=>({span,reason}))};
}
// HTML style attributes are blocked by the Studio's strict style-src policy.
// Set individual CSSOM properties after rendering; never interpret CSS text.
export function positionWaterfall(root=document){
  for(const node of root.querySelectorAll('.history-track [data-history-left]')){
    const left=Number(node.dataset.historyLeft);
    if(!Number.isFinite(left))continue;
    const boundedLeft=Math.min(100,Math.max(0,left));
    node.style.left=boundedLeft+'%';
    if(node.dataset.historyWidth!==undefined){
      const width=Number(node.dataset.historyWidth);
      if(Number.isFinite(width))node.style.width=Math.min(100-boundedLeft,Math.max(0,width))+'%';
    }
  }
}
export function snapshotCalls(record) {
  const groups=new Map();
  for(const ref of latestSnapshotRefs(record)){
    const key=JSON.stringify([ref.spanId||null,ref.callId]);
    if(!groups.has(key))groups.set(key,{key,spanId:ref.spanId,callId:ref.callId,refs:[]});
    groups.get(key).refs.push(ref);
  }
  for(const span of record.spans||[])if(span.component==='model'&&![...groups.values()].some(call=>call.spanId===span.spanId)){
    const key=JSON.stringify([span.spanId,null]);groups.set(key,{key,spanId:span.spanId,refs:[]});
  }
  const ordered=(record.spans||[]).filter(span=>span.component==='model').sort((a,b)=>(a.startNanos||0)-(b.startNanos||0));
  return [...ordered.flatMap(span=>[...groups.values()].filter(call=>call.spanId===span.spanId)),
    ...[...groups.values()].filter(call=>!ordered.some(span=>span.spanId===call.spanId))];
}
function readableValue(value,esc,depth=0){
  if(value==null)return '<span class="history-muted">未记录</span>';
  if(typeof value!=='object')return '<p class="history-body-text">'+esc(String(value))+'</p>';
  if(depth>5)return '<pre>'+esc(json(value))+'</pre>';
  if(Array.isArray(value))return '<ol class="history-body-list">'+value.map(item=>'<li>'+readableValue(item,esc,depth+1)+'</li>').join('')+'</ol>';
  return '<dl class="history-fields">'+Object.entries(value).map(([key,item])=>'<dt>'+esc(key)+'</dt><dd>'+readableValue(item,esc,depth+1)+'</dd>').join('')+'</dl>';
}
export function readableSnapshot(value,esc){
  let body=value.body;
  if(typeof body==='string'){try{body=JSON.parse(body);}catch{return '<p class="history-body-text">'+esc(body)+'</p>';}}
  if(body==null)return '<p>正文不可用</p>';
  if(typeof body!=='object')return readableValue(body,esc);
  const roles={system:'系统',developer:'开发者',user:'用户',assistant:'助手',tool:'工具结果'};
  const messages=Array.isArray(body.messages)?body.messages:body.role||'content'in body||'tool_calls'in body?[body]:Array.isArray(body.choices)?body.choices.map(choice=>choice.message).filter(Boolean):[];
  const messageHtml=messages.map(message=>!message||typeof message!=='object'?readableValue(message,esc):'<section class="history-message"><h4>'+esc(roles[message.role]||message.role||(value.part==='response'?'模型响应':'消息'))+'</h4>'+
    (message.name?'<p>'+esc(message.name)+'</p>':'')+
    (message.tool_call_id?'<p class="history-muted">调用引用：'+esc(message.tool_call_id)+'</p>':'')+
    (message.content!=null?readableValue(message.content,esc):'<p class="history-muted">未记录消息正文</p>')+
    (message.tool_calls?.length?'<h5>工具调用</h5>'+readableValue(message.tool_calls,esc):'')+'</section>').join('');
  const tools=Array.isArray(body.tools)?'<section><h4>工具定义</h4>'+body.tools.map(tool=>{
    if(!tool||typeof tool!=='object')return readableValue(tool,esc);
    const fn=tool.function||tool;
    return '<details class="history-tool-definition"><summary>'+esc(fn.name||'未记录工具名称')+'</summary>'+
      (fn.description?'<p>'+esc(fn.description)+'</p>':'')+readableValue(fn.parameters||fn,esc)+'</details>';
  }).join('')+'</section>':'';
  const parameters=Object.fromEntries(Object.entries(body).filter(([key])=>!['messages','tools','choices','role','content','tool_calls','tool_call_id','name'].includes(key)));
  return messageHtml+tools+(Object.keys(parameters).length?'<section><h4>已记录参数</h4>'+readableValue(parameters,esc)+'</section>':'')
    ||readableValue(body,esc);
}
export function executionHistory({api,esc,icon,render}){
  let records=[],selected=null,detailId=null,tab='process',busy=false,error='',unreadable=0,coverage=null,scanned=null;
  let generation=0,bodyGeneration=0,bodyBusy=false,bodyError='',bodyValue=null,activeCall=null,activePart='request';
  let cursors=[''],page=0,nextCursor=null,advanced=false,truncated=false;
  let eventPage=0,selectedSpanId=null;
  const verifiedBodies=new Map();
  let viewAuthorizationDenied=false;
  const cacheKey=(request,ref)=>JSON.stringify([request,ref.spanId||null,ref.callId,ref.part]);
  function cacheBody(key,value){verifiedBodies.delete(key);verifiedBodies.set(key,value);while(verifiedBodies.size>16)verifiedBodies.delete(verifiedBodies.keys().next().value);}
  function restoreViews(record){record.snapshots=latestSnapshotRefs(record);for(const ref of record.snapshots){const cached=verifiedBodies.get(cacheKey(record.requestId,ref));if(cached)ref.viewState=cached.state||'BODY_NOT_VERIFIED';else if(viewAuthorizationDenied)ref.viewState='VIEW_NOT_GRANTED';}return record;}
  const filters={date:'',buildId:'',status:'',subjectRef:'',sessionRef:'',requestId:''};
  const text=value=>esc(value??'未记录');
  const button=(attribute,value,label,disabled=false)=>'<button type="button" '+attribute+'="'+esc(value)+'" '+(disabled?'disabled':'')+'>'+label+'</button>';
  const raw=(label,value)=>'<details><summary>'+label+'</summary><pre>'+esc(json(value))+'</pre></details>';
  const date=beijingDateTime;
  const millis=value=>Number.isFinite(value)?value.toFixed(value>=100?0:1)+' ms':'未记录';
  const statusTone=value=>['ERROR','DENIED','INTERRUPTED','INCOMPLETE','UNFINISHED','CANCELLED'].includes(value)?'is-error':value==='COMPLETED'?'is-success':'is-neutral';
  function clientTiming(record){
    const value=record.clientTiming;
    if(!value)return '用户端耗时未记录';
    const parts=[];
    if(Number.isFinite(value.firstStatusMs))parts.push('首次状态 '+millis(value.firstStatusMs));
    if(Number.isFinite(value.bodyDisplayedMs))parts.push('正文显示 '+millis(value.bodyDisplayedMs));
    if(Number.isFinite(value.completedMs))parts.push('完成 '+millis(value.completedMs));
    return parts.length?parts.join(' · '):'用户端耗时未记录';
  }
  function invalidateBody(){bodyGeneration++;bodyBusy=false;bodyError='';bodyValue=null;}
  async function refresh(){
    if(detailId){return open(detailId,true);}
    const g=++generation;invalidateBody();busy=true;error='';render();
    try{
      const selectedFilters=Object.fromEntries(Object.entries(filters).filter(([key,value])=>key!=='date'&&value));
      const q=new URLSearchParams(selectedFilters);const range=beijingDayRange(filters.date);
      if(range)for(const [key,value] of Object.entries(range))q.set(key,value);q.set('limit','20');
      if(cursors[page])q.set('cursor',cursors[page]);
      const value=await api('/api/v1/execution-history?'+q);
      if(g!==generation)return;
      records=(value.records||[]).map(restoreViews);nextCursor=value.nextCursor||null;coverage=value.coverage;scanned=value.scanned;
      unreadable=value.unreadable||0;truncated=Boolean(value.truncated);
    }catch(e){if(g===generation){error=e.message;nextCursor=null;}}
    finally{if(g===generation){busy=false;render();}}
  }
  async function open(id,preserve=false){
    detailId=id;selected=null;if(!preserve){tab='process';activeCall=null;activePart='request';eventPage=0;selectedSpanId=null;}invalidateBody();
    const g=++generation;busy=true;error='';render();
    try{const value=await api('/api/v1/execution-history/'+encodeURIComponent(id));if(g===generation)selected=restoreViews(value);}
    catch(e){if(g===generation)error=e.message;}
    finally{if(g===generation){busy=false;render();}}
  }
  function back(){generation++;invalidateBody();busy=false;detailId=null;selected=null;error='';render();}
  function chooseCall(key,part='request'){
    invalidateBody();activeCall=key;activePart=part;render();
  }
  async function openSnapshot(callId,part,spanId){
    const record=selected,request=record?.requestId;
    const ref=record?.snapshots?.find(item=>item.callId===callId&&item.part===part&&(item.spanId||'')===(spanId||''));
    if(!request||!ref)return;
    invalidateBody();const g=bodyGeneration,ownerGeneration=generation;
    bodyBusy=true;bodyError='';render();
    try{
      const value=await api('/api/v1/execution-history/'+encodeURIComponent(request)+'/snapshots/'+encodeURIComponent(callId)+'/'+encodeURIComponent(part));
      if(g!==bodyGeneration||ownerGeneration!==generation||selected!==record)return;
      if((value.requestId&&value.requestId!==request)||(value.callId&&value.callId!==callId)||(value.spanId&&value.spanId!==ref.spanId)||(value.part&&value.part!==part))throw Error('正文关联不匹配，未展示返回内容。');
      ref.viewState=value.state||'BODY_NOT_VERIFIED';bodyValue=value;
      cacheBody(cacheKey(request,ref),value);
      for(const item of records)if(item.requestId===request)restoreViews(item);
    }catch(e){if(g===bodyGeneration&&ownerGeneration===generation){
      const code=e.data?.error?.code||e.data?.code;
      ref.viewState=code==='SNAPSHOT_VIEW_NOT_GRANTED'?'VIEW_NOT_GRANTED':e.status===403?'READ_REJECTED':e.status===400?'REFERENCE_INVALID':'READ_FAILED';
      bodyError=snapshotState(ref.viewState);
      if(code==='SNAPSHOT_VIEW_NOT_GRANTED'){
        verifiedBodies.clear();bodyValue=null;viewAuthorizationDenied=true;
        for(const item of [...records,record])for(const related of item.snapshots||[])related.viewState='VIEW_NOT_GRANTED';
      }
      if(e.status===403)for(const related of record.snapshots||[])if(related.callId===callId){
        related.viewState=ref.viewState;cacheBody(cacheKey(request,related),{state:ref.viewState});
      }
      cacheBody(cacheKey(request,ref),{state:ref.viewState});
      for(const item of records)if(item.requestId===request)restoreViews(item);
    }}
    finally{if(g===bodyGeneration&&ownerGeneration===generation){bodyBusy=false;render();}}
  }
  function collection(record){
    const refs=latestSnapshotRefs(record);
    if(!refs.length)return '未记录正文引用';
    if(refs.some(ref=>['UNAVAILABLE','FAILED','WRITE_FAILED'].includes(ref.storageResult?.state||ref.storage)||ref.gapReason==='WRITE_FAILED'||ref.storageReason))return '存在写入不可用记录';
    if(refs.some(ref=>['VIEW_NOT_GRANTED','READ_REJECTED','READ_FAILED','REFERENCE_INVALID'].includes(ref.viewState)))return '正文查看受限或失败';
    if(refs.some(ref=>['NOT_CAPTURED','EXPIRED','CLIENT_ERROR'].includes(ref.viewState||ref.state)))return '正文存在缺口';
    if(refs.some(ref=>!ref.viewState))return '正文待读取核验';
    return '正文状态已记录';
  }
  function listHtml(){
    const field=(name,label,type='text')=>'<label>'+label+'<input name="'+name+'" type="'+type+'" value="'+esc(filters[name])+'" maxlength="160"></label>';
    return '<div class="page-title-row"><h1>运行记录</h1>'+button('data-history-refresh','',''+icon('refresh-cw')+'刷新',busy)+'</div>'+
      '<form id="history-filters" class="history-filters">'+field('date','日期（北京时间）','date')+field('buildId','Agent ID')+
      '<label>执行结果<select name="status"><option value="">全部</option>'+Object.entries(resultLabels).map(([key,label])=>'<option value="'+key+'" '+(filters.status===key?'selected':'')+'>'+label+'</option>').join('')+'</select></label>'+
      '<button class="primary-button" type="submit" '+(busy?'disabled':'')+'>查询</button>'+
      '<details class="history-advanced" '+(advanced?'open':'')+'><summary>高级筛选</summary><div>'+field('subjectRef','授权主体引用')+field('sessionRef','会话引用')+field('requestId','请求 ID')+'</div></details></form>'+
      '<p class="history-muted">显示与日期筛选均采用北京时间 UTC+8。</p>'+
      (error?'<p role="alert">'+esc(error)+'</p>':'')+
      (unreadable?'<p role="status">'+text(unreadable)+' 条记录无法读取</p>':'')+
      (truncated&&!nextCursor?'<p role="status">结果已截断，但服务端未提供下一页游标；请缩小筛选范围后重试。</p>':'')+
      '<div class="history-list-surface"><div class="history-table-wrap" aria-busy="'+busy+'"><table class="history-table"><thead><tr><th>请求 / 时间</th><th>Agent</th><th>执行结果</th><th>证据情况</th><th></th></tr></thead><tbody>'+
      records.map(record=>'<tr><td><strong>'+text(executionNames(record,true))+'</strong><small>'+text(date(record.createdAt))+'</small><details><summary>请求标识</summary><code>'+text(record.requestId)+'</code></details></td><td>'+text(record.agentName||'执行时名称未记录')+
      '<small>'+text(record.buildId)+'</small></td><td><span class="history-pill '+statusTone(record.status)+'">'+text(resultLabels[record.status]||record.status)+'</span><small>服务端 '+duration(record.durationNanos)+'</small></td><td><span>执行链：'+(record.complete?'完整':'不完整')+'</span><small>'+text(collection(record))+'</small></td><td>'+
      button('data-history-id',record.requestId,'查看详情',busy)+'</td></tr>').join('')+
      (!records.length?'<tr><td colspan="5">'+(busy?'正在读取记录':'暂无符合条件的记录')+'</td></tr>':'')+'</tbody></table></div>'+
      '<footer class="history-pagination"><span>第 '+(page+1)+' 页 · 每页 20 条 · 当前 '+records.length+' 条</span><div>'+
      button('data-history-page','previous',icon('arrow-left')+'上一页',busy||page===0)+button('data-history-page','next','下一页'+icon('arrow-up-right'),busy||!nextCursor)+'</div></footer></div>'+
      '<details class="history-coverage"><summary>检索覆盖范围</summary>'+
      '<p>本次扫描：'+text(scanned)+'；分页结果不代表完整历史总量。'+(nextCursor?'还有下一页。':'')+'</p><p>检索为并发更新的活视图，不是冻结快照；晚写入的较早记录可能出现，首页上界之后的新记录不进入本轮分页。</p>'+readableValue(coverage,esc)+'</details>';
  }
  function step(span,record,gap){
    const [title,meaning]=describeSpan(span,record.spans,record.display);
    return '<div class="history-check '+(failed(span)?'is-failed':'')+'" data-history-span="'+esc(span.spanId)+'"><strong>'+text(title)+'</strong><span>'+duration(span.durationNanos)+'</span><span>'+text(outcomeLabel(span))+'</span>'+
      (gap?'<p class="history-tree-gap">层级缺口：'+text(gap)+'，不推测归属。</p>':'')+'<details><summary>记录依据</summary><p>'+text(meaning)+'</p>'+readableValue({spanId:span.spanId,parentSpanId:span.parentSpanId,component:span.component,operation:span.operation,...span.links},esc)+'</details></div>';
  }
  function processHtml(record){
    const grouped=timelineGroups(record.spans),layout=waterfallLayout(record);
    const chosen=layout.rows.find(span=>span.spanId===selectedSpanId)||defaultHistorySpan(record);
    selectedSpanId=chosen?.spanId||null;
    const rows=layout.scopes.map((scope,index)=>{
      const ticks=[0,.25,.5,.75,1].map(value=>'<span>'+duration(scope.maximum*value)+'</span>').join('');
      const bars=scope.rows.map(span=>{
        const [title]=describeSpan(span,record.spans,record.display),left=span.startNanos/scope.maximum*100;
        const width=Number.isFinite(span.durationNanos)?Math.max(0,span.durationNanos/scope.maximum*100):0;
        return '<button type="button" class="history-water-row '+(span.spanId===selectedSpanId?'is-selected ':'')+(failed(span)?'is-failed':'')+'" data-history-span-select="'+esc(span.spanId)+'" aria-pressed="'+(span.spanId===selectedSpanId)+'"><span class="history-water-label"><strong>'+text(title)+'</strong><small>'+text(outcomeLabel(span))+'</small></span><span class="history-track"><span class="history-hit" data-history-left="'+left+'" data-history-width="'+Math.max(width,.8)+'"></span>'+
          (Number.isFinite(span.durationNanos)?'<span class="history-bar history-bar-'+esc(span.component)+'" data-history-left="'+left+'" data-history-width="'+Math.max(width,.15)+'"></span><span class="history-duration" data-history-left="'+Math.min(87,left+width+1)+'">'+duration(span.durationNanos)+'</span>':'<span class="history-open-marker" data-history-left="'+left+'" title="结束未记录"></span><span class="history-duration" data-history-left="'+Math.min(87,left+1)+'">结束未记录</span>')+'</span></button>';
      }).join('');
      return (layout.scopes.length>1?'<h3 class="history-clock-scope">时钟域 '+(index+1)+' <small>'+text(scope.scope)+'</small></h3>':'')+'<div class="history-axis"><span></span><div>'+ticks+'</div></div><div class="history-waterfall">'+bars+'</div>';
    }).join('');
    const byId=new Map((record.spans||[]).map(span=>[span.spanId,span]));
    const related=chosen?(record.spans||[]).filter(span=>{let parent=span.parentSpanId,seen=new Set();while(parent&&!seen.has(parent)){if(parent===chosen.spanId)return true;seen.add(parent);parent=byId.get(parent)?.parentSpanId;}return false;}):[];
    const failedChecks=related.filter(failed),normalChecks=related.filter(span=>!failed(span));
    const [title,meaning]=chosen?describeSpan(chosen,record.spans,record.display):['未选择步骤','没有可显示的计时步骤'];
    const evidence=chosen?'<section class="history-evidence-card history-step-evidence"><h3>'+text(title)+'</h3><dl class="history-fields"><dt>实际结果</dt><dd>'+text(outcomeLabel(chosen))+'</dd><dt>计时边界</dt><dd>本请求单调时钟 +'+duration(chosen.startNanos)+'；持续 '+duration(chosen.durationNanos)+'</dd><dt>记录说明</dt><dd>'+text(meaning)+'</dd><dt>原始操作</dt><dd>'+text(chosen.component)+' / '+text(chosen.operation)+'</dd></dl>'+
      (chosen.component==='model'?button('data-history-model',chosen.spanId,'查看本次模型交互'):'')+
      '<details><summary>关联标识与原始步骤字段</summary>'+readableValue({spanId:chosen.spanId,parentSpanId:chosen.parentSpanId,dispatch:chosen.dispatch,...chosen.links},esc)+'</details></section>':'<section class="history-evidence-card"><p>没有可显示的步骤证据。</p></section>';
    const checks='<section class="history-evidence-card"><h3>关联检查与证据</h3>'+
      (failedChecks.length?'<div class="history-failed-checks">'+failedChecks.map(value=>step(value,record,grouped.invalid.get(value))).join('')+'</div>':'')+
      (normalChecks.length?'<details class="history-normal-checks"><summary>正常检查 '+normalChecks.length+' 项</summary>'+normalChecks.map(value=>step(value,record,grouped.invalid.get(value))).join('')+'</details>':'')+
      (!related.length?'<p class="history-muted">该步骤没有记录可归属的子检查。</p>':'')+
      '<p class="history-muted">失败检查直接显示；选中步骤只是当前排查入口，不自动判定根因。</p></section>';
    return '<section class="history-process-surface"><header class="history-section-head"><div><h2>执行耗时</h2><p>每个时钟域内统一刻度；不同进程不直接对齐</p></div><span>'+duration(record.durationNanos)+'</span></header>'+rows+
      '<p class="history-muted">点击区间查看证据。空白不解释为排队；嵌套与并行区间不能相加。短区间仅扩大点击范围，不放大时间条。</p></section>'+
      (!chosen?'<p class="history-empty-main">本次未记录模型或工具调用；全流程记录仍可在上方查看。</p>':'')+'<div class="history-evidence-split">'+evidence+checks+'</div>'+
      (layout.invalid.length?'<details class="history-unassigned" open><summary>层级或计时缺口 · '+layout.invalid.length+' 项</summary>'+layout.invalid.map(item=>step(item.span,record,item.reason)).join('')+'</details>':'')+
      (grouped.other.length?'<details class="history-unassigned" '+(grouped.other.some(failed)?'open':'')+'><summary>未归属检查与执行记录 · '+grouped.other.length+' 项</summary>'+grouped.other.map(value=>step(value,record,grouped.invalid.get(value))).join('')+'</details>':'');
  }
  function modelHtml(record){
    const calls=snapshotCalls(record);
    const call=calls.find(value=>value.key===activeCall)||calls[0];
    if(!call)return '<p>'+text(snapshotAbsence(record))+'</p>';
    activeCall=call.key;
    const refs=call.refs.filter(ref=>ref.part===activePart),ref=refs[0];
    const shown=bodyBusy?null:bodyValue||(ref?verifiedBodies.get(cacheKey(record.requestId,ref)):null);
    const storage=ref?.storageResult?.state||ref?.storage;
    const storageText=({WRITTEN:'有写入成功回执（正文仍需读取核验）',WRITE_FAILED:'有写入失败回执',QUEUED:'原记录已入队，不代表当前队列状态',UNAVAILABLE:'写入不可用'})[storage]||storage;
    const modelSpans=(record.spans||[]).filter(span=>span.component==='model').sort((a,b)=>(a.startNanos||0)-(b.startNanos||0));
    const linked=record.spans?.some(span=>span.component==='model'&&span.spanId===call.spanId);
    const boundary=activePart==='request'?'客户端准备请求':'SDK 解析响应';
    const dispatch=call.refs.find(item=>item.dispatch)?.dispatch||record.spans?.find(span=>span.spanId===call.spanId)?.dispatch;
    return '<section class="history-model-surface"><header class="history-section-head"><div><h2>模型交互</h2><p>'+text(record.modelContext?.modelName||record.modelContext?.modelRef||'模型名称未记录')+'</p></div><span>'+calls.length+' 次调用</span></header><div class="history-model-nav" aria-label="模型调用">'+calls.map(value=>{const index=modelSpans.findIndex(span=>span.spanId===value.spanId);const incomplete=!value.refs.some(item=>item.part==='request')||!value.refs.some(item=>item.part==='response');return '<button type="button" data-history-call="'+esc(value.key)+'" aria-pressed="'+(call.key===value.key)+'">'+(index>=0?'第 '+(index+1)+' 次':'未关联引用')+(incomplete?' · 正文不完整':'')+'</button>';}).join('')+'</div>'+
      '<p class="history-muted">'+(linked?'已关联到记录中的模型步骤。':'未找到匹配的模型步骤，保留原引用。')+' 这里只核对当时提供的内容与解析结果，不采集或展示隐藏思考。</p>'+
      '<div class="history-parts">'+['request','response'].map(part=>'<button type="button" data-history-part="'+part+'" aria-pressed="'+(part===activePart)+'">'+(part==='request'?'模型输入':'模型输出')+'</button>').join('')+'</div>'+
      '<div class="history-boundary-note"><strong>'+boundary+'</strong><span>'+(dispatch==='SENT'?'步骤记录为已发送，但没有独立网络发送时点。':dispatch==='NOT_SENT'?'本次记录明确标记为未发送。':'模型网络请求是否发送未独立观测。')+'</span></div>'+
      (ref?'<dl class="history-fields"><dt>采集状态</dt><dd>'+text(snapshotState(ref.state))+'</dd><dt>保存结果</dt><dd>'+text(storageText)+(ref.storageResult?.reason||ref.storageReason?' · '+text(snapshotReason(ref.storageResult?.reason||ref.storageReason)):'')+'</dd><dt>正文查看</dt><dd>'+text(bodyBusy?'正在读取':ref.viewState?snapshotState(ref.viewState):'尚未读取')+'</dd><dt>采集位置</dt><dd>'+text(ref.boundary||boundary)+'</dd></dl>'+
      button('data-snapshot-read','',''+(bodyBusy?'正在读取':'读取正文'),bodyBusy):'<p>'+text(snapshotAbsence(record))+'</p>')+
      (bodyError?'<p role="alert">'+esc(bodyError)+'</p>':'')+
      (shown?'<section class="snapshot-content"><p class="history-body-state">本次正文查看结果：'+text(snapshotState(shown.state))+'。</p>'+
        (snapshotAvailability(shown)?'<p>'+text(snapshotAvailability(shown))+'</p>':readableSnapshot(shown,esc))+
        '<details><summary>结构化原文与处理记录</summary><pre data-snapshot-summary>'+text(snapshotSummary(shown))+'</pre><pre data-snapshot-body>'+text(snapshotBody(shown))+'</pre>'+readableValue({evidence:shown.evidence,boundary:shown.boundary,dispatch:shown.dispatch},esc)+'</details></section>':'')+
      raw('采集引用与关联标识',call.refs)+'</section>';
  }
  function technicalHtml(record){
    return '<section class="history-technical-surface"><h2>技术与版本证据</h2><dl class="history-fields">'+Object.entries({requestId:record.requestId,buildId:record.buildId,instanceId:record.instanceId,processBootId:record.processBootId,subjectRef:record.subjectRef,sessionRef:record.sessionRef}).map(([key,value])=>'<dt>'+key+'</dt><dd>'+text(value)+'</dd>').join('')+'</dl>'+
      '<h3>计时口径</h3><p>SDK 调用区间不是模型推理耗时；嵌套步骤不累加。客户端计时单独记录，不是模型首字时间。</p>'+
      readableValue({executorWaitNanos:record.executorWaitNanos,executorWaitCoverage:record.executorWaitCoverage,clientTiming:record.clientTiming,modelDispatchCoverage:record.modelDispatchCoverage},esc)+
      '<p>模型 HTTP 发出：未提供独立发送时点证据。</p>'+
      '<h3>资产与运行环境</h3>'+raw('版本清单与模型配置证据',{versions:record.versions,modelContext:record.modelContext})+
      raw('已安装能力（不代表本次全部执行）',record.capabilities||[])+
      observationHtml(record.observation,esc)+
      '<p class="history-muted">'+(record.display?.state==='FROZEN'?'使用记录时的组件说明快照，摘要已核验。':record.display?.state==='UNAVAILABLE'?'组件说明缺失或摘要不符，保留技术标识。':'旧记录：使用已有兼容说明，未保存当时的说明快照。')+'</p>'+
      '<section class="history-events"><h3>执行事件</h3><p>第 '+(eventPage+1)+' 页 · 每页 50 条</p>'+readableValue((record.events||[]).slice(eventPage*50,eventPage*50+50),esc)+
      button('data-history-events','previous','上一页',eventPage===0)+button('data-history-events','next','下一页',(eventPage+1)*50>=(record.events||[]).length)+'</section>'+raw('记录缺口',record.gaps)+
      (record.recoveredAt?'<p>中断恢复时间：'+text(record.recoveredAt)+'；终止时刻未记录。</p>':'')+'</section>';
  }
  function detailHtml(){
    let html=button('data-history-back','',icon('arrow-left')+'返回运行记录')+
      '<div class="page-title-row"><h1>运行详情</h1>'+button('data-history-refresh','',icon('refresh-cw')+'刷新',busy)+'</div>';
    if(error)html+='<p role="alert">'+esc(error)+'</p>';
    if(!selected)return html+'<p role="status">'+(busy?'正在读取记录':'记录暂不可用')+'</p>';
    const record=selected,spans=record.spans||[];
    const tree=timelineGroups(spans);
    const longest=tree.main.filter(span=>Number.isFinite(span.durationNanos)).sort((a,b)=>b.durationNanos-a.durationNanos)[0];
    const business=recordedBusinessResults(record);
    const failures=spans.filter(failed);
    const businessText=business.length?business.map(result=>text(describeSpan({component:'capability',links:{capabilityId:result.capabilityId}},spans,record.display)[0])+' · '+text(spanStatus({links:{businessStatus:result.businessStatus}}))).join('；'):'未记录独立业务结果';
    const firstGap=snapshotCalls(record).find(call=>!call.refs.some(ref=>ref.part==='request')||!call.refs.some(ref=>ref.part==='response')||call.refs.some(ref=>['NOT_CAPTURED','EXPIRED','CLIENT_ERROR'].includes(ref.state)));
    html+='<article class="history-detail"><header class="history-record-heading"><div><h2>'+text(executionNames(record))+'</h2><p>'+text(record.agentName||'执行时名称未记录')+' · '+text(date(record.createdAt))+' · 北京时间 UTC+8</p></div></header>'+
      '<section class="history-overview"><dl class="history-summary"><div><dt>执行结果</dt><dd><strong>'+text(resultLabels[record.status]||record.status)+'</strong><small>'+businessText+'</small></dd></div><div><dt>服务端处理</dt><dd><strong>'+duration(record.durationNanos)+'</strong><small>'+text(clientTiming(record))+'</small></dd></div><div><dt>记录情况</dt><dd><strong>'+(record.complete?'执行链完整':'执行链不完整')+'</strong><small>'+text(collection(record))+'</small></dd></div></dl>'+
      '<div class="history-findings"><section class="history-finding '+(failures.length?'is-error':'')+'"><h3>'+(failures.length?'已确认问题':'异常情况')+'</h3>'+(failures.length?failures.sort((a,b)=>(a.startNanos||0)-(b.startNanos||0)).map(span=>{const note=failureExplanation(span);return '<div class="history-failure-summary"><strong>'+text(describeSpan(span,spans,record.display)[0])+' · '+text(outcomeLabel(span))+'</strong><p>'+text(note[0])+'</p><small>'+text(note[1])+' '+text(note[2])+'</small>'+button('data-history-locate',span.spanId,'定位失败步骤')+'</div>';}).join(''):'<p>未记录明确执行异常；现有记录不足以证明业务回答质量。</p>')+'</section>'+
      '<section class="history-finding"><h3>耗时关注点</h3><p>'+(longest?text(describeSpan(longest,spans,record.display)[0])+'为最长已记录主区间，'+duration(longest.durationNanos)+'。':'没有可比较的模型或工具主区间。')+'</p>'+(longest?button('data-history-locate',longest.spanId,'定位耗时步骤'):'')+'<small>最长区间不是根因，嵌套和并行耗时不相加。</small></section>'+
      '<section class="history-finding '+((record.gaps||[]).length||firstGap?'is-gap':'')+'"><h3>证据缺口</h3>'+
      ((record.gaps||[]).length?(record.gaps||[]).map(code=>'<p>'+text(gapMessage(code))+'</p>').join(''):'<p>执行链未报告结构缺口。</p>')+
      '<small>'+text(collection(record))+'。</small>'+(firstGap?button('data-history-gap-model',firstGap.key,'查看模型正文情况'):'')+'</section></div></section>'+
      '<div class="history-tabs" role="tablist" aria-label="记录详情">'+[['process','执行过程'],['model','模型交互'],['technical','技术信息']].map(([id,label])=>'<button type="button" role="tab" id="history-tab-'+id+'" aria-controls="history-panel" data-history-tab="'+id+'" aria-selected="'+(tab===id)+'">'+label+'</button>').join('')+'</div>'+
      '<section id="history-panel" role="tabpanel" aria-labelledby="history-tab-'+tab+'">'+(tab==='process'?processHtml(record):tab==='model'?modelHtml(record):technicalHtml(record))+'</section></article>';
    return html;
  }
  function html(){return '<section class="execution-history">'+(detailId?detailHtml():listHtml())+'</section>';}
  function click(event){
    const node=event.target.closest('[data-history-id],[data-history-back],[data-history-refresh],[data-history-page],[data-history-tab],[data-history-model],[data-history-call],[data-history-part],[data-snapshot-read],[data-history-events],[data-history-locate],[data-history-span-select],[data-history-gap-model]');
    if(!node||node.disabled)return;
    const d=node.dataset;
    if(d.historyId)return open(d.historyId);
    if('historyBack'in d)return back();
    if('historyRefresh'in d)return refresh();
    if(d.historyEvents){eventPage=Math.max(0,eventPage+(d.historyEvents==='next'?1:-1));render();return;}
    if(d.historyLocate){tab='process';selectedSpanId=d.historyLocate;invalidateBody();render();document.querySelector('[data-history-span-select="'+CSS.escape(d.historyLocate)+'"]')?.scrollIntoView({block:'center'});return;}
    if(d.historySpanSelect){selectedSpanId=d.historySpanSelect;render();return;}
    if(d.historyGapModel){tab='model';return chooseCall(d.historyGapModel);}
    if(d.historyPage){
      if(busy)return;
      if(d.historyPage==='next'&&nextCursor){cursors=cursors.slice(0,page+1);cursors.push(nextCursor);page++;}
      else if(d.historyPage==='previous'&&page>0)page--;else return;
      records=[];
      return refresh();
    }
    if(d.historyTab){invalidateBody();tab=d.historyTab;render();return;}
    if(d.historyModel){tab='model';chooseCall(snapshotCalls(selected).find(call=>call.spanId===d.historyModel)?.key);return;}
    if(d.historyCall)return chooseCall(d.historyCall);
    if(d.historyPart)return chooseCall(activeCall,d.historyPart);
    if('snapshotRead'in d){
      const call=snapshotCalls(selected).find(value=>value.key===activeCall),ref=call?.refs.find(value=>value.part===activePart);
      if(ref)return openSnapshot(ref.callId,ref.part,ref.spanId);
    }
  }
  function submit(event){
    if(event.target.id!=='history-filters')return;event.preventDefault();
    const values=new FormData(event.target);for(const key of Object.keys(filters))filters[key]=String(values.get(key)||'');
    advanced=Boolean(event.target.querySelector('.history-advanced')?.open);page=0;cursors=[''];nextCursor=null;records=[];return refresh();
  }
  return {html,refresh,click,submit,layout:positionWaterfall};
}
