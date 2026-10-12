import assert from 'node:assert/strict';
import {readFileSync, mkdirSync} from 'node:fs';
import {createServer} from 'node:http';
import {build} from 'esbuild';

const bundle=await build({entryPoints:['browser/business-assets.js'],bundle:true,write:false,format:'esm'});
const helpers=await import('data:text/javascript;base64,'+Buffer.from(bundle.outputFiles[0].text).toString('base64'));
const {sameAsset,assetRef,componentEntries,bindingCapabilities,bindingModules,catalogCapabilities,packChoices,businessAssetsView}=helpers;
const java='openjiuwen-java-0.1.15',python='openjiuwen-python-0.1.18';
const ref=id=>({id,version:'1.0.0'});
const entry=(id,label,ids,operation='QUERY',alternatives=false)=>({pack:ref(id),label,
  capabilities:ids.map(capabilityId=>({capabilityId,operation})),providers:alternatives?[ref('bank-a'),ref('bank-b')]:[ref('bank-a')],
  runtimeIds:operation==='TASK'||id==='context'?[java]:[java,python],methodCustomizationSupported:operation==='QUERY'&&id!=='context'});
const entries=[entry('balance','账户余额',['account.balance.query']),entry('wealth','财富事实',['wealth.facts.describe']),
  entry('context','财富客户上下文',['wealth.customer.context']),entry('cashflow','收支分析',['finance.transaction-summary.query','finance.spending-analysis.query']),
  entry('internal-transfer','行内转账',['transfer.internal.create'],'TASK'),entry('alternative','可选来源业务',['other.query'],'QUERY',true)];
const components=entries.map(value=>({id:'component-'+value.pack.id,label:value.label,version:value.pack.version,description:value.label,
  scope:'本人合成数据',status:'AVAILABLE',domain:'banking',icon:'wallet',capabilityIds:value.capabilities.map(c=>c.capabilityId),
  packBindings:[{pack:value.pack,...(value.providers.length===1?{provider:value.providers[0]}:{providers:value.providers})}],
  capabilityPackRefs:[value.pack],providerRefs:value.providers,domainPackRefs:[],tools:value.capabilities,
  runtimeIds:value.runtimeIds,methodCustomizationSupported:value.methodCustomizationSupported,profileRef:ref('profile')}));
const modules=[['agent.account',[0]],['agent.wealth_aggregate',[1,2]],['agent.finance_assistant',[3]],['agent.transfer',[4]],['agent.ungrouped',[5]]]
  .map(([id,indexes])=>({id,label:indexes.map(i=>entries[i].label).join('与'),componentIds:indexes.map(i=>components[i].id),
    scope:'本人合成数据',status:'PARTIAL',icon:'wallet',source:'builder://catalog',sourceOperations:[]}));
const defaults=entries.slice(0,2).map(value=>({pack:value.pack,provider:value.providers[0]}));
const template=(id,label,bindings=[])=>({id,label,status:'PARTIAL',icon:'landmark',moduleIds:modules.map(m=>m.id),
  featuredModuleIds:modules.map(m=>m.id),defaultPackBindings:bindings,defaultModuleIds:[],runtimeIds:[java,python]});
const directory={revision:'business-revision',components,domains:[{id:'banking',label:'银行业务'}]};
const businessDomains=[
  {id:'customer-management',label:'客户经营',description:'客户经营',icon:'user-round-check',order:1},
  {id:'business-processing',label:'业务办理',description:'业务办理',icon:'landmark',order:2},
  {id:'credit-approval',label:'信贷授信',description:'信贷授信',icon:'file-check-2',order:3},
  {id:'operations',label:'运营作业',description:'运营作业',icon:'clipboard-check',order:4},
  {id:'risk-compliance',label:'风险合规',description:'风险合规',icon:'shield-check',order:5},
  {id:'research-decision',label:'投研决策',description:'投研决策',icon:'chart-pie',order:6},
];
const moduleDomainAssignments=[
  {moduleId:'agent.account',businessDomainId:'business-processing',relatedBusinessDomainIds:['customer-management']},
  {moduleId:'agent.finance_assistant',businessDomainId:'business-processing',relatedBusinessDomainIds:['customer-management']},
  {moduleId:'agent.transfer',businessDomainId:'business-processing',relatedBusinessDomainIds:['risk-compliance']},
  {moduleId:'agent.wealth_aggregate',businessDomainId:'customer-management',relatedBusinessDomainIds:[]},
  {moduleId:'agent.ungrouped',businessDomainId:'operations',relatedBusinessDomainIds:[]},
];
const taskBlueprints=[
  {id:'account-cashflow-service',label:'账户与收支服务',businessDomainId:'business-processing',relatedBusinessDomainIds:['customer-management'],moduleIds:['agent.account','agent.finance_assistant'],packIds:['balance','cashflow'],channelIds:['mobile-banking'],legacyTemplateId:'mobile-banking',status:'PARTIAL',kind:'BUSINESS_TASK'},
  {id:'transfer-service',label:'转账办理服务',businessDomainId:'business-processing',relatedBusinessDomainIds:[],moduleIds:['agent.transfer'],packIds:['internal-transfer'],channelIds:['mobile-banking'],legacyTemplateId:'mobile-banking',status:'PARTIAL',kind:'BUSINESS_TASK'},
  {id:'wealth-holding-service',label:'财富持有期服务',businessDomainId:'customer-management',relatedBusinessDomainIds:[],moduleIds:['agent.wealth_aggregate'],packIds:['wealth','context'],channelIds:['mobile-banking'],legacyTemplateId:'mobile-banking',status:'PARTIAL',kind:'BUSINESS_TASK'},
];
const moduleDirectory={revision:'template-revision',version:'0.1',components,modules,
  businessDomains,moduleDomainAssignments,taskBlueprints,channels:[{id:'mobile-banking',label:'手机银行',kind:'CUSTOMER_CHANNEL'}],
  templates:[template('mobile-banking','手机银行',defaults),template('second','另一模板'),template('third','其他模板'),template('customer-custom','自定义空白')],
  runtimes:[{id:java,label:'Java',language:'Java',version:'0.1.15',status:'AVAILABLE'},{id:python,label:'Python',language:'Python',version:'0.1.18',status:'AVAILABLE'}]};
const capabilities=catalogCapabilities([{id:'account.balance.query',label:'账户余额'},
  {id:'finance.transaction-summary.query',label:'收支摘要'},{id:'finance.spending-analysis.query',label:'收支分析'}],entries);
assert.equal(sameAsset(undefined,undefined),false);
assert.deepEqual(assetRef({...ref('x'),label:'Ignored'}),ref('x'));
assert.equal(componentEntries([components[1],components[2]],entries).length,2);
assert.deepEqual(bindingCapabilities([{pack:ref('cashflow')}],entries),['finance.spending-analysis.query','finance.transaction-summary.query']);
assert.deepEqual(bindingModules([{pack:ref('context')}],moduleDirectory),['agent.wealth_aggregate']);
assert.equal(capabilities.find(c=>c.id==='transfer.internal.create').label,'行内转账');
const esc=value=>String(value??'').replaceAll('&','&amp;').replaceAll('"','&quot;').replaceAll('<','&lt;').replaceAll('>','&gt;');
const html=packChoices([components[5]],entries,[{pack:ref('alternative')}],{esc,capabilities,moduleId:'agent.ungrouped'});
assert(html.includes('data-pack-id="alternative"')&&html.includes('data-pack-detail="alternative"')&&!html.includes('data-pack-provider'));
assert(!html.includes('value="0"'));
const taskView=businessAssetsView({directory,capabilities,selected:components[4].id,query:'',domain:'',esc,icon:()=>''});
assert(taskView.includes('用于创建 Agent')&&!taskView.includes('data-method-action="customize-business"'));
const readonlyView=businessAssetsView({directory,capabilities,selected:components[0].id,query:'',domain:'',esc,icon:()=>''});
assert(readonlyView.includes('data-method-action="customize-business"'));
console.log('10 unified catalog unit checks passed');
if(process.argv.includes('--unit-only'))process.exit(0);

const {chromium}=await import(process.env.PLAYWRIGHT_MODULE||'playwright');
const workbench=await build({entryPoints:['browser/workbench.js'],bundle:true,write:false,format:'iife'});
const styles=['examples/agent-experience/style.css','browser/terminal.css','browser/method-assets.css','browser/industry-standards.css','browser/execution-history.css','browser/studio-navigation.css'].map(path=>readFileSync(path,'utf8')).join('\n');
const catalog={directory,unifiedDirectory:directory,unifiedTemplateModuleDirectory:moduleDirectory,unifiedManagedDirectory:{entries},
  managedDirectory:{entries:entries.map(e=>({...e,label:'WRONG LEGACY LABEL',runtimeIds:[]}))},
  modelCatalogRevision:'models-r1',capabilities:capabilities.slice(0,4),models:[{modelRef:'test-model',displayName:'测试模型',provider:'test',bindingStatus:'CONFIGURED_UNTESTED'}]};
const method={assetId:'readonly-method',version:'1.0.0',releaseDigest:'digest',name:'只读私有方法',capabilityIds:['account.balance.query']};
const selection={schema_version:'0.8',runtimeId:java,capabilityIds:['account.balance.query'],packBindings:[{pack:ref('balance'),provider:ref('bank-a')}],
  selectedModelRefs:['test-model'],defaultModelRef:'test-model',presentation:'MOBILE_WEB'};
const record=(id,changes)=>({buildId:id,buildStatus:'SUCCEEDED',selection:{...selection,...changes},stages:[],artifact:{artifactId:id},instance:{status:'NOT_STARTED'},modelSelection:{selectedModelRefs:['test-model'],defaultModelRef:'test-model'}});
const records=[record('old-explicit',{}),record('old-missing',{schema_version:'0.6',packBindings:undefined}),
  record('old-removed',{packBindings:[{pack:{id:'retired-pack',version:'0.2'},provider:ref('original-bank')}]}),
  record('old-provider',{packBindings:[{pack:ref('balance'),provider:{id:'original-bank',version:'0.2'}}]}),
  record('old-method',{methodRef:{assetId:'removed-method',version:'0.1',releaseDigest:'original'}})];
let missingCatalog=false,requests=[],rejectPreflight=false;
const server=createServer(async(req,res)=>{
  let data='';for await(const chunk of req)data+=chunk;
  if(req.method==='POST')requests.push({url:req.url,body:JSON.parse(data)});
  res.setHeader('Content-Type','application/json');
  if(req.url==='/api/bootstrap')return res.end(JSON.stringify({csrf:'test'}));
  if(req.url==='/api/v1/catalog')return res.end(JSON.stringify(missingCatalog?{...catalog,unifiedDirectory:undefined,unifiedTemplateModuleDirectory:undefined,unifiedManagedDirectory:undefined}:catalog));
  if(req.url==='/api/v1/connections')return res.end(JSON.stringify({connections:[{kind:'BANK',configurationStatus:'CONFIGURED_UNTESTED'}]}));
  if(req.url==='/api/v1/assets')return res.end(JSON.stringify({assets:[{assetId:method.assetId,status:'ACTIVE',versions:[method]}]}));
  if(req.url==='/api/v1/builds')return res.end(JSON.stringify({builds:records}));
  if(req.url==='/api/v1/builds/five-stage/optimization-view')return res.end(JSON.stringify({status:'not-executed',nextAction:{label:'先运行 Candidate Evaluation',reason:'当前 Build 尚未绑定评测与优化记录。'}}));
  if(req.url==='/api/v1/builds/five-stage')return res.end(JSON.stringify(records.find(record=>record.buildId==='five-stage')));
  if(req.url==='/api/v1/preflight'){
    if(rejectPreflight){res.statusCode=400;return res.end(JSON.stringify({error:{code:'COMPONENT_SELECTION_REJECTED'}}));}
    return res.end(JSON.stringify({canBuild:false}));
  }
  if(req.url?.startsWith('/api/'))return res.end(JSON.stringify({status:'UNAVAILABLE'}));
  const content=req.url==='/app.js'?workbench.outputFiles[0].text:req.url==='/terminal.css'?styles:
    (req.url==='/'||req.url?.startsWith('/?'))?readFileSync('examples/local-workbench/index.html','utf8'):
    ['/style.css','/catalog.css','/live.css','/icons.svg'].includes(req.url)?readFileSync('examples/local-workbench'+req.url):'';
  res.setHeader('Content-Type',req.url?.endsWith('.js')?'application/javascript':req.url?.endsWith('.css')?'text/css':req.url?.endsWith('.svg')?'image/svg+xml':'text/html');
  res.end(content);
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const url=`http://127.0.0.1:${server.address().port}`;
const browser=await chromium.launch({headless:true,...(process.env.CHROMIUM_PATH?{executablePath:process.env.CHROMIUM_PATH}:{})});
const output=process.env.TEST_OUTPUT||'/tmp/finance-unified-browser-results';mkdirSync(output,{recursive:true});
let checks=0;
try{
  const page=await browser.newPage({viewport:{width:1440,height:1000}}),errors=[];
  page.on('pageerror',e=>errors.push(e.message));page.on('dialog',d=>d.accept());
  const check=(condition,message)=>{assert(condition,message);checks++;};
  const load=async()=>{await page.goto(url);await page.locator('[data-action="build"]').waitFor();};
  const open=async module=>{await page.locator(`[data-module-detail="${module}"]`).click();};
  const close=async()=>{await page.locator('#drawer .drawer-close').click();};
  const save=async()=>{await page.locator('#drawer [data-module-confirm]').click();};
  const toggle=async(module,pack,on)=>{await open(module);await page.locator(`#drawer input[data-pack-id="${pack}"]`).setChecked(on);check(await page.locator('#drawer').isVisible(),'drawer remains open');await save();};
  const preflight=async()=>{const n=requests.length;await page.locator('[data-action="build"]').click();await page.waitForFunction(()=>document.querySelector('.live-error'));check(requests.length===n+1,'only preflight dispatched');return requests.at(-1).body;};
  await load();
  check(await page.locator('[data-assembly-mode]').count()===0,'no assembly modes');
  check(await page.locator('.business-module input[type="checkbox"]').count()===0,'module cards have no checkboxes');
  check(await page.locator('.module-detail-button').count()===0,'no ambiguous info icons');
  check(await page.locator('#module-page-size').count()===0,'page size selector removed');
  check(await page.locator('[data-business-domain]').count()===6,'six business domains replace channel template navigation');
  check(await page.locator('[data-scenario]').count()===0,'legacy mobile banking template is not a first-level choice');
  check(await page.locator('[data-task-blueprint]').count()===2,'product domain exposes verified task blueprints');
  check(await page.locator('[data-module-card][role="button"]').count()===2,'selected task modules use whole-card buttons');
  check(await page.locator('.module-choice-button').count()===0,'card hint is not a nested button');
  await page.locator('[data-module-card="agent.account"]').focus();await page.keyboard.press('Enter');
  check(await page.locator('#drawer').isVisible(),'keyboard opens whole card');await close();
  await page.locator('[data-task-blueprint="transfer-service"]').click();
  await open('agent.transfer');
  check(await page.locator('[data-pack-id="internal-transfer"]').isChecked(),'transfer task pins declared pack');
  await page.locator('[data-pack-id="internal-transfer"]').uncheck();
  check((await page.locator('[data-module-card="agent.transfer"]').getAttribute('class')).includes('is-selected'),'draft does not alter committed card');
  await page.locator('[data-pack-detail="internal-transfer"]').click();
  check(await page.locator('#drawer summary').filter({hasText:'能力包信息'}).count()===1,'detail uses package information');
  check(await page.locator('#drawer').innerText().then(t=>t.includes('未附带专业方法')),'no fabricated SOP');
  await page.locator('[data-module-back]').first().click();
  check(!await page.locator('[data-pack-id="internal-transfer"]').isChecked(),'detail return preserves draft');
  await close();await open('agent.transfer');
  check(await page.locator('[data-pack-id="internal-transfer"]').isChecked(),'cancel discards draft');await close();
  await page.locator('[data-business-domain="customer-management"]').click();
  await open('agent.wealth_aggregate');
  check(await page.locator('[data-pack-id="wealth"]').isChecked(),'original wealth default');
  check(await page.locator('[data-pack-id="context"]').isChecked(),'wealth task pins both declared packs');await close();
  let body=await preflight();
  check(body.schema_version==='0.9'&&body.directoryRevision==='template-revision','schema 0.9 uses template revision');
  check(body.packBindings.length===2&&body.packBindings.some(b=>b.pack.id==='context'),'task blueprint pins explicit packs');
  await page.locator('[data-custom-composition]').click();
  check(await page.locator('[data-action="build"]').isDisabled(),'empty custom composition is blocked');
  await page.locator('[data-business-domain="business-processing"]').click();
  await toggle('agent.transfer','internal-transfer',true);
  check(await page.locator('.builder-preview').count()===0,'no permanent right preview');
  await page.locator('[data-action="enlarge-preview"]').click();
  check((await page.locator('#drawer').innerText()).includes('已选业务能力')&&!(await page.locator('#drawer').innerText()).includes('尚未选择业务能力'),'generic preview for nonempty capabilities without samples');
  await close();
  check(!await page.locator('[data-action="build"]').isDisabled(),'Java TASK can preflight');
  await page.locator(`[data-runtime="${python}"]`).check();
  check(await page.locator('[data-action="build"]').isDisabled()&&(await page.locator('.build-blockers').innerText()).includes('不支持所选框架'),'Python blocked explicitly');
  await open('agent.transfer');check(await page.locator('[data-pack-id="internal-transfer"]').isChecked(),'TASK selection preserved');await close();
  await page.locator(`[data-runtime="${java}"]`).check();
  body=await preflight();check(body.packBindings[0].pack.id==='internal-transfer'&&body.moduleIds.includes('agent.transfer'),'TASK routed by component membership');
  await toggle('agent.transfer','internal-transfer',false);
  await toggle('agent.account','balance',true);
  await open('agent.account');await page.locator('[data-pack-detail="balance"]').click();
  await page.locator('[data-method-choice="balance"]').selectOption('readonly-method@1.0.0');
  await page.locator('[data-module-back]').first().click();await save();
  await toggle('agent.transfer','internal-transfer',true);
  check(await page.locator('[data-action="build"]').isDisabled()&&(await page.locator('.build-blockers').innerText()).includes('私有 SOP'),'TASK plus private blocked');
  await toggle('agent.transfer','internal-transfer',false);
  body=await preflight();check(body.methodRef.assetId===method.assetId,'readonly private supported');
  await page.locator('[data-business-domain="customer-management"]').click();
  await toggle('agent.wealth_aggregate','context',true);
  check(await page.locator('[data-action="build"]').isDisabled()&&(await page.locator('.build-blockers').innerText()).includes('暂不支持与私有 SOP'),'non-customizable QUERY plus private blocked');
  await page.locator('[data-business-domain="business-processing"]').click();
  await page.locator('[data-custom-composition]').click();
  await toggle('agent.finance_assistant','cashflow',true);
  body=await preflight();check(body.capabilityIds.length===2&&body.packBindings.length===1,'multi capability pack atomic');
  await page.locator('[data-business-domain="operations"]').click();
  await page.locator('[data-custom-composition]').click();
  await toggle('agent.ungrouped','alternative',true);
  check(await page.locator('[data-action="build"]').isDisabled(),'ambiguous provider requires explicit choice');
  await open('agent.ungrouped');await page.locator('[data-pack-detail="alternative"]').click();await page.locator('#drawer summary').filter({hasText:'能力包信息'}).click();await page.locator('[data-pack-provider="alternative"]').selectOption(JSON.stringify(ref('bank-b')));await page.locator('[data-module-back]').first().click();await save();
  body=await preflight();check(body.packBindings[0].provider.id==='bank-b','explicit provider preserved');
  rejectPreflight=true;await preflight();check((await page.locator('.live-error').innerText()).includes('能力包组合或来源引用'),'Chinese rejected message');rejectPreflight=false;
  for(const id of ['old-explicit','old-missing','old-removed','old-provider','old-method']){
    await page.locator('[data-nav="agents"]').click();await page.locator(`[data-edit="${id}"]`).click();
    if(id==='old-explicit'){body=await preflight();check(JSON.stringify(body.packBindings)===JSON.stringify(selection.packBindings),'legacy explicit refs preserved');}
    else{check(await page.locator('[data-action="build"]').isDisabled(),`${id} blocked`);check(await page.locator('.edit-context').count()===1,'new draft retains old context');}
  }
  check(!requests.some(r=>r.url.includes('/delete')||r.url.includes('/save')),'old artifacts never modified');
  await page.locator('[data-nav="assets"]').first().click();
  check(!(await page.locator('.business-asset-grid').innerText()).includes('transfer.internal.create'),'Chinese business labels');
  await page.locator('[data-method-action="business-detail"][data-component="component-internal-transfer"]').click();
  const contentAlert=page.getByRole('alert').filter({hasText:'ASSET_CONTENT_RESPONSE_UNAVAILABLE'});
  await contentAlert.waitFor();
  check((await contentAlert.innerText()).includes('未用其他版本替代'),'unavailable contents fail closed without breaking asset actions');
  check(await page.locator('[data-method-action="customize-business"]').count()===0,'TASK no SOP customize');
  await page.locator('[data-method-action="use-business"]').click();
  await open('agent.transfer');check(await page.locator('[data-pack-id="internal-transfer"]').isChecked(),'business entry selects same pack');await close();
  for(const width of [1440,1024,390,320]){
    await page.setViewportSize({width,height:900});await load();
    check(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),`${width} page no overflow: ${JSON.stringify(await page.evaluate(()=>[...document.querySelectorAll('body *')].filter(e=>getComputedStyle(e).visibility!=='hidden'&&e.getBoundingClientRect().right>innerWidth+1).slice(0,8).map(e=>({tag:e.tagName,class:e.className,right:e.getBoundingClientRect().right}))))}`);
    await page.screenshot({path:`${output}/${width}-create.png`,fullPage:true});
    await page.locator('[data-business-domain="customer-management"]').click();
    await open('agent.wealth_aggregate');
    check(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),`${width} drawer no overflow`);
    check(await page.locator('.pack-choice').evaluateAll(nodes=>nodes.every(n=>n.scrollWidth<=n.clientWidth+1)),`${width} pack choices fit`);
    await page.screenshot({path:`${output}/${width}-packs.png`,fullPage:true});await close();
  }
  missingCatalog=true;await load();
  check(await page.locator('[data-action="build"]').isDisabled()&&(await page.locator('.build-blockers').innerText()).includes('统一能力目录暂不可用'),'missing unified catalog fails closed');
  await page.locator('[data-action="list"]').click();check(await page.locator('[data-edit="old-explicit"]').count()===1,'old records remain readable');
  missingCatalog=false;
  records.push({...record('five-stage',{}),stages:[
    'harness_contracts','harness_tests_install','candidate_package','component_installation','observation_admission'
  ].map(id=>({id,status:'SUCCEEDED',elapsedMillis:100}))});
  await page.goto(`${url}/?agent=five-stage`);
  await page.locator('[data-stage="observation_admission"]').waitFor();
  check(await page.locator('.build-stage').count()===5,'all five completed stages render');
  check((await page.locator('[data-stage="component_installation"]').innerText()).includes('组件安装准入'),'installation stage named');
  check(await page.locator('[data-action="launch"]').isEnabled(),'completed build has an available launch action');
  check(errors.length===0,JSON.stringify(errors));
  console.log(`${checks} unified creation browser checks passed; screenshots ${output}`);
}finally{await browser.close();await new Promise(resolve=>server.close(resolve));}
