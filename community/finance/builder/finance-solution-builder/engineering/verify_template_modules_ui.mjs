import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE || 'playwright');
const [base, output, mode = 'metadata'] = process.argv.slice(2);
fs.mkdirSync(output, {recursive:true});
const checks = [], errors = [], builds = [], turns = [];
let running = null;
const check = (name, ok) => { assert.ok(ok, name); checks.push(name); };
const browser = await chromium.launch({headless:true});
const context = await browser.newContext({viewport:{width:1440,height:1050},
  ...(process.env.STUDIO_TEST_USER ? {httpCredentials:{username:process.env.STUDIO_TEST_USER,password:process.env.STUDIO_TEST_PASSWORD}} : {})});
const page = await context.newPage();
await page.addInitScript(() => {
  window.__studioStreams=[];
  const original=window.fetch.bind(window);
  window.fetch=async(...args)=>{const response=await original(...args);
    if(response.url.endsWith('/ag-ui')) response.clone().text().then(body=>window.__studioStreams.push({status:response.status,body}));
    return response;
  };
});
page.on('pageerror', e => errors.push(e.message));
async function request(route, body) {
  return page.evaluate(async ({route,body}) => {
    const boot = await fetch('/api/bootstrap').then(r=>r.json());
    const response = await fetch(route, {method:body ? 'POST':'GET',headers:{'Content-Type':'application/json','X-CSRF-Token':boot.csrf},body:body ? JSON.stringify(body):undefined});
    return {status:response.status, body:await response.json()};
  }, {route,body});
}
try {
  await page.goto(base);
  await page.locator('[data-module="agent.account"]').waitFor();
  const cat = (await request('/api/v1/catalog')).body;
  check('no_old_audience_or_double_entry', await page.locator('[data-audience],[data-directory-view],[data-cap]').count() === 0);
  check('default_eight_business_modules', await page.locator('[data-module-card]').count() === 8);
  check('only_two_partial_modules_selectable', await page.locator('[data-module]:enabled').count() === 2 && await page.locator('[data-module]:checked').count() === 2);
  check('framework_default_actual_openjiuwen', await page.locator('[data-runtime="openjiuwen-java-0.1.15"]').isChecked());
  await page.locator('[data-action="expand-modules"]').click();
  check('all_26_ids_match_source_catalog', JSON.stringify((await page.locator('[data-module-card]').evaluateAll(ns=>ns.map(n=>n.dataset.moduleCard))).sort()) === JSON.stringify(cat.templateModuleDirectory.modules.map(m=>m.id).sort()));
  await page.screenshot({path:path.join(output,'all-26.png'),fullPage:true});
  await page.locator('#directory-search').fill('安全');
  check('search_pending_security_not_governance_switch', await page.locator('[data-module-card]').count() === 1 && await page.locator('[data-module="agent.security_service"]').isDisabled());
  check('search_preserves_hidden_selection', (await page.locator('#module-count').innerText()).includes('2 / 26'));
  await page.locator('#directory-search').fill('');
  await page.locator('[data-module-detail="agent.account"]').click();
  check('module_details_distinguish_source_operations', (await page.locator('#drawer').innerText()).includes('交易明细') && (await page.locator('#drawer').innerText()).includes('account_balance_query'));
  await page.keyboard.press('Escape');
  await page.locator('[data-action="expand-modules"]').click();
  await page.locator('[data-runtime="openjiuwen-python"]').check();
  check('pending_framework_blocks_build_without_fallback', await page.locator('[data-action="build"]').isDisabled() && await page.locator('[data-runtime="openjiuwen-python"]').isChecked());
  await page.screenshot({path:path.join(output,'openjiuwen-python-pending.png'),fullPage:true});
  await page.locator('[data-runtime="openjiuwen-java-0.1.15"]').check();
  await page.locator('[data-module="agent.account"]').uncheck();
  await page.locator('[data-module="agent.wealth_aggregate"]').uncheck();
  check('empty_selection_blocks_build', await page.locator('[data-action="build"]').isDisabled());
  await page.locator('[data-scenario="wealth-assistant"]').click();
  check('pending_template_has_no_invented_modules', await page.locator('[data-module-card]').count() === 0 && await page.locator('[data-action="build"]').isDisabled());
  await page.locator('[data-action="more-templates"]').click();
  await page.locator('[data-pending-scenario="loan-review"]').click();
  check('more_templates_preserve_pending_boundary', await page.locator('[data-module-card]').count() === 0 && await page.locator('[data-action="build"]').isDisabled());
  await page.locator('[data-scenario="mobile-banking"]').click();
  await page.locator('[data-catalog="models"]').click();
  check('models_come_from_real_catalog', await page.locator('[data-pick]').count() === cat.models.length);
  const available = page.locator('[data-pick]:enabled');
  if (await available.count() > 1) {
    const refs = await available.evaluateAll(ns=>ns.slice(0,2).map(n=>n.dataset.pick));
    for (const ref of refs) await page.locator(`[data-pick="${ref}"]`).check();
    await page.locator(`input[name="default-model"][value="${refs[1]}"]`).check();
    await page.locator('#catalog-save').click();
    check('multiple_models_and_explicit_default', (await page.locator('[data-catalog="models"]').innerText()).includes(cat.models.find(m=>m.modelRef===refs[1]).displayName));
  } else await page.keyboard.press('Escape');
  await page.locator('[data-action="bank-picker"]').click();
  check('connection_picker_not_navigation', await page.locator('dialog input[name="bank"]:checked').count() === 1 && await page.locator('[data-module-card]').count() === 8);
  await page.keyboard.press('Escape');
  const ref = cat.models.find(m=>!['DISABLED','PROTOCOL_UNSUPPORTED'].includes(m.bindingStatus)).modelRef;
  const body = {extension_type:'finance-workbench.selection',schema_version:'0.5',runtimeId:'openjiuwen-java-0.1.15',applicationTemplateId:'banking-readonly-web-v1',bankConnectionId:'local-simulator',scenarioTemplateId:'mobile-banking',moduleIds:['agent.account'],capabilityIds:['account.balance.query'],directoryRevision:cat.templateModuleDirectory.revision,selectedModelRefs:[ref],defaultModelRef:ref,modelCatalogRevision:cat.modelCatalogRevision};
  for (const [name,change,code] of [['framework',{runtimeId:'openjiuwen-python'},'RUNTIME_NOT_SUPPORTED'],['module',{moduleIds:['agent.transfer']},'MODULE_UNAVAILABLE'],['template',{scenarioTemplateId:'wealth-assistant'},'TEMPLATE_UNAVAILABLE'],['mismatch',{moduleIds:['agent.wealth_aggregate']},'MODULE_SELECTION_MISMATCH']]) {
    const result=await request('/api/v1/builds',{...body,...change});
    check('real_api_rejects_'+name,result.status===409 && result.body.error.code===code);
  }
  for (const width of [1440,1024,390,320]) {
    await page.setViewportSize({width,height:1050});
    check(width+'_page_fits',await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
    check(width+'_cards_and_labels_fit',await page.locator('.business-module,.scene-option,.framework-option,.connection-button,.business-module strong').evaluateAll(ns=>ns.every(n=>n.scrollWidth<=n.clientWidth+1)));
    await page.screenshot({path:path.join(output,'width-'+width+'.png'),fullPage:true});
  }
  if (mode === 'build' || mode === 'live') {
   for (const combination of (mode === 'live' ? ['both','wealth','balance'] : ['both'])) {
    if(combination !== 'both') await page.locator('[data-nav="create"]').first().click();
    await page.locator('[data-scenario="mobile-banking"]').click();
    const caps=combination==='both' ? ['account.balance.query','wealth.facts.describe'] : [combination==='balance' ? 'account.balance.query':'wealth.facts.describe'];
    await page.locator('[data-module="agent.account"]').setChecked(caps.includes('account.balance.query'));
    await page.locator('[data-module="agent.wealth_aggregate"]').setChecked(caps.includes('wealth.facts.describe'));
    if(mode === 'live') {
      const bound=cat.models.find(m=>m.bindingStatus==='CONFIGURED_UNTESTED');
      assert.ok(bound,'No configured model binding');
      await page.locator('[data-catalog="models"]').click();
      await page.locator(`[data-pick="${bound.modelRef}"]`).check();
      const selectedRefs=await page.locator('[data-pick]:checked').evaluateAll(ns=>ns.map(n=>n.dataset.pick));
      for(const ref of selectedRefs) if(ref!==bound.modelRef) await page.locator(`[data-pick="${ref}"]`).uncheck();
      await page.locator(`input[name="default-model"][value="${bound.modelRef}"]`).check();
      await page.locator('#catalog-save').click();
    }
    await page.setViewportSize({width:1440,height:1050});
    const response = page.waitForResponse(r=>r.url().endsWith('/api/v1/builds') && r.request().method()==='POST');
    await page.locator('[data-action="build"]').click();
    const res=await response; const build=await res.json(); builds.push(build.buildId);
    check(combination+':page_queues_actual_v05_build',res.status()===202 && build.selection.schema_version==='0.5' && build.selection.moduleIds.length===caps.length);
    let current=build; const until=Date.now()+1200000;
    while (['QUEUED','RUNNING'].includes(current.buildStatus) && Date.now()<until) {
      await page.waitForTimeout(1000); current=(await request('/api/v1/builds/'+build.buildId)).body;
    }
    check(combination+':page_build_compiled',current.buildStatus==='SUCCEEDED');
    check(combination+':build_records_framework_modules',current.directorySelection.runtime.id===body.runtimeId && current.directorySelection.modules.length===caps.length);
    fs.writeFileSync(path.join(output,combination+'-build.json'),JSON.stringify(current,null,2));
    await page.screenshot({path:path.join(output,combination+'-actual-build.png'),fullPage:true});
    if(mode === 'live') {
      const opening=page.waitForResponse(r=>r.url().endsWith('/sessions') && r.request().method()==='POST',{timeout:120000});
      await page.locator('[data-action="launch"]').click();
      const response=await opening, session=await response.json();
      assert.equal(response.status(),200,JSON.stringify(session.error));
      running={buildId:build.buildId,instanceId:session.instanceId};
      fs.writeFileSync(path.join(output,combination+'-installation.json'),JSON.stringify(session,null,2));
      const installed=session.result.installations.flatMap(x=>x.capabilities);
      check(combination+':actual_tools_exact',JSON.stringify(installed.map(x=>x.capabilityId).sort())===JSON.stringify(caps.sort()) && installed.every(x=>x.operation==='QUERY'));
      const questions=[combination==='balance' ? '请查询我已授权账户的余额，不指定账户编号。' : '请查询并描述我的财富事实、风险测评及缺失信息。','你好，请只简短回应问候，不查询金融数据。'];
      for(const query of questions) {
        await page.waitForFunction(()=>document.querySelector('#query') && !document.querySelector('#query').disabled);
        const before=await page.evaluate(()=>window.__studioStreams.length);
        await page.locator('#query').fill(query);
        await page.getByRole('button',{name:'发送',exact:true}).click();
        await page.waitForFunction(n=>window.__studioStreams.length>n,before,{timeout:180000});
        const stream=await page.evaluate(n=>window.__studioStreams[n],before);
        assert.equal(stream.status,200);
        const events=stream.body.split('\n').filter(l=>l.startsWith('data: ')).map(l=>JSON.parse(l.slice(6)));
        check(combination+':agui_finished_'+turns.length,events.some(e=>e.type==='RUN_FINISHED') && !events.some(e=>e.type==='RUN_ERROR'));
        const result=events.find(e=>e.type==='CUSTOM' && e.name==='finance.evidence')?.value?.result;
        assert.ok(result);
        check(combination+':configured_model_'+turns.length,result.evidenceKind==='CONFIGURED_MODEL_WITH_LOCAL_BANK_SIMULATOR' && result.modelRef===session.result.modelRef);
        check(combination+':tool_and_source_scope_'+turns.length,query.startsWith('你好') ? result.toolResults.length===0 : result.toolResults.length>0 && result.sourceCalls.length>0);
        turns.push({buildId:build.buildId,modelRef:result.modelRef,modelName:result.modelName,turn:result.turn,toolResults:result.toolResults.length,sourceCalls:result.sourceCalls.length,evidenceSha256:result.evidenceSha256,eventTypes:events.map(e=>e.type)});
      }
      await page.screenshot({path:path.join(output,combination+'-conversation.png'),fullPage:true});
      await page.setViewportSize({width:390,height:900});
      await page.screenshot({path:path.join(output,combination+'-conversation-mobile.png'),fullPage:true});
      await page.getByRole('button',{name:'停止',exact:true}).click();
      await page.waitForFunction(()=>!!document.querySelector('.building-emblem'));
      check(combination+':stopped',(await request('/api/v1/builds/'+build.buildId)).body.instance.status==='STOPPED');
      running=null;
    }
    await page.locator('[data-nav="agents"]').click();
    await page.locator(`[data-edit="${build.buildId}"]`).click();
    check(combination+':new_record_edit_restores_module_selection',await page.locator('[data-module]:checked').count()===caps.length);
   }
  }
  check('no_browser_errors',errors.length===0);
} finally {
  if(running) {
    const stopped=await request('/api/v1/builds/'+running.buildId+'/stop',{instanceId:running.instanceId});
    if(stopped.status!==200) errors.push('Stop cleanup failed');
  }
  await browser.close();
  fs.writeFileSync(path.join(output,'verification.json'),JSON.stringify({mode,checks,errors,builds,turns,realModel:turns.length ? 'CONFIGURED_MODEL_WITH_LOCAL_SIMULATOR':'NOT_RUN',humanOnboarding:'NOT_RUN'},null,2));
}
