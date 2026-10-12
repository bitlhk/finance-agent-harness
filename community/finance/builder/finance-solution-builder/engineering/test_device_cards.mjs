import assert from 'node:assert/strict';
import {build} from 'esbuild';
import {readFileSync} from 'node:fs';

// Execute the same shared renderer bundled for Builder and generated Agent pages.
const result = await build({entryPoints:['browser/presentation.js'], bundle:true, write:false, format:'esm'});
globalThis.location = {pathname:'/experience/test/'};
const {cards, defaultUI, decimal} = await import('data:text/javascript;base64,' + Buffer.from(result.outputFiles[0].text).toString('base64'));
let checks = 0;
const check = (condition, name) => { assert.ok(condition, name); checks++; };
const display = (rows) => ({schemaVersion:'finance-display.v0.1',cards:[{type:'balance',scope:'AUTHORIZED_ACCOUNTS_RETURNED',rows,source:{sourceSystem:'controlled-source',asOf:'2026-09-10T00:00:00Z'}}]});
for (const balanceCard of ['balance-summary-v1','balance-ledger-v1']) {
  const config = {...defaultUI(),balanceCard};
  const output = cards(display([{accountLabel:'account-a',amount:'90071992547409.91',currency:'CNY',availability:'AVAILABLE'},
    {accountLabel:'account-b',amount:'0.00',currency:'USD',availability:'AVAILABLE'},
    {accountLabel:'account-c',amount:'123.45',currency:'HKD',availability:'MISSING'}]),config);
  check(output.includes('90,071,992,547,409.91'), balanceCard + ': precise decimal');
  check(output.includes('0.00') && output.includes('USD'), balanceCard + ': zero is retained');
  check(!output.includes('123.45') && output.includes('未返回'), balanceCard + ': unavailable amount not presented as fact');
  check(output.includes('controlled-source') && output.includes('2026-09-10T00:00:00Z') && output.includes('本次获授权'), balanceCard + ': source, time and scope');
  check(!output.includes('device-frame'), balanceCard + ': no device shell in generated Agent');
  const escaped = cards(display([{accountLabel:'<img onerror="alert(1)">',amount:'2.00',currency:'CNY',availability:'AVAILABLE'}]),config);
  check(!escaped.includes('<img') && escaped.includes('&lt;img'), balanceCard + ': untrusted labels escaped');
}
check(decimal('50000') === '50,000' && decimal('5e4') === '未返回', 'no floating-point reparsing');
check(cards({schemaVersion:'unknown',cards:[]}).includes('版本暂不支持'), 'unknown contract rejected');
const gapInput = {schemaVersion:'finance-display.v0.1',cards:[{type:'gaps',readiness:'PARTIAL_FOR_DESCRIPTION',gaps:[
  {field:'assets.coverage',reason:'NOT_COVERED'},
  {field:'assets.scope',reason:'MISSING'},
  {field:'assets.missingSources:外部资产',reason:'NOT_COVERED'},
  {field:'risk.scheme',reason:'MISSING'},
  {field:'future.unknown',reason:'UNAVAILABLE'},
  {field:'assets.missingSources:<img onerror=alert(1)>',reason:'NOT_COVERED'},
]}]};
const originalGaps = JSON.stringify(gapInput), renderedGaps = cards(gapInput);
check(renderedGaps.includes('>资产覆盖范围：未覆盖</li>'), 'coverage business label');
check(renderedGaps.includes('>资产统计范围：未返回</li>'), 'scope business label');
check(renderedGaps.includes('>未覆盖来源：外部资产：未覆盖</li>'), 'source prefix label');
check(renderedGaps.includes('>风险评级体系：未返回</li>'), 'existing risk label retained');
check(renderedGaps.includes('>future.unknown：来源不可用</li>'), 'unknown field not reinterpreted');
check(!renderedGaps.includes('<img') && renderedGaps.includes('&lt;img'), 'source suffix escaped');
check(renderedGaps.includes('data-field="assets.coverage"') && JSON.stringify(gapInput)===originalGaps, 'raw keys retained without changing evidence');
check(!readFileSync('examples/agent-experience/app.js','utf8').includes('device-frame'), 'generated Agent JS excludes Builder shells');
check(!readFileSync('examples/agent-experience/style.css','utf8').includes('.device-frame'), 'generated Agent CSS excludes Builder shells');
console.log(JSON.stringify({checksPassed:checks,scope:'SHARED_RENDERER_OFFLINE'}));
