import assert from 'node:assert/strict';
import {supportView} from '../browser/asset-support.js';
const esc=s=>String(s).replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;').replaceAll('"','&quot;');
assert.equal(supportView(null,esc),'');
const html=supportView({status:'AVAILABLE',subjects:[{id:'sample',version:'2'}],methodCases:[{name:'normal',query:'synthetic query',expectedTerms:['test-only']}],commands:[{name:'test',command:'<script>alert(1)</script>',sourceCommit:'fixed',workingDirectory:'repo://'}],validation:{status:'VERSION_MISMATCH',reason:'证据属于其他版本'}},esc);
assert(html.includes('sample @ 2'));
assert(html.includes('已提供适用案例'));
assert(html.includes('证据属于其他版本'));
assert(html.includes('test-only')&&html.includes('不装入 Agent 提示'));
assert(!html.includes('<script>')&&html.includes('&lt;script&gt;'));
assert(!html.includes('onclick=')&&!html.includes('data-action='));
assert(html.includes('源码提交：fixed'));
assert(supportView({status:'SCOPE_UNRESOLVED'},esc).includes('请先确定验证环境'));
assert(supportView({status:'NOT_DECLARED'},esc).includes('未提供组件验证资料'));
const derived=supportView({status:'AVAILABLE',validation:{status:'NOT_RUN',reason:'组件尚无直接验证'},
  derivedMethodVerifications:[{name:'自己的方法',assetRef:{assetId:'method-one',version:'1.0.0',releaseDigest:'sha256:test'},
    actualRuntime:{family:'openjiuwen',language:'java',version:'0.1.15'},baseMatch:'MATCHED',
    validation:{status:'PASS',reason:'同内容运行检查通过'},missingFields:['scope.hostId','executedAt']}],
  reportScan:{status:'TRUNCATED',truncated:true}},esc);
assert(derived.includes('组件尚无直接验证')&&derived.includes('同内容运行检查通过'));
assert(derived.includes('自己的方法 · v1.0.0'));
assert(derived.includes('openjiuwen · java · 0.1.15'));
assert(derived.includes('宿主版本、实际执行时刻'));
assert(derived.includes('报告记录时间</dt><dd>未记录'));
assert(derived.includes('data-method-action="verification-version"'));
assert(derived.includes('data-asset="method-one"'));
assert(derived.includes('未覆盖全部历史报告'));
assert(derived.includes('不代表原组件或其他 Runtime 已通过'));
console.log('19 component verification view checks passed');
