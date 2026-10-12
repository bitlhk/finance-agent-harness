import assert from 'node:assert/strict';
import {graphProjection,graphLayout,edgeLabel} from '../browser/asset-graph.js';
const node=(id,kind,version='1')=>({id,kind,label:id,reference:{version},files:[]});
const nodes=[node('method','SKILL'),node('first','CAPABILITY'),node('second','CAPABILITY'),
  node('provider@1','EXECUTION_ARTIFACT'),node('provider@2','EXECUTION_ARTIFACT','2'),
  node('missing','EXECUTION_ARTIFACT'),node('input','DECLARATION'),node('output','CONTRACT'),node('pack','DOMAIN_PACK'),node('unknown','NEW_TYPE')];
nodes[4].label=nodes[3].label='同名适配器';nodes[5].status='MISSING';
const edge=(source,target,label)=>({source,target,label});
const relations=[edge('method','first','使用 · 声明引用'),edge('method','second','使用 · 声明引用'),
  edge('provider@1','first','实现'),edge('provider@2','second','实现'),edge('missing','second','实现'),
  edge('first','input','遵循 · 输入声明'),edge('second','input','遵循 · 输入声明'),
  edge('first','output','遵循 · 结果外壳'),edge('pack','method','包含')];
const value={composition:{nodes,relations}},before=JSON.stringify(value);
const projected=graphProjection(value);assert.equal(projected.nodes.length,8);assert.equal(projected.edges.length,8);
assert.equal(projected.nodes.filter(n=>n.id==='input').length,1,'Shared contract stays one node');
assert.equal(projected.nodes.filter(n=>n.label==='同名适配器').length,2,'Versions do not collapse');
assert(projected.nodes.some(n=>n.status==='MISSING'));assert(projected.auxiliary.some(n=>n.id==='unknown'));
assert(projected.edges.every(e=>relations.includes(e)),'No invented edges');
assert(projected.edges.filter(e=>e.label==='实现').every(e=>nodes.find(n=>n.id===e.source).kind==='EXECUTION_ARTIFACT'));
assert.equal(edgeLabel(relations[0]),'声明使用');assert.equal(edgeLabel(relations[7]),'遵循结果外壳');
const noSkill=graphProjection({composition:{nodes:nodes.filter(n=>n.kind!=='SKILL'),relations:relations.filter(e=>e.source!=='method'&&e.target!=='method')}});
assert(!noSkill.nodes.some(n=>n.kind==='SKILL'));
const focused=graphProjection(value,'first');assert(focused.hidden>0);assert(focused.nodes.some(n=>n.id==='input'));
assert.equal(JSON.stringify(value),before,'Projection must not mutate evidence');
const layout=await graphLayout(projected);assert(layout.width>0&&layout.height>0);
assert.equal(layout.edges.length,projected.edges.length);
const overlap=(a,b)=>a.x<b.x+b.width&&a.x+a.width>b.x&&a.y<b.y+b.height&&a.y+a.height>b.y;
for(const [i,a] of layout.children.entries())for(const b of layout.children.slice(i+1))assert(!overlap(a,b),'Node collision');
const labels=layout.edges.flatMap(e=>e.labels||[]);
for(const label of labels)for(const box of layout.children)assert(!overlap(label,box),'Label overlaps node');
for(const [i,a] of labels.entries())for(const b of labels.slice(i+1))assert(!overlap(a,b),'Label collision');
for(const e of layout.edges){assert(e.sections?.length);assert.equal(e.sources[0],projected.edges[Number(e.id.split('-')[1])].source);}
console.log('PASS: projection, sharing, versions, missing references, no Skill, focus and layout geometry');
