import {createRequire} from 'node:module';
import {readFileSync,mkdirSync,writeFileSync} from 'node:fs';
import assert from 'node:assert/strict';
const {chromium}=createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE || '@playwright/test');
const out=process.env.HISTORY_CSP_OUTPUT || '/tmp/finance-history-csp';
mkdirSync(out,{recursive:true});
const script=`import {executionHistory,positionWaterfall} from '/execution-history.js';
const span=(spanId,component,startNanos,durationNanos)=>({spanId,component,startNanos,durationNanos,parentSpanId:spanId==='root'?null:'root',operation:spanId==='root'?'request':'call',outcome:'COMPLETED',links:{}});
const record={requestId:'synthetic-csp',createdAt:'2026-10-06T00:00:00Z',status:'COMPLETED',complete:false,gaps:[],events:[],snapshots:[],spans:[span('root','host',0,20e9),span('long','model',1e9,18e9),span('short','provider-http',2e9,1e8),span('parallel','model',2e9,8e9),span('open','model',19e9,null)]};
const esc=v=>String(v??'').replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('"','&quot;');
const render=()=>{document.querySelector('main').innerHTML=view.html();view.layout();};
const view=executionHistory({api:async()=>structuredClone(record),esc,icon:()=>'',render});
document.addEventListener('click',e=>view.click(e));
await view.click({target:{closest:()=>({dataset:{historyId:'synthetic-csp'}})}});
window.positionWaterfall=positionWaterfall;window.ready=true;`;
const browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH,args:['--no-sandbox']});
const results=[];
try{
for(const width of [1440,390]){
 const page=await browser.newPage({viewport:{width,height:1000}}),errors=[];
 page.on('pageerror',e=>errors.push(e.message));page.on('console',m=>{if(m.type()==='error')errors.push(m.text());});
 await page.route('https://history.test/**',route=>{
  const path=new URL(route.request().url()).pathname;
  const headers={'Content-Security-Policy':"default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'"};
  if(path==='/')return route.fulfill({headers,contentType:'text/html',body:'<link rel="stylesheet" href="/execution-history.css"><main></main><script type="module" src="/fixture.js"></script>'});
  if(path==='/fixture.js')return route.fulfill({headers,contentType:'text/javascript',body:script});
  if(!['/execution-history.js','/execution-history.css','/history-labels.js'].includes(path))return route.abort();
  return route.fulfill({headers,contentType:path.endsWith('.css')?'text/css':'text/javascript',body:readFileSync('browser'+path,'utf8')});
 });
 await page.goto('https://history.test/');await page.waitForFunction(()=>window.ready);
 const geometry=()=>page.locator('[data-history-span-select="long"] .history-bar').evaluate(e=>({ratio:e.getBoundingClientRect().width/e.parentElement.getBoundingClientRect().width,left:parseFloat(e.style.left),label:parseFloat(e.parentElement.querySelector('.history-duration').style.left)}));
 const before=await geometry();assert.ok(Math.abs(before.ratio-.9)<.01);assert.equal(before.left,5);assert.equal(before.label,87);
 await page.locator('[data-history-span-select="short"]').click();
 assert.equal(await page.locator('[data-history-span-select="short"]').getAttribute('aria-pressed'),'true');
 assert.ok(Math.abs((await geometry()).ratio-.9)<.01);
 assert.equal(await page.locator('[data-history-span-select="open"] .history-bar').count(),0);
 assert.equal(await page.locator('[data-history-span-select="open"] .history-open-marker').evaluate(e=>e.style.left),'95%');
 await page.screenshot({path:out+'/'+width+'.png',fullPage:true});
 const invalid=await page.evaluate(()=>{const root=document.createElement('div');root.innerHTML='<span class="history-track"><span data-history-left="NaN" data-history-width="Infinity"></span><span data-history-left="-2" data-history-width="200"></span></span>';window.positionWaterfall(root);return [...root.querySelectorAll('[data-history-left]')].map(e=>({left:e.style.left,width:e.style.width}));});
 assert.deepEqual(invalid,[{left:'',width:''},{left:'0%',width:'100%'}]);assert.deepEqual(errors,[]);
 results.push({width,...before,rerender:true,selection:true,openSpan:true,invalidValues:true,cspErrors:errors});await page.close();
}
writeFileSync(out+'/results.json',JSON.stringify(results,null,2));console.log(JSON.stringify(results));
}finally{await browser.close();}
