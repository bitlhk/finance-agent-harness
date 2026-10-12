import assert from 'node:assert/strict';
import {build} from 'esbuild';
const bundle=await build({entryPoints:['browser/execution-history.js'],bundle:true,write:false,format:'esm'});
const {snapshotSummary,snapshotBody,snapshotState}=await import('data:text/javascript;base64,'+Buffer.from(bundle.outputFiles[0].text).toString('base64'));
assert.equal(snapshotState('REDACTED'),'已脱敏');
assert.equal(snapshotState('FILTERED'),'已过滤/脱敏');
assert.equal(snapshotState('BODY_NOT_VERIFIED'),'待读取核验');
assert.equal(snapshotBody({body:'{"messages":[{"content":"hello"}]}'}),JSON.stringify({messages:[{content:'hello'}]},null,2));
assert.equal(snapshotBody({body:'partial {'}),'partial {');
assert.equal(snapshotBody({}),'正文不可用');
assert.match(snapshotSummary({state:'CAPTURED'}),/处理明细未记录/);
assert.match(snapshotSummary({state:'NOT_CAPTURED',reason:'BODY_UNAVAILABLE_OR_NOT_FLUSHED'}),/正文不可用/);
assert.match(snapshotSummary({state:'CAPTURED',changes:[]}),/未记录过滤或截断/);
for (const reason of ['COLLECTION_LIMIT','PROPERTY_LIMIT','DEPTH_LIMIT','NODE_LIMIT','BYTE_LIMIT',
                     'REDACTED','FIELD_NOT_ALLOWLISTED','PROPERTY_NAME_FILTERED','UNSUPPORTED_OBJECT','UNSUPPORTED_VALUE']) {
  const result=snapshotSummary({state:'TRUNCATED',changes:[{reason,path:'/tools/0/function/parameters/properties',
    originalCount:129,retainedCount:128,pathAbbreviated:true}],changesOmitted:2});
  assert.match(result,/已截断/);assert.match(result,/原始 129，保留 128/);
  assert.match(result,/路径已缩短/);assert.match(result,/另有 2 项/);
  assert.match(result,/不证明模型 HTTP 已发送/);
}
console.log('snapshot summary: legacy, unavailable body, loss paths/counts and capture boundary passed');
