// Execute only the tutorial's deterministic assembly input and compatibility CLI.
// No Maven, generated Host, network provider, or model is started.
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
const root = path.resolve(new URL('../..',import.meta.url).pathname);
const builder = process.env.BUILDER_ROOT;
const python = process.env.TUTORIAL_PYTHON;
assert.ok(builder && python, 'Set BUILDER_ROOT and TUTORIAL_PYTHON');
const output = path.resolve(process.argv[2]);
fs.mkdirSync(output,{recursive:false});
const text = fs.readFileSync(path.join(root,'docs/public/first-agent.md'),'utf8');
const snippet = text.match(/<<'PY'\n([\s\S]*?)\nPY/)[1];
const env = {...process.env,PYTHONPATH:'src:../base/src'};
const records = [];
const run = (name,args,input,expected) => {
  const result = spawnSync(python,args,{cwd:builder,env,input,encoding:'utf8'});
  fs.writeFileSync(path.join(output,`${name}.stdout`),result.stdout || '');
  fs.writeFileSync(path.join(output,`${name}.stderr`),result.stderr || '');
  records.push({name,command:[python,...args],cwd:builder,exit:result.status,expected,
    stdoutSha256:createHash('sha256').update(result.stdout || '').digest('hex')});
  fs.writeFileSync(path.join(output,'commands.json'),JSON.stringify(records,null,2));
  assert.equal(result.status,expected,`${name}: ${result.stderr}`);
  return result.stdout;
};
run('create-solution',['-',output],snippet,0);
run('no-overwrite',['-',output],snippet,1);
const args = ['-m','finance_solution_builder.cashflow_compatibility','--harness',root,'--project',builder,'--solution',path.join(output,'solution.json')];
const current = JSON.parse(run('compatibility',args,undefined,0));
assert.equal(current.compatibility.status,'COMPATIBLE');
assert.equal(current.compatibility.required,true);
assert.deepEqual(current.compatibility.asset.consumers,['MOBILE_WEB','ATM_WEB']);
const baselineSnippet = text.match(/<<'BASELINE'\n([\s\S]*?)\nBASELINE/)[1];
run('save-baseline',['-',root,builder,output],baselineSnippet,0);
const originalPrevious = fs.readFileSync(path.join(output,'previous.json'));
assert.deepEqual(JSON.parse(originalPrevious),current.compatibility);
run('baseline-no-overwrite',['-',root,builder,output],baselineSnippet,1);
assert.ok(originalPrevious.equals(fs.readFileSync(path.join(output,'previous.json'))));
// Copy only files read by the actual compatibility contract, never caches or builds.
const candidate = path.join(output,'sparse-candidate');
for (const relative of Object.keys(current.compatibility.files)) {
  assert.ok(!relative.startsWith('/') && !relative.split('/').includes('..'));
  const target=path.join(candidate,relative);
  fs.mkdirSync(path.dirname(target),{recursive:true});
  fs.copyFileSync(path.join(root,relative),target);
}
const candidateArgs = [...args];
candidateArgs[candidateArgs.indexOf('--harness')+1] = candidate;
const same=JSON.parse(run('candidate-before-edit',candidateArgs,undefined,0));
assert.deepEqual(same,current);
const relative='domain-packs/cashflow/customer-self-service/skills/cashflow-review/SKILL.md';
const body=path.join(candidate,relative);
fs.appendFileSync(body,'\n最终回答分为“收支事实”和“覆盖与待核实”两段；每项数值保留来源、币种和月份。\n');
const changed=JSON.parse(run('candidate-after-edit',[...candidateArgs,'--previous',path.join(output,'previous.json'),'--changed-path',relative],undefined,0));
assert.equal(changed.impact.status,'AFFECTED');
assert.deepEqual(changed.impact.changedDependencies,[relative]);
assert.deepEqual(changed.impact.consumers,['MOBILE_WEB','ATM_WEB']);
assert.equal(changed.impact.qualificationReuse,'REVIEW_REQUIRED');
assert.notEqual(changed.compatibility.bindingDigest,current.compatibility.bindingDigest);
assert.ok(originalPrevious.equals(fs.readFileSync(path.join(output,'previous.json'))));
const unknown = JSON.parse(run('unknown-impact',[...args,'--previous',path.join(output,'previous.json'),'--changed-path','unknown-example.md'],undefined,2));
assert.equal(unknown.impact.status,'UNKNOWN_REQUIRES_REVIEW');
assert.equal(unknown.impact.qualificationReuse,'REVIEW_REQUIRED');
run('fixture-selection',['-c', `from pathlib import Path
from finance_solution_builder.method_trials import fixture_module
helper=fixture_module(Path(${JSON.stringify(builder)}),Path(${JSON.stringify(root)}))
data=helper.cashflow_data
assert set(data)=={'summary','summary-policy','analysis','analysis-policy'}
assert data['summary']['period']=='2026-08'
assert [data['summary'][k] for k in ('totalIncome','totalExpense','netCashflow')]==['10000.00','1250.50','8749.50']
assert data['summary-policy']['policies'][0]['evidenceRef']=='D05-003-SUMMARY-GOVERNANCE'
print('PASS: existing loader selected four source files; no Endpoints/Host/model started')`],undefined,0);
fs.writeFileSync(path.join(output,'RESULT.json'),JSON.stringify({status:'PASS',commands:records.length,
  scope:'documentation snippets only; no build or business',snippetSha256:createHash('sha256').update(snippet).digest('hex')},null,2));
console.log(JSON.stringify({status:'PASS',commands:records.length,output}));
