import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE || 'playwright');
const [base, output, mode = 'metadata'] = process.argv.slice(2);
fs.mkdirSync(output, {recursive: true});
const checks = [], errors = [];
const check = (name, value) => { assert.ok(value, name); checks.push(name); };
const browser = await chromium.launch({headless: true});
const context = await browser.newContext({viewport: {width: 1440, height: 1000},
  ...(process.env.STUDIO_TEST_USER ? {httpCredentials: {username: process.env.STUDIO_TEST_USER, password: process.env.STUDIO_TEST_PASSWORD}} : {})});
const page = await context.newPage();
await page.addInitScript(() => {
  window.__studioStreams = [];
  const fetch = window.fetch.bind(window);
  window.fetch = async (...args) => {
    const response = await fetch(...args);
    if (response.url.endsWith('/ag-ui')) response.clone().text().then(body => window.__studioStreams.push({status:response.status,body}));
    return response;
  };
});
let generated = null;
const builds = [], turns = [];
let running = null;
async function readBuild(id) {
  return context.request.get(new URL('/api/v1/builds/'+id,base).href).then(r=>r.json());
}
async function stopRunning() {
  if (!running && mode === 'live' && generated) {
    const current = await readBuild(generated);
    if (current.instance?.status === 'READY') running={buildId:generated,instanceId:current.instance.instanceId};
  }
  if (!running) return;
  const {buildId,instanceId} = running;
  const result = await page.evaluate(async ({buildId,instanceId}) => {
    const session = await fetch('/api/bootstrap').then(r=>r.json());
    const r = await fetch('/api/v1/builds/'+buildId+'/stop', {method:'POST',headers:{'Content-Type':'application/json','X-CSRF-Token':session.csrf},body:JSON.stringify({instanceId})});
    return {status:r.status,body:await r.json()};
  }, {buildId,instanceId});
  assert.equal(result.status,200);
  running=null;
}
page.on('pageerror', error => errors.push(error.message));
try {
  await page.goto(base);
  await page.locator('[data-audience="CUSTOMER"]').waitFor();
  check('three_audiences_engineering_disabled', await page.locator('[data-audience]').count() === 3 && await page.locator('[data-audience="ENGINEERING"]').isDisabled());
  check('templates_default_no_silent_selection', await page.locator('.scenario-card').count() === 2 && await page.locator('[data-action="build"]').isDisabled());
  await page.screenshot({path: path.join(output,'desktop-templates.png'), fullPage:true});
  await page.locator('[data-scenario="customer-wealth-readonly"]').click();
  check('template_selects_actual_two_components', await page.locator('[data-cap]:checked').count() === 2);
  await page.locator('[data-cap="account.balance.query"]').click();
  check('template_can_remove_component', await page.locator('[data-cap]:checked').count() === 1);
  await page.locator('[data-scenario="customer-wealth-readonly"]').click();
  await page.locator('[data-directory-view="COMPONENTS"]').click();
  check('parallel_entry_preserves_visible_selection', await page.locator('[data-component]:checked').count() === 2);
  await page.locator('#directory-search').fill('余额');
  check('search_only_filters_not_selection', await page.locator('.component-card').count() === 1 && await page.locator('[data-cap]:checked').count() === 2);
  await page.locator('#directory-domain').selectOption('WEALTH');
  check('combined_filters_can_be_empty', await page.locator('.component-card').count() === 0 && await page.locator('[data-cap]:checked').count() === 2);
  await page.locator('#directory-search').fill('');
  await page.locator('#directory-domain').selectOption('');
  await page.locator('[data-directory-detail="wealth.facts.describe"]').click();
  check('details_show_real_tools_scopes', (await page.locator('#drawer').innerText()).includes('wealth_facts_describe') && (await page.locator('#drawer').innerText()).includes('wealth:facts:read'));
  await page.screenshot({path: path.join(output,'component-details.png')});
  await page.keyboard.press('Escape');
  await page.locator('[data-audience="EMPLOYEE"]').click();
  check('employee_cannot_build_or_inherit_selection', await page.locator('[data-cap]:checked').count() === 0 && await page.locator('[data-action="build"]').isDisabled());
  check('same_two_components_pending_for_employee', await page.locator('[data-component]:disabled').count() === 2);
  await page.screenshot({path: path.join(output,'employee-components.png'), fullPage:true});
  await page.locator('[data-audience="CUSTOMER"]').click();
  check('return_does_not_restore_old_profile_selection', await page.locator('[data-component]:checked').count() === 0);
  await page.locator('[data-component="wealth.facts.describe"]').check();
  await page.locator('[data-catalog="models"]').click();
  check('existing_model_picker_present', await page.locator('[data-pick]').count() > 0);
  await page.keyboard.press('Escape');
  await page.locator('[data-action="bank-picker"]').click();
  check('existing_bank_picker_preserves_scope', await page.locator('dialog input[name="bank"]:checked').count() === 1);
  await page.keyboard.press('Escape');
  for (const width of [1440,820,390,320]) {
    await page.setViewportSize({width,height:1000});
    check(`${width}_no_page_overflow`, await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    const overflow = await page.locator('.directory-card, .audience-tabs button').evaluateAll(nodes => nodes.filter(n => n.scrollWidth > n.clientWidth + 1).length);
    check(`${width}_cards_fit`, overflow === 0);
    await page.screenshot({path:path.join(output,`${width}-components.png`),fullPage:true});
  }
  if (mode === 'build' || mode === 'live') {
   for (const entry of (mode === 'live' ? ['COMPONENTS','TEMPLATE'] : ['COMPONENTS'])) {
    await page.setViewportSize({width:1440,height:1000});
    if (entry === 'TEMPLATE') {
      await page.locator('[data-nav="create"]').first().click();
      await page.locator('[data-directory-view="TEMPLATE"]').click();
      await page.locator('[data-scenario="customer-account-readonly"]').click();
    }
    const responsePromise = page.waitForResponse(r => r.url().endsWith('/api/v1/builds') && r.request().method() === 'POST', {timeout:30000});
    await page.locator('[data-action="build"]').click();
    const response = await responsePromise;
    assert.equal(response.status(),202);
    const initial = await response.json();
    assert.deepEqual(initial.selection.capabilityIds,entry === 'COMPONENTS' ? ['wealth.facts.describe'] : ['account.balance.query']);
    assert.equal(initial.selection.entryMode,entry);
    generated = initial.buildId;
    const deadline=Date.now()+900000;
    while(Date.now()<deadline){
      const current=await readBuild(generated);
      if(current.buildStatus === 'SUCCEEDED'){
        check('real_build_succeeded_with_frozen_selection', !!current.artifact && current.directorySelection.components.length === 1);
        builds.push(current);
        fs.writeFileSync(path.join(output,entry+'-build-result.json'),JSON.stringify(current,null,2));
        break;
      }
      if(['FAILED','INVALID_ARTIFACT','INTERRUPTED'].includes(current.buildStatus)) throw Error('Real build failed: '+JSON.stringify(current.error));
      await new Promise(r=>setTimeout(r,2000));
    }
    assert.ok(builds.some(b=>b.buildId === generated),'Build completion timeout');
    await page.screenshot({path:path.join(output,entry+'-real-build.png'),fullPage:true});
    if (mode === 'live') {
      const opened = page.waitForResponse(r=>r.url().endsWith('/sessions') && r.request().method()==='POST', {timeout:120000});
      await page.locator('[data-action="launch"]').click();
      const response = await opened;
      const session = await response.json();
      assert.equal(response.status(),200,JSON.stringify(session.error));
      running={buildId:generated,instanceId:session.instanceId};
      fs.writeFileSync(path.join(output,entry+'-installation.json'),JSON.stringify(session,null,2));
      const tools=JSON.stringify(session.result.installations);
      check(entry+'_only_selected_tool_installed',tools.includes(entry === 'COMPONENTS' ? 'wealth_facts_describe' : 'account_balance_query') && !tools.includes(entry === 'COMPONENTS' ? 'account_balance_query' : 'wealth_facts_describe') && !tools.includes('transfer'));
      const queries=entry === 'COMPONENTS' ? ['请查询并描述我的财富事实、风险测评及缺失信息。','你好，请只简短回应问候，不查询金融数据。'] : ['查一下我已授权账户的余额，不指定账户编号。'];
      for (const query of queries) {
        await page.locator('#query').waitFor();
        await page.waitForFunction(()=>!document.querySelector('#query').disabled);
        const before = await page.evaluate(()=>window.__studioStreams.length);
        await page.locator('#query').fill(query);
        await page.getByRole('button',{name:'发送',exact:true}).click();
        await page.waitForFunction(n=>window.__studioStreams.length>n,before,{timeout:180000});
        const r=await page.evaluate(n=>window.__studioStreams[n],before); assert.equal(r.status,200);
        const events=r.body.split('\n').filter(l=>l.startsWith('data: ')).map(l=>JSON.parse(l.slice(6)));
        check(entry+'_agui_finished_'+turns.length,events.some(e=>e.type==='RUN_FINISHED') && !events.some(e=>e.type==='RUN_ERROR'));
        const evidence=events.find(e=>e.type==='CUSTOM' && e.name==='finance.evidence')?.value;
        assert.ok(evidence?.result);
        const result=evidence.result;
        check(entry+'_configured_model_'+turns.length,result.evidenceKind==='CONFIGURED_MODEL_WITH_LOCAL_BANK_SIMULATOR' && result.modelRef===session.result.modelRef);
        if (query.startsWith('你好')) check('greeting_does_not_call_financial_tool',result.toolResults.length===0);
        else check(entry+'_financial_tool_and_source_observed',result.toolResults.length>0 && result.sourceCalls.length>0);
        turns.push({buildId:generated,sessionId:session.sessionId,modelRef:result.modelRef,modelName:result.modelName,turn:result.turn,toolResultCount:result.toolResults.length,sourceCallCount:result.sourceCalls.length,evidenceSha256:result.evidenceSha256,eventTypes:events.map(e=>e.type)});
        await page.waitForFunction(()=>!document.querySelector('#query').disabled);
      }
      await page.screenshot({path:path.join(output,entry+'-conversation.png'),fullPage:true});
      await page.setViewportSize({width:390,height:900});
      await page.screenshot({path:path.join(output,entry+'-conversation-mobile.png'),fullPage:true});
      await page.getByRole('button',{name:'停止',exact:true}).click();
      await page.waitForFunction(()=>!!document.querySelector('.building-emblem'));
      check(entry+'_stopped',(await readBuild(generated)).instance.status==='STOPPED');
      running=null;
    }
   }
  }
  check('no_browser_errors',errors.length === 0);
} finally {
  try { await stopRunning(); } catch (e) { errors.push('Cleanup: '+e.message); }
  fs.writeFileSync(path.join(output,'verification.json'), JSON.stringify({mode,checks,errors,generated,buildIds:builds.map(b=>b.buildId),turns,realModel:turns.length ? 'CONFIGURED_MODEL_WITH_LOCAL_SIMULATOR' : 'NOT_RUN',humanOnboarding:'NOT_RUN'},null,2));
  await browser.close();
}
console.log(JSON.stringify({checks:checks.length,errors,generated}));
