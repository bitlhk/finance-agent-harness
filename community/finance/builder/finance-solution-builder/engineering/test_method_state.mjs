import assert from 'node:assert/strict';
import {build} from 'esbuild';
const bundle=await build({entryPoints:['browser/method-assets.js'],bundle:true,write:false,format:'esm'});
const {methodAssets}=await import('data:text/javascript;base64,'+Buffer.from(bundle.outputFiles[0].text).toString('base64'));
const asset=id=>({assetId:id,revision:1,contentDigest:'digest-'+id,versions:[],validation:{status:'VALID',issues:[]},
  data:{name:'METHOD-'+id,maintainer:'',content:'old-'+id,resources:{},requiredResources:[],capabilityIds:['wealth.facts.describe'],cases:[]}});
const trial=id=>({status:'COMPLETED',trial:{trialId:'trial-'+id,revision:1,contentDigest:'digest-'+id,report:{status:'PASS'}}});
const event=(action,assetId)=>({target:{closest:()=>({dataset:{methodAction:action,asset:assetId}})}});
let calls=[],pending=null,timers=[];
globalThis.confirm=()=>true;
globalThis.setTimeout=callback=>{timers.push(callback);};
async function api(path,body){
  calls.push({path,body});
  if(pending?.matches(path,body))return new Promise(resolve=>{pending.resolve=resolve;});
  if(path==='/api/v1/assets')return {assets:[]};
  const id=path.split('/')[4];
  if(path.endsWith('/trial'))return body?{status:'QUEUED'}:trial(id);
  if(path.endsWith('/preview'))return {status:'NOT_RUN'};
  return asset(id);
}
const view=methodAssets({api,esc:String,icon:()=>'',markdown:String,rerender:()=>{},models:()=>[],choose:()=>{},refreshChoices:()=>{}});
await view.act(event('open','A'));
view.input({target:{dataset:{methodField:'content'},value:'edited-A'}});
await view.act(event('publish'));
assert(!calls.some(c=>c.path.endsWith('/publish')));
assert(view.html().includes('请先保存当前修改'));
view.leave();await view.act(event('open','A'));
await view.act(event('trial'));
pending={matches:(path,body)=>path.endsWith('/A/trial')&&!body};
const polling=timers.shift()();
await view.act(event('back'));await view.act(event('open','B'));
pending.resolve({...trial('A'),error:'STALE-A-ERROR'});await polling;pending=null;
assert(view.html().includes('METHOD-B'));
assert(!view.html().includes('STALE-A-ERROR'));
view.input({target:{dataset:{methodField:'content'},value:'saved-first'}});
pending={matches:(path)=>path.endsWith('/B/save')};
const saving=view.act(event('save'));
view.input({target:{dataset:{methodField:'content'},value:'typed-during-save'}});
pending.resolve({...asset('B'),revision:2});await saving;pending=null;
assert(view.html().includes('typed-during-save'));
assert(view.html().includes('保存期间还有新修改'));
assert(view.html().includes('未保存'));
let renders=0;
const focusView=methodAssets({api,esc:String,icon:()=>'',markdown:String,rerender:()=>{renders++;},models:()=>[],choose:()=>{},refreshChoices:()=>{}});
await focusView.act(event('open','A'));renders=0;
for(const key of ['query','version']){
  const target={dataset:{methodMeta:key},value:key==='query'?'new preview question':'1.2.3',hasAttribute:()=>false};
  focusView.input({target});await focusView.change({target});
}
assert.equal(renders,0,'Text-field blur must not replace the preview/publish button during its first click');
assert(focusView.html().includes('new preview question'));
assert(focusView.html().includes('1.2.3'));
console.log('4 method state checks passed: dirty publish, stale poll, edits during save, text blur preserves action target');

let lifecycleCalls=[], saved=0;
const lifecycleApi=async(path,body)=>{
  lifecycleCalls.push({path,body});
  if(path==='/api/v1/assets'&&!body)return {assets:[]};
  if(path.endsWith('-preview'))return {data:asset('preview').data};
  if(body?.data){saved++;return {...asset('saved'),data:body.data};}
  if(path.endsWith('/trial')||path.endsWith('/preview'))return {status:'NOT_RUN'};
  return asset('saved');
};
const edit=methodAssets({api:lifecycleApi,esc:String,icon:()=>'',markdown:String,rerender:()=>{},models:()=>[],choose:()=>{},refreshChoices:()=>{}});
await edit.act(event('create'));
assert.equal(lifecycleCalls.length,0,'New editor must not create a persisted draft');
await edit.act(event('back'));
assert.equal(saved,0,'Discarded new editor must not leave an empty asset');
await edit.act(event('copy-balance'));
assert.equal(lifecycleCalls.at(-1).path,'/api/v1/assets/example-preview');
assert.equal(saved,0);
edit.input({target:{dataset:{methodField:'content'},value:'Edited example'}});
await edit.act(event('save'));
assert.equal(saved,1);
assert(lifecycleCalls.some(c=>c.path.endsWith('/copy-default')&&c.body.data.content==='Edited example'));
await edit.act(event('back'));
const file={name:'SKILL.md',size:10,arrayBuffer:async()=>new TextEncoder().encode('# Imported').buffer};
await edit.change({target:{files:[file],hasAttribute:name=>name==='data-method-import',dataset:{}}});
assert.equal(saved,1);
assert.equal(lifecycleCalls.at(-1).path,'/api/v1/assets/import-preview');
await edit.act(event('save'));
assert.equal(saved,2);
assert(lifecycleCalls.some(c=>c.path.endsWith('/import')&&c.body.filename==='SKILL.md'&&c.body.data));
console.log('6 new draft lifecycle checks passed: new, discard, example preview, example save, import preview, import save');

const detailsBundle=await build({entryPoints:['browser/capability-details.js'],bundle:true,write:false,format:'esm'});
const {capabilityDetails,capabilityChoice}=await import('data:text/javascript;base64,'+Buffer.from(detailsBundle.outputFiles[0].text).toString('base64'));
const detailsInput={id:'component-a',label:'Component',version:'3.2.1',scope:'Read only',capabilityIds:['balance'],
  capabilityPackRefs:[{id:'balance-pack',version:'2.4.0'}],domainPackRefs:[],providerRefs:[{id:'bank-provider',version:'7.0'}],
  tools:[{capabilityId:'balance',toolName:'balance_query',operation:'QUERY'}],profileRef:{id:'customer',version:'1.0'},
  verification:{contract:'INSPECTED'}};
const escape=value=>String(value??'').replaceAll('<','&lt;').replaceAll('>','&gt;');
const detailHtml=capabilityDetails([detailsInput],{esc:escape,runtime:{label:'Python + Java bridge',version:'0.1.18'},
  connection:'simulator',method:{assetId:'private-method',version:'2.0.0',name:'<script>bad</script>',capabilityIds:['balance']}});
assert(detailHtml.includes('balance-pack @ 2.4.0')&&detailHtml.includes('bank-provider @ 7.0'));
assert(detailHtml.includes('Python + Java bridge')&&detailHtml.includes('private-method @ 2.0.0'));
assert(detailHtml.includes('无独立 Domain Pack 声明')&&!detailHtml.includes('<script>'));
assert(detailHtml.includes('待新制品 Lock 核验'));
console.log('4 component detail checks passed: original references, selected runtime/method, absent pack/escaping, installation boundary');

const choiceItem={id:'agent.finance_assistant',tools:[
  {capabilityId:'summary',toolName:'raw_summary',operation:'QUERY'},
  {capabilityId:'analysis',toolName:'raw_analysis',operation:'QUERY'}]};
const choiceOptions={esc:escape,capabilities:[{id:'summary',label:'收支摘要',description:'指定月份'},
  {id:'analysis',label:'收支分类与月度数据',description:'六个月来源数据'}],checked:true,enabled:true,required:false};
const choiceParts=[{label:'收支分析组合',description:'按组合安装'}];
const choice=capabilityChoice(choiceItem,choiceParts,choiceOptions);
assert.equal((choice.match(/type="checkbox"/g)||[]).length,1);
assert(choice.includes('收支摘要')&&choice.includes('收支分类与月度数据'));
assert(!choice.includes('raw_summary')&&!choice.includes('QUERY'));
choiceOptions.capabilities[0].label='新版业务名称';
assert(capabilityChoice(choiceItem,choiceParts,choiceOptions).includes('新版业务名称'));
assert(capabilityDetails([{...detailsInput,tools:[{...detailsInput.tools[0],requiredScopes:['cashflow:read']}]}],
  {esc:escape}).includes('cashflow:read'));
console.log('5 business presentation checks passed: grouped selection, catalog names, hidden identifiers, metadata update, technical scopes');

const guided=methodAssets({api:lifecycleApi,esc:escape,icon:()=>'',markdown:String,rerender:()=>{},models:()=>[],choose:()=>{},refreshChoices:()=>{}});
await guided.act(event('create'));
assert(guided.html().includes('保存草稿')&&!guided.html().includes('data-method-field="content"'));
assert(guided.html().includes('method-steps')&&guided.html().includes('无需配置真实模型'));
await guided.act(event('mode-edit'));
assert(guided.html().includes('data-method-field="content"'));
await guided.act(event('step-check'));
assert(guided.html().includes('data-stage="check"')&&guided.html().includes('不影响保存草稿和 Java 受控检查'));
await guided.act(event('step-publish'));
assert(guided.html().includes('data-stage="publish"')&&guided.html().includes('先保存当前草稿，并完成该版本的执行检查'));
assert(guided.html().includes('已有 Agent 不自动更新'));
console.log('5 guided method checks passed: reading default, editable source, no-model guidance, three stages, frozen release boundary');

let writes=0, finishSave;
let failSave=false;
const nextApi=async(path,body)=>{
  if(body?.data){writes++;if(failSave)throw Error('SAVE_FAILED');return new Promise(resolve=>{finishSave=()=>resolve({...asset('next'),data:body.data});});}
  if(path==='/api/v1/assets')return {assets:[]};
  if(path.endsWith('/package-plan'))return {bindings:[{capabilityId:'wealth.facts.describe',toolName:'wealth_facts_describe',capabilityPack:{id:'wealth-facts',version:'0.1.0'},operation:'QUERY',protocol:'finance-provider-spi.v1',inputSchemaRef:'capability-pack://wealth-facts@0.1.0/wealth.facts.describe/input',outputSchemaRef:'schema://capability-result.v1'}],requirements:{runtimeFamilies:['openjiuwen'],runtimeLanguages:['java'],execution:['QUERY'],requiredScopes:['wealth:facts:read'],observation:['TOOL_CALL']},evidence:{runtimeVerified:true,professionalQuality:'NOT_EVALUATED'}};
  if(path.endsWith('/trial'))return body?{status:'QUEUED'}:trial('next');
  if(path==='/api/v1/assets/next')return asset('next');
  return {status:'NOT_RUN'};
};
let nextRenders=0;
const next=methodAssets({api:nextApi,esc:escape,icon:()=>'',markdown:String,rerender:()=>{nextRenders++;},models:()=>[],choose:()=>{},refreshChoices:()=>{}});
await next.act(event('create'));
assert(next.html().includes('保存草稿并继续'));
await next.act(event('step-check'));
assert.equal(writes,0,'Step navigation must not silently save');
await next.act(event('step-edit'));
const beforeContinuing=nextRenders;
let continuing=next.act(event('continue-check'));
assert(nextRenders>beforeContinuing,'Pending async actions must immediately render disabled controls');
assert(/data-method-action="save"[^>]*disabled/.test(next.html()));
await next.act(event('continue-check'));
assert.equal(writes,1,'Repeated next while saving must not duplicate writes');
assert(next.html().includes('data-stage="edit"'),'Do not advance before save succeeds');
finishSave();await continuing;
assert(next.html().includes('data-stage="check"'));
await next.act(event('trial'));
await timers.shift()();
await next.act(event('continue-publish'));
assert.equal(writes,1,'Saved drafts advance without saving again');
assert(next.html().includes('data-stage="publish"')&&next.html().includes('本次将发布的组件边界'));
await next.act(event('step-edit'));
next.input({target:{dataset:{methodField:'content'},value:'new content'}});
failSave=true;
await next.act(event('continue-check'));
assert(next.html().includes('SAVE_FAILED')&&next.html().includes('data-stage="edit"'));
failSave=false;
continuing=next.act(event('continue-check'));
next.input({target:{dataset:{methodField:'content'},value:'later edit'}});
finishSave();await continuing;
assert(next.html().includes('later edit')&&next.html().includes('data-stage="edit"'));
assert(next.html().includes('保存期间还有新修改'));
console.log('6 explicit continuation checks passed: browse, duplicate, awaited save, saved next, failure, concurrent edit');

const released={assetId:'source',version:'1.0.0',releaseDigest:'frozen-digest',businessPackageReady:true};
const forkCalls=[];
const forkApi=async(path,body)=>{
  forkCalls.push({path,body});
  if(path==='/api/v1/assets')return {assets:[]};
  if(path.endsWith('/contents'))return {schemaVersion:'finance-asset-contents.v0.1',kind:'PUBLISHED_PACKAGE',name:'固定版本',version:'1.0.0',identity:released,files:[],bindings:[],dependencies:[]};
  if(path.endsWith('/packages/1.0.0/frozen-digest'))return {filename:'frozen.zip',base64:'c3R1Yg=='};
  if(path.endsWith('/package-preview'))return {data:{...asset('snapshot').data,content:'FROM_FROZEN_ZIP'},validation:{status:'VALID',issues:[]},source:{kind:'PACKAGE_IMPORTED',releaseDigest:'frozen-digest'},dependencyStatus:{status:'ADMISSIBLE'}};
  if(path.endsWith('/package-import'))return {...asset('new'),data:body.data};
  if(path.endsWith('/trial')||path.endsWith('/preview'))return {status:'NOT_RUN'};
  return {...asset('source'),versions:[released],data:{...asset('source').data,content:'UNPUBLISHED_WORK_IN_PROGRESS'}};
};
const fork=methodAssets({api:forkApi,esc:escape,icon:()=>'',markdown:String,rerender:()=>{},models:()=>[],choose:()=>{},refreshChoices:()=>{}});
const forkEvent=(action,extra={})=>({target:{closest:selector=>selector==='[data-method-action]'?{dataset:{methodAction:action,...extra}}:null}});
await fork.act(forkEvent('open',{asset:'source'}));
await fork.act(forkEvent('package-contents',{version:'1.0.0',digest:'frozen-digest'}));
await fork.act(forkEvent('fork-version',{version:'1.0.0',digest:'frozen-digest'}));
assert(fork.html().includes('FROM_FROZEN_ZIP')&&!fork.html().includes('UNPUBLISHED_WORK_IN_PROGRESS'));
assert(fork.html().includes('未保存'));
await fork.act(forkEvent('save'));
assert(forkCalls.some(c=>c.path.endsWith('/package-import')&&c.body.data.content==='FROM_FROZEN_ZIP'));
assert(!forkCalls.some(c=>c.path.endsWith('/source/save')||c.path.endsWith('/publish')));
console.log('4 fixed-version draft checks passed: snapshot body, unsaved, new import, no source overwrite or publication');
