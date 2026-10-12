import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
const {chromium}=await import(process.env.PLAYWRIGHT_MODULE || 'playwright');
const [base,output]=process.argv.slice(2);
fs.mkdirSync(output,{recursive:true});
const report={checks:[],failures:[],errors:[],bounds:[],skips:[],mutations:[]};
const browser=await chromium.launch({headless:true});
const context=await browser.newContext({viewport:{width:1440,height:1000},
  ...(process.env.STUDIO_TEST_USER ? {httpCredentials:{username:process.env.STUDIO_TEST_USER,password:process.env.STUDIO_TEST_PASSWORD}} : {})});
const page=await context.newPage();
page.on('pageerror',e=>report.errors.push(e.message));
page.on('request',r=>{if(r.method()!=='GET') report.mutations.push({method:r.method(),path:new URL(r.url()).pathname});});
const more=()=>page.locator('[data-action="more-templates"]');
const dialog=()=>page.locator('#drawer');
const chosen=id=>page.locator(`[data-scenario="${id}"]`);
async function check(name,ok){assert.ok(await ok,name);report.checks.push(name);}
async function settled(){await page.waitForTimeout(80);}
async function focus(selector){await settled();return page.locator(selector).evaluate(n=>n===document.activeElement);}
async function reset(width=1440,height=1000){await page.setViewportSize({width,height});await page.goto(base);await chosen('mobile-banking').waitFor();}
async function group(name,fn){try{await reset();await fn();}catch(e){report.failures.push({case:name,error:e.message});await page.screenshot({path:path.join(output,name+'-failure.png'),fullPage:true});}}
try {
 await group('picker-size',async()=>{
  await more().click();
  const box=await dialog().boundingBox();report.bounds.push({width:1440,dialog:box});
  await page.screenshot({path:path.join(output,'desktop-picker-open.png'),fullPage:true});
  await check('desktop_picker_compact',box.width>=380 && box.width<=420 && box.height<=360);
  const rows=await page.locator('[data-pending-scenario]').evaluateAll(ns=>ns.map(n=>n.getBoundingClientRect().height));
  await check('rows_60_to_72px',rows.length===3 && rows.every(h=>h>=60 && h<=72));
 });
 await group('selected-card',async()=>{
  for(const id of ['group-insurance-review','loan-review','investment-research']) {
   await more().click();await page.locator(`[data-pending-scenario="${id}"]`).click();await settled();
   await check(id+':closed',!(await dialog().evaluate(n=>n.open)));
   await check(id+':real_radio',await chosen(id).count()===1 && await chosen(id).getAttribute('role')==='radio' && await chosen(id).getAttribute('aria-checked')==='true');
   await check(id+':highlight',await chosen(id).evaluate(n=>n.classList.contains('is-selected')));
   await check(id+':focus',focus(`[data-scenario="${id}"]`));
   await chosen(id).click();await settled();
   await check(id+':repeat_no_dialog',!(await dialog().evaluate(n=>n.open)));
   await check(id+':no_fake_modules',await page.locator('[data-module]').count()===0 && await page.locator('[data-action="build"]').isDisabled());
   if(id==='group-insurance-review') await page.screenshot({path:path.join(output,'group-insurance-selected.png'),fullPage:true});
  }
  await check('more_button_separate_from_cards',await page.locator('.scene-options [data-action="more-templates"]').count()===0 && await more().count()===1);
 });
 await group('idempotent-config',async()=>{
  await page.locator('[data-module="agent.wealth_aggregate"]').uncheck();
  await page.locator('[data-template="DESKTOP_WEB"]').click();
  const model=await page.locator('[data-catalog="models"]').innerText();
  const bank=await page.locator('[data-action="bank-picker"]').innerText();
  await chosen('mobile-banking').click();
  await check('reselect_keeps_removed_module',!(await page.locator('[data-module="agent.wealth_aggregate"]').isChecked()));
  await check('reselect_keeps_models_bank_presentation',model===await page.locator('[data-catalog="models"]').innerText() && bank===await page.locator('[data-action="bank-picker"]').innerText() && await page.locator('[data-template="DESKTOP_WEB"]').getAttribute('aria-checked')==='true');
  await page.locator('[data-nav="agents"]').click();
  const edits=page.locator('[data-edit]');
  if(await edits.count()) {
   await edits.first().click();const before=await page.locator('.edit-context').innerText();
   await chosen('mobile-banking').click();
   await check('reselect_keeps_edit_context',await page.locator('.edit-context').innerText()===before);
  } else report.skips.push('No existing record for current local operator: edit-context check');
 });
 await group('cancel-and-keyboard',async()=>{
  for(const method of ['escape','close','backdrop']) {
   await more().click();
   if(method==='escape') await page.keyboard.press('Escape');
   if(method==='close') await page.locator('.drawer-close').click();
   if(method==='backdrop') await page.mouse.click(5,5);
   await settled();
   await check(method+':closed',!(await dialog().evaluate(n=>n.open)));
   await check(method+':unchanged',await chosen('mobile-banking').getAttribute('aria-checked')==='true');
   await check(method+':focus_back',focus('[data-action="more-templates"]'));
  }
  await more().click();await page.locator('[data-pending-scenario="group-insurance-review"]').focus();
  await page.keyboard.press('ArrowDown');await settled();
  await check('picker_arrow_only_moves_focus',focus('[data-pending-scenario="loan-review"]'));
  await page.keyboard.press('ArrowDown');await check('picker_next_focus',focus('[data-pending-scenario="investment-research"]'));
  await page.keyboard.press('ArrowUp');await check('picker_previous_focus',focus('[data-pending-scenario="loan-review"]'));
  await check('picker_navigation_does_not_commit',await dialog().evaluate(n=>n.open) && await chosen('mobile-banking').getAttribute('aria-checked')==='true');
  await page.keyboard.press('Enter');await settled();
  await check('picker_enter_commits_loan',await chosen('loan-review').count()===1 && await chosen('loan-review').getAttribute('aria-checked')==='true' && !(await dialog().evaluate(n=>n.open)));
  await more().click();await page.keyboard.press('Home');
  await check('picker_home_focus',focus('[data-pending-scenario="group-insurance-review"]'));
  await page.keyboard.press('End');await check('picker_end_focus',focus('[data-pending-scenario="investment-research"]'));
  await page.keyboard.press('Escape');await settled();
  await check('picker_navigation_escape_keeps_loan',await chosen('loan-review').getAttribute('aria-checked')==='true' && await focus('[data-action="more-templates"]'));
  await more().click();
  await check('picker_committed_option_marked',await page.locator('[data-pending-scenario="loan-review"]').getAttribute('aria-selected')==='true');
  await page.keyboard.press('Home');await page.keyboard.press('Space');await settled();
  await check('picker_space_commits',await chosen('group-insurance-review').getAttribute('aria-checked')==='true' && !(await dialog().evaluate(n=>n.open)));
  await chosen('group-insurance-review').focus();
  await page.keyboard.press('Home');await settled();
  await check('main_radio_home_selects_first',await chosen('mobile-banking').getAttribute('aria-checked')==='true');
  await page.keyboard.press('ArrowRight');await settled();
  await check('main_radio_arrow_selects_next',await chosen('wealth-assistant').getAttribute('aria-checked')==='true' && await chosen('wealth-assistant').getAttribute('tabindex')==='0');
 });
 await group('other-drawers',async()=>{
  await more().click();await page.keyboard.press('Escape');
  await page.locator('[data-catalog="models"]').click();
  await check('model_drawer_not_shrunk',(await dialog().boundingBox()).height>=900 && !(await dialog().evaluate(n=>n.classList.contains('template-picker'))));
  await page.locator('#catalog-save').click();
  await check('model_save_render_restores_focus',focus('[data-catalog="models"]'));
  await page.locator('[data-action="bank-picker"]').click();
  await check('bank_drawer_unchanged',await dialog().evaluate(n=>n.classList.contains('bank-drawer') && !n.classList.contains('template-picker')));
  await page.locator('#bank-save').click();await check('bank_save_restores_focus',focus('[data-action="bank-picker"]'));
  await page.locator('[data-module-detail="agent.account"]').click();
  await check('detail_drawer_not_shrunk',(await dialog().boundingBox()).height>=900);
  await page.keyboard.press('Escape');await check('detail_cancel_restores_focus',focus('[data-module-detail="agent.account"]'));
  // A queued close event must not erase the next dialog's draft or steal its focus.
  await more().click();
  await page.evaluate(()=>{document.querySelector('#drawer').close();document.querySelector('[data-catalog="models"]').click();});
  await settled();
  await check('stale_close_does_not_clear_new_dialog',await dialog().evaluate(n=>n.open && n.contains(document.activeElement)) && await page.locator('[data-pick]').count()>0);
  await page.keyboard.press('Escape');
 });
 await group('responsive',async()=>{
  for(const width of [1440,1024,390,320]) {
   await reset(width,900);await more().click();
   const box=await dialog().boundingBox();report.bounds.push({width,dialog:box});
   await check(width+':picker_safe_margins',box.x>=15 && box.x+box.width<=width-15 && box.height<=360);
   await check(width+':no_overflow',await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
   await check(width+':rows_not_stretched',await page.locator('[data-pending-scenario]').evaluateAll(ns=>ns.every(n=>{const r=n.getBoundingClientRect();return r.height>=60 && r.height<=72 && n.scrollWidth<=n.clientWidth+1;})));
   await page.screenshot({path:path.join(output,`width-${width}-picker.png`),fullPage:true});
   await page.locator('[data-pending-scenario="group-insurance-review"]').click();await settled();
   await check(width+':selected_card_fits',await chosen('group-insurance-review').evaluate(n=>n.scrollWidth<=n.clientWidth+1));
   await page.screenshot({path:path.join(output,`width-${width}-selected.png`),fullPage:true});
  }
  await reset(320,400);await more().click();
  await check('short_screen_picker_scoped_scroll',await dialog().evaluate(n=>{const r=n.getBoundingClientRect();return r.top>=15 && r.bottom<=innerHeight-15;}));
  await page.evaluate(()=>{const list=document.querySelector('.template-picker-options');for(let i=0;i<12;i++){const row=list.firstElementChild.cloneNode(true);row.removeAttribute('data-pending-scenario');list.append(row);}});
  await check('synthetic_long_list_scrolls_without_stretch',await page.locator('.template-picker-options').evaluate(n=>{n.scrollTop=100;return n.scrollTop>0 && n.scrollHeight>n.clientHeight && n.firstElementChild.getBoundingClientRect().height===64;}));
 });
 await group('touch',async()=>{
  const touchContext=await browser.newContext({viewport:{width:390,height:844},isMobile:true,hasTouch:true,
   ...(process.env.STUDIO_TEST_USER ? {httpCredentials:{username:process.env.STUDIO_TEST_USER,password:process.env.STUDIO_TEST_PASSWORD}} : {})});
  try {
   const t=await touchContext.newPage();await t.goto(base);await t.locator('[data-action="more-templates"]').tap();
   await t.locator('[data-pending-scenario="group-insurance-review"]').tap();await t.waitForTimeout(80);
   await check('touch_selects_real_card',await t.locator('[data-scenario="group-insurance-review"]').getAttribute('aria-checked')==='true');
   await t.locator('[data-scenario="group-insurance-review"]').tap();
   await check('touch_reselect_is_idempotent',await t.locator('#drawer').evaluate(n=>!n.open));
   await t.locator('[data-action="more-templates"]').tap();await t.touchscreen.tap(5,5);await t.waitForTimeout(80);
   await check('touch_backdrop_cancels',await t.locator('#drawer').evaluate(n=>!n.open));
  } finally {await touchContext.close();}
 });
 await check('no_mutating_api_calls',report.mutations.length===0);
 await check('no_browser_errors',report.errors.length===0);
} finally {
 await browser.close();
 fs.writeFileSync(path.join(output,'report.json'),JSON.stringify(report,null,2));
 console.log(JSON.stringify({checks:report.checks.length,failures:report.failures,skips:report.skips,errors:report.errors}));
 if(report.failures.length || report.errors.length) process.exitCode=1;
}
