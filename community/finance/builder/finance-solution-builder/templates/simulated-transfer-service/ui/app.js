const $ = s => document.querySelector(s);
let csrf = sessionStorage.getItem('transfer-csrf') || '', pending = null, epoch = 0;
function status(text) { $('#status').textContent = text; }
async function api(path, body = {}) {
  const started = epoch;
  const response = await fetch('api/' + path, {method:'POST', headers:{'Content-Type':'application/json','X-CSRF-Token':csrf}, body:JSON.stringify(body)});
  const data = await response.json();
  if (!response.ok) {
    if (data.clearConversation) $('#messages').replaceChildren();
    if (response.status===403 && ['AUTHORIZATION_REJECTED','INSUFFICIENT_SCOPES'].includes(data.error)) {
      epoch++;pending=null;$('#confirmation').close();$('#tasks').replaceChildren();$('#installation').textContent='';$('#identity').textContent='';
      document.querySelectorAll('select').forEach(s=>s.replaceChildren());
    }
    const errors={STEP_UP_REQUIRED:'请先完成身份验证；身份验证不代表交易确认。',MODEL_NOT_CONFIGURED:'模型连接未配置，不能进行模型对话。',INSUFFICIENT_SCOPES:'当前授权范围不足。',AUTHORIZATION_REJECTED:'当前身份或授权已失效，请重新核验。',CONFIRMATION_EXPIRED_OR_CHANGED:'确认已过期或原任务已变化，请重新核对。',HOST_RESULT_UNAVAILABLE_QUERY_ORIGINAL_TASK:'本次结果无法确认，请查询原任务，不要重复准备交易。'};
    throw Error(errors[data.error] || data.error || '请求未完成，请查证原任务');
  }
  if(started!==epoch)throw Error('授权上下文已变化，已丢弃旧响应。');
  return data;
}
function showSession() { $('#login').hidden=true; $('#workspace').hidden=false; $('#logout').hidden=false; }
function fields(node, values) {
  node.replaceChildren();
  for (const [key,value] of Object.entries(values)) { const dt=document.createElement('dt'),dd=document.createElement('dd'); dt.textContent=key;dd.textContent=String(value);node.append(dt,dd); }
}
function button(text, action) { const b=document.createElement('button');b.textContent=text;b.onclick=()=>run(b,action);return b; }
async function run(button, action) { button.disabled=true;try {await action();} catch(e){status(e.message);}finally{button.disabled=false;} }
$('#chat-form').addEventListener('submit',()=>status(''));
async function refresh() {
  const data=await api('state');showSession();$('#installation').textContent=JSON.stringify({buildId:data.buildId,installation:data.installation},null,2);
  $('#tasks').replaceChildren();
  for(const task of data.tasks){
    const row=document.createElement('article'), title=document.createElement('h3'),dl=document.createElement('dl'),actions=document.createElement('div');
    title.textContent=task.terminal.errorCode==='HOST_DECLINED'?'已拒绝授权':({COMPLETED:'已完成',REJECTED:'已拒绝'}[task.terminal.status] || (task.awaitingConfirmation?'待核对确认':'待查证原任务'));
    fields(dl,{'付款账户':task.parameters.fromAccountId || '未提供','收款人':task.parameters.beneficiaryId || '未提供','金额 CNY':task.parameters.amount ?? '未提供','任务':task.handle});
    actions.className='actions';actions.append(button('查询原任务',async()=>{const r=await api('resume',{handle:task.handle});status(r.content || JSON.stringify(r));await refresh();}));
    if(task.awaitingConfirmation){actions.append(button('核对并确认',async()=>{
      pending=await api('challenge',{handle:task.handle});fields($('#summary'),{'付款账户':pending.parameters.fromAccountId,'收款人':pending.parameters.beneficiaryId,'金额 CNY':pending.parameters.amount,'用途':pending.parameters.purpose || '未填写'});
      $('#expiry').textContent='确认有效至 '+pending.expiresAt;$('#confirmation').showModal();
    }),button('拒绝授权',async()=>{await api('decline',{handle:task.handle});await refresh();}));}
    row.append(title,dl,actions);$('#tasks').append(row);
  }
}
async function choices(){
  const data=await api('choices');
  const accounts=Array.isArray(data.accounts)?data.accounts:data.accounts.accounts;
  for(const [name,items,id,label] of [['fromAccountId',(accounts||[]).filter(a=>a.currency==='CNY'&&a.status==='NORMAL'),'accountId',a=>`${a.accountName} · ${a.availableBalance} CNY`],['beneficiaryId',(data.beneficiaries||[]).filter(a=>a.internal&&a.status==='ACTIVE'),'beneficiaryId',a=>`${a.nameMasked} · ${a.accountNumberMasked}`]]){
    const select=$(`[name="${name}"]`);select.replaceChildren(new Option('请选择',''));for(const item of items)select.add(new Option(label(item),item[id]));
  }
}
$('#login-form').onsubmit=e=>{e.preventDefault();run(e.submitter,async()=>{const body=Object.fromEntries(new FormData(e.target));const data=await api('login',body);e.target.reset();csrf=data.csrf;sessionStorage.setItem('transfer-csrf',csrf);$('#identity').textContent=data.customer;showSession();await choices();await refresh();status('登录成功');});};
$('#step-form').onsubmit=e=>{e.preventDefault();run(e.submitter,async()=>{const data=await api('step-up',Object.fromEntries(new FormData(e.target)));e.target.reset();status('身份验证完成：'+data.identityStrength);});};
$('#transfer-form').onsubmit=e=>{e.preventDefault();run(e.submitter,async()=>{const body=Object.fromEntries(new FormData(e.target));const result=await api('prepare',body);status(result.content||JSON.stringify(result));await refresh();});};
$('#approve').onclick=()=>run($('#approve'),async()=>{if(!pending)return;const data=await api('confirm',{handle:pending.handle,nonce:pending.nonce});$('#confirmation').close();pending=null;status(data.content||JSON.stringify(data));await refresh();await choices();});
$('#chat-form').onsubmit=e=>{e.preventDefault();run(e.submitter,async()=>{const body=Object.fromEntries(new FormData(e.target));const user=document.createElement('p');user.textContent=body.query;$('#messages').append(user);$('#messages').scrollTop=$('#messages').scrollHeight;e.target.reset();const result=await api('chat',body);const answer=document.createElement('div');answer.className='markdown';answer.innerHTML=FinanceMarkdown.renderMarkdown(result.content);$('#messages').append(answer);$('#messages').scrollTop=$('#messages').scrollHeight;await refresh();});};
$('#refresh').onclick=()=>run($('#refresh'),refresh);
$('#logout').onclick=()=>run($('#logout'),async()=>{await api('logout');sessionStorage.removeItem('transfer-csrf');location.reload();});
if(csrf)refresh().then(choices).catch(e=>status(e.message));
