import assert from 'node:assert/strict';
import {mkdirSync,writeFileSync,readFileSync} from 'node:fs';
const {chromium}=await import(process.env.PLAYWRIGHT_MODULE||'playwright');
const [base,out]=process.argv.slice(2);assert(base&&out);
mkdirSync(out,{recursive:true});
const checks=[],errors=[],mutations=[];
const check=(name,value)=>{checks.push({name,passed:!!value});writeFileSync(out+'/checks.json',JSON.stringify(checks,null,2));assert(value,name);};
const browser=await chromium.launch({headless:true,executablePath:process.env.CHROMIUM_PATH});
try{
  const credentials=process.env.FH_UI_AUTH_FILE?JSON.parse(readFileSync(process.env.FH_UI_AUTH_FILE,'utf8')):null;
  const context=await browser.newContext({viewport:{width:1440,height:1000},...(credentials?{httpCredentials:credentials}:{})});
  const page=await context.newPage();
  page.on('pageerror',e=>errors.push(e.message));page.on('request',r=>{if(r.method()!=='GET')mutations.push(r.url());});
  const loaded=page.waitForResponse(r=>r.url().endsWith('/api/v1/catalog')&&r.status()===200);
  await page.goto(base);
  const catalog=await(await loaded).json();
  const directory=catalog.unifiedDirectory;assert(directory);
  const assets=[];
  for(const component of directory.components){
    if(component.status!=='AVAILABLE')continue;
    const response=await page.request.get(base+`/api/v1/assets/components/${component.id}/${directory.revision}/contents`);
    check(component.id+'-actual-contents',response.status()===200);
    const value=await response.json();assets.push({component,value});
    check(component.id+'-unique-source-file-keys',new Set(value.files.map(f=>f.fileId||f.path)).size===value.files.length);
  }
  writeFileSync(out+'/actual-assets.json',JSON.stringify(assets,null,2));
  const multi=assets.find(a=>a.value.bindings.length>1&&a.value.files.some(f=>f.role==='SKILL'));
  const noSkill=assets.find(a=>!a.value.files.some(f=>f.role==='SKILL')&&a.value.bindings.length===1&&a.value.developmentSupport?.methodCases?.length);
  assert(multi&&noSkill,'real multi-capability and no-Skill assets required');
  const nav=async id=>{const open=page.getByRole('button',{name:'打开导航',exact:true});if(await open.isVisible())await open.click();await page.locator(`[data-nav="${id}"]`).click();};
  const open=async asset=>{await nav('assets');await page.locator(`[data-method-action="business-detail"][data-component="${asset.component.id}"]`).click();await page.locator('.asset-graph-svg').waitFor({state:'attached'});};
  for(const width of [1440,390,320]){
    await page.setViewportSize({width,height:1000});await open(multi);
    check('relations-still-visible-'+width,await page.locator(width<1050?'.asset-graph-mobile':'.asset-graph-desktop').isVisible());
    await page.getByRole('tab',{name:'检查与生成'}).click();
    const support=multi.value.developmentSupport;assert(support?.methodCases?.length,'real pinned method cases required');
    const panel=page.locator('.component-development-support');await panel.waitFor();
    const text=await panel.innerText();
    check('actual-cases-visible-'+width,support.methodCases.every(c=>text.includes(c.query)));
    check('no-fabricated-pass-'+width,text.includes(support.validation.reason));
    await page.screenshot({path:out+`/component-verification-${width}.png`,fullPage:true});
    check('no-overflow-'+width,await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));
    await page.getByRole('tab',{name:'文件与修改'}).click();
    const f=multi.value.files.find(f=>f.role==='SKILL'&&f.previewStatus==='AVAILABLE');assert(f);
    await page.locator(`[data-asset-file="${f.fileId||f.path}"]`).click();
    check('exact-version-file-'+width,await page.locator('.asset-file-code code').textContent()===f.content);
    check('source-visible-'+width,(await page.locator('.asset-file-preview').innerText()).includes(f.reference.id));
    const downloaded=page.waitForEvent('download');await page.locator('[data-asset-download-file]').click();
    const download=await downloaded;check('exact-file-download-'+width,readFileSync(await download.path(),'utf8')===f.content);
    await page.screenshot({path:out+`/source-file-${width}.png`,fullPage:true});
    await nav('agents');await nav('assets');
    check('navigation-not-stale-'+width,await page.locator('[data-method-action="business-detail"]').count()>0&&await page.locator('.asset-graph-svg').count()===0);
  }
  await page.setViewportSize({width:1440,height:1000});await open(noSkill);
  check('no-method-invented',await page.locator('.asset-graph-notice').innerText()==='不包含独立方法，直接展示能力与接入依赖。');
  await page.getByRole('tab',{name:'检查与生成'}).click();
  check('no-skill-has-own-cases',(await page.locator('.component-development-support').innerText()).includes(noSkill.value.developmentSupport.methodCases[0].query));
  await page.screenshot({path:out+'/balance-verification.png',fullPage:true});
  check('no-browser-errors',errors.length===0);check('read-only-zero-mutations',mutations.length===0);
  writeFileSync(out+'/report.json',JSON.stringify({status:'PASS',checks,errors,mutations,assets:assets.map(a=>({id:a.component.id,identity:a.value.identity})),humanIndependentAcceptance:'NOT_RUN'},null,2));
  console.log('PASS',checks.length,'asset onboarding browser checks');
}finally{await browser.close();}
