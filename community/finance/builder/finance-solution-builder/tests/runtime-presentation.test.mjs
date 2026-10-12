import assert from 'node:assert/strict';
import {test} from 'node:test';
import {runtimePresentation, selectionSchemaVersion, runtimeHostBinding, runtimeStatusLabel} from '../browser/runtime-presentation.js';

test('frozen runtime display wins over renamed current directory', () => {
  const build = {selection:{runtimeId:'native-query'}, runtimePresentation:{runtimeId:'native-query',
    label:'Pinned runtime', language:'java', version:'2.0.3', architectureLabel:'Pinned artifact'}};
  const view = runtimePresentation(build, [{id:'native-query',label:'Renamed runtime',version:'9'}]);
  assert.equal(view.label, 'Pinned runtime');
  assert.equal(view.detail, 'java · 2.0.3');
  assert.equal(view.architecture, 'Pinned artifact');
  assert.equal(view.frozen, true);
});

test('a mismatched frozen identity is not used for another runtime', () => {
  const build = {selection:{runtimeId:'other'}, runtimePresentation:{runtimeId:'native-query',label:'Wrong'}};
  assert.equal(runtimePresentation(build).label, 'other');
  assert.equal(runtimePresentation(build).frozen, false);
});

test('legacy records remain readable without inventing framework names', () => {
  assert.equal(runtimePresentation({selection:{runtimeId:'legacy-runtime'}}).label, 'legacy-runtime');
  assert.equal(runtimePresentation({}).detail, '版本未记录');
  assert.equal(runtimePresentation({selection:{runtimeId:'old'}}, [{id:'old',label:'Old runtime'}]).label, 'Old runtime');
});

test('selection request version comes from admitted runtime metadata', () => {
  assert.equal(selectionSchemaVersion({selectionSchemaVersion:'0.10'}), '0.10');
  assert.equal(selectionSchemaVersion({id:'unfamiliar-runtime'}), '0.9');
  assert.equal(selectionSchemaVersion(), '0.9');
});

test('runtime status distinguishes pilot admission from formal acceptance', () => {
  assert.equal(runtimeStatusLabel({status:'AVAILABLE',candidateEnabled:true}),'试点接入');
  assert.equal(runtimeStatusLabel({status:'CANDIDATE',enabled:false}),'待验证');
  assert.equal(runtimeStatusLabel({status:'AVAILABLE',candidateEnabled:false}),'已接入');
  assert.equal(runtimeStatusLabel(),'待接入');
});

const profile = {id:'customer',version:'1',source:'repo://profiles/customer.md'};
const host = {hostId:'native-host',profileRef:profile,applicationTemplateIds:['terminal'],presentations:['MOBILE_WEB']};
const runtime = {id:'any-native-runtime',selectionSchemaVersion:'0.10',hostOptions:[host]};
const provider = {id:'bank-reference',version:'1'};
const bindings = [{pack:{id:'unfamiliar-package',version:'1'},provider}];
const entries = [{pack:bindings[0].pack,runtimeHostOptions:[{runtimeId:runtime.id,hostId:host.hostId,profileRef:profile,provider}]}];
const bind = (rt=runtime, bs=bindings, es=entries, terminal='MOBILE_WEB') => runtimeHostBinding(rt,bs,es,terminal,'terminal');

test('an explicit host and profile are derived only from compatible declarations', () => {
  assert.deepEqual(bind(),{fields:{hostId:'native-host',profileRef:profile},error:null});
  assert.notEqual(bind().fields.profileRef,profile);
  assert.deepEqual(runtimeHostBinding({},[],[],'MOBILE_WEB','terminal'),{fields:{},error:null});
});

test('two matches never silently choose the first host', () => {
  assert.equal(bind({...runtime,hostOptions:[host,host]}).error,'MANAGED_HOST_AMBIGUOUS');
});

test('a unique narrower operation set wins, without guessing between equivalent hosts', () => {
  const query = {...host, operations:['QUERY']};
  const task = {...host, hostId:'task-host', operations:['QUERY','TASK']};
  const both = [{...entries[0], runtimeHostOptions:[...entries[0].runtimeHostOptions,
    {...entries[0].runtimeHostOptions[0],hostId:task.hostId}]}];
  assert.equal(bind({...runtime,hostOptions:[task,query]},bindings,both).fields.hostId,query.hostId);
  assert.equal(bind({...runtime,hostOptions:[query,task]},bindings,both).fields.hostId,query.hostId);
  assert.equal(bind({...runtime,hostOptions:[query,{...task,operations:['QUERY']}]},bindings,both).error,'MANAGED_HOST_AMBIGUOUS');
  assert.equal(bind({...runtime,hostOptions:[{...query,operations:undefined},task]},bindings,both).error,'MANAGED_HOST_AMBIGUOUS');
  assert.equal(bind({...runtime,hostOptions:[task]},bindings,both).fields.hostId,task.hostId);
});

test('runtime, provider version, profile source and presentation must all match', () => {
  for(const changes of [{runtimeId:'other'},{provider:{...provider,version:'2'}},
    {profileRef:{...profile,source:'repo://profiles/other.md'}},{hostId:'other'}]) {
    assert.equal(bind(runtime,bindings,[{...entries[0],runtimeHostOptions:[{...entries[0].runtimeHostOptions[0],...changes}]}]).error,
      'MANAGED_HOST_BINDING_MISMATCH');
  }
  assert.equal(bind(runtime,bindings,entries,'ATM_WEB').error,'MANAGED_HOST_BINDING_MISMATCH');
  assert.equal(bind({...runtime,hostOptions:[{...host,declarationStatus:'UNAVAILABLE'}]}).error,'MANAGED_HOST_BINDING_MISMATCH');
});

test('every package needs the same supported host without dropping an unknown reference', () => {
  assert.equal(bind(runtime,[...bindings,{pack:{id:'second',version:'1'},provider}]).error,'MANAGED_HOST_BINDING_MISMATCH');
  assert.equal(bind(runtime,bindings,[...entries,...entries]).error,'MANAGED_HOST_BINDING_MISMATCH');
  const second = {pack:{id:'second',version:'1'},provider};
  assert.equal(bind(runtime,[...bindings,second],[...entries,{...entries[0],pack:second.pack}]).error,null);
});
