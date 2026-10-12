import assert from 'node:assert/strict';
import {build} from 'esbuild';
globalThis.location = {pathname:'/experience/test/'};
const bundle = await build({entryPoints:['browser/presentation.js'],bundle:true,write:false,format:'esm'});
const {cards} = await import('data:text/javascript;base64,'+Buffer.from(bundle.outputFiles[0].text).toString('base64'));
const card = {type:'records',version:'0.1',title:'Monthly summary',scope:'Current page only',
  columns:[{key:'amount',label:'Amount'},{key:'currency',label:'Currency'}],
  rows:[{amount:'90071992547409.91'}, {amount:'<script>alert(1)</script>',currency:'CNY'}],
  context:{dateFrom:'2026-08-01',dateTo:'2026-08-31',hasMore:true},source:{sourceSystem:'synthetic'}};
const render = card => cards({schemaVersion:'finance-display.v0.1',cards:[card]});
const html = render(card);
assert(html.includes('90071992547409.91'));
assert(!html.includes('<script>')); assert(html.includes('&lt;script&gt;'));
assert(html.includes('未提供')); assert(html.includes('仍有后续页面'));
assert(html.includes('2026-08-01')); assert(html.includes('2026-08-31'));
assert(render({...card,rows:[]}).includes('当前查询范围未返回记录'));
assert(render({...card,version:'2'}).includes('结果展示版本暂不支持'));
console.log('records: escaping, exact decimals, missing fields, page scope and version rejection passed');
