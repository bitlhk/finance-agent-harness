import assert from 'node:assert/strict';
import {readingGroups,membersOf,primaryMembers,groupSummary,relatedGroups,relationText} from '../browser/asset-reading.js';
const value={bindings:[{},{}],validation:{},composition:{nodes:[
 {id:'skill',kind:'SKILL',label:'月度核验',status:'AVAILABLE',group:'dependencies'},
 {id:'pack',kind:'DOMAIN_PACK',label:'核验方法包（候选）',status:'AVAILABLE',group:'dependencies'},
 {id:'cap',kind:'CAPABILITY',label:'摘要',status:'DECLARED'},
 {id:'p1',kind:'EXECUTION_ARTIFACT',label:'适配一',status:'REFERENCE_ONLY'},
 {id:'p2',kind:'EXECUTION_ARTIFACT',label:'适配二',status:'MISSING'},
 {id:'schema',kind:'CONTRACT',label:'通用能力结果外壳',status:'AVAILABLE'},
 {id:'odd',kind:'UNKNOWN',label:'未来资产',status:'CONFLICT'}],
 readingGroups:[{id:'methods',memberIds:['skill','pack']},{id:'capabilities',memberIds:['cap']},{id:'providers',memberIds:['p1','p2']},{id:'contracts',memberIds:['schema']},{id:'validation',memberIds:[]},{id:'other',memberIds:['odd']}],
 relations:[{source:'skill',target:'cap',label:'使用 · 声明引用'},{source:'p1',target:'cap',label:'实现'}]}};
const original=JSON.stringify(value);
assert.equal(readingGroups(value).length,6);
assert.deepEqual(primaryMembers(value,value.composition.readingGroups[0]).map(n=>n.id),['skill']);
assert.equal(membersOf(value,value.composition.readingGroups[0])[1].label,'核验方法包（候选）');
assert.equal(groupSummary(value,value.composition.readingGroups[0]).summary,'月度核验');
assert.equal(groupSummary(value,value.composition.readingGroups[2]).count,2);
assert.equal(groupSummary(value,value.composition.readingGroups[2]).attention,true);
assert(groupSummary(value,value.composition.readingGroups[3]).note.includes('不等于领域合同'));
assert(groupSummary(value,value.composition.readingGroups[4]).note.includes('尚无'));
assert.deepEqual([...relatedGroups(value,'cap')],['methods','capabilities','providers']);
assert.equal(relationText(value.composition.relations[0],'skill'),'声明使用');
assert.equal(relationText({source:'pack',target:'cap',label:'声明可用能力'},'pack'),'声明可用能力范围');
assert.equal(JSON.stringify(value),original,'Presentation must not rewrite source facts');
const empty={...value,composition:{...value.composition,nodes:[]}};
assert.equal(groupSummary(empty,value.composition.readingGroups[0]).summary,'不包含独立方法');
console.log('13 asset reading checks passed');
