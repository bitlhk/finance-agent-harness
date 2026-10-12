import assert from 'node:assert/strict';
import {mkdirSync, readFileSync, writeFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {resolve} from 'node:path';

const base=process.argv[2], output=resolve(process.argv[3]||'build/asset-content-browser');
if(!base)throw Error('Usage: node engineering/verify_asset_contents.mjs URL OUTPUT');
const {chromium}=await import(process.env.PLAYWRIGHT_MODULE||'playwright');
const credentials=process.env.FH_UI_AUTH_FILE?JSON.parse(readFileSync(process.env.FH_UI_AUTH_FILE,'utf8')):null;
const browser=await chromium.launch({headless:true,...(process.env.CHROMIUM_PATH?{executablePath:process.env.CHROMIUM_PATH}:{})});
mkdirSync(output,{recursive:true,mode:0o700});
const checks=[];
try {
  for(const width of [1440,390]) {
    const context=await browser.newContext({viewport:{width,height:960},acceptDownloads:true,
      ...(credentials?{httpCredentials:{username:credentials.username,password:credentials.password}}:{})});
    const page=await context.newPage(), errors=[], writes=[];
    page.on('pageerror',e=>errors.push(e.message));
    page.on('request',request=>{if(request.method()!=='GET')writes.push({method:request.method(),url:new URL(request.url()).pathname});});
    await page.goto(base,{waitUntil:'networkidle'});
    if(width<640)await page.getByRole('button',{name:'打开导航',exact:true}).click();
    await page.locator('[data-nav="assets"]').click();
    await page.locator('[data-method-action="business-detail"][data-component="finance.cashflow.readonly"]').click();
    await page.locator('.asset-file-tree button').first().waitFor();
    const api=await page.evaluate(async()=>{
      const catalog=await(await fetch('/api/v1/catalog')).json(), d=catalog.unifiedDirectory;
      const results=[];
      for(const part of d.components){const r=await fetch(`/api/v1/assets/components/${encodeURIComponent(part.id)}/${d.revision}/contents`);results.push({id:part.id,status:r.status,value:await r.json()});}
      return results;
    });
    assert(api.length>=3);
    for(const item of api){assert.equal(item.status,200,item.id);assert(item.value.files.length);assert(item.value.bindings.length);}
    checks.push({name:'catalog_components_read',width,count:api.length});
    const cash=api.find(r=>r.id==='finance.cashflow.readonly').value;
    assert.equal(cash.bindings.length,2);
    const skill=cash.files.find(f=>f.role==='SKILL');assert(skill);
    await page.locator(`[data-asset-file="${skill.path}"]`).click();
    assert.equal(await page.locator('.asset-file-code code').textContent(),skill.content);
    const downloaded=page.waitForEvent('download');
    await page.locator('[data-asset-download-file]').click();
    const download=await downloaded;
    assert.equal('sha256:'+createHash('sha256').update(readFileSync(await download.path())).digest('hex'),skill.sha256);
    await page.screenshot({path:output+`/files-${width}.png`,fullPage:true});
    await page.getByRole('tab',{name:'工具与依赖'}).click();
    assert.equal(await page.locator('.asset-bindings section').count(),2);
    assert.equal(cash.development.skills[0].status,'DECLARED');
    assert.deepEqual(cash.development.skills[0].unresolvedTools,[]);
    assert.equal(cash.development.executionVerified,false);
    await page.getByRole('heading',{name:'方法如何使用工具'}).waitFor();
    await page.screenshot({path:output+`/tools-${width}.png`,fullPage:true});
    await page.getByRole('button',{name:'查看结果合同'}).first().click();
    assert.equal(await page.getByRole('tab',{name:'组成与文件'}).getAttribute('aria-selected'),'true');
    assert((await page.locator('.asset-file-code').innerText()).includes('schema'));
    await page.getByRole('tab',{name:'验证与使用'}).focus();
    await page.keyboard.press('Enter');
    await page.getByRole('heading',{name:'引用此资产的 Agent'}).waitFor();
    await page.getByRole('heading',{name:'开发责任与验收清单'}).waitFor();
    await page.locator('.asset-rule-list summary').filter({hasText:'Trace / 观测'}).click();
    assert((await page.locator('.asset-rule-list').innerText()).includes('来源操作名'));
    assert(cash.development.sourceReferences.length>=7);
    await page.screenshot({path:output+`/usage-${width}.png`,fullPage:true});
    assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));
    assert.deepEqual(errors,[]);
    assert.deepEqual(writes,[]);
    checks.push({name:'files_download_tabs_keyboard_no_overflow_no_mutation',width});
    await context.close();
  }
  writeFileSync(output+'/browser-report.json',JSON.stringify({base,checks,realModel:'NOT_RUN',bankBusinessCalls:'NO_EXECUTION_ROUTES_REQUESTED'},null,2));
  console.log(JSON.stringify(checks));
} finally {await browser.close();}
