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
fs.writeFileSync(path.join(output,'previous.json'),JSON.stringify(current.compatibility));
const unknown = JSON.parse(run('unknown-impact',[...args,'--previous',path.join(output,'previous.json'),'--changed-path','unknown-example.md'],undefined,2));
assert.equal(unknown.impact.status,'UNKNOWN_REQUIRES_REVIEW');
assert.equal(unknown.impact.qualificationReuse,'REVIEW_REQUIRED');
fs.writeFileSync(path.join(output,'RESULT.json'),JSON.stringify({status:'PASS',commands:records.length,
  scope:'documentation snippets only; no build or business',snippetSha256:createHash('sha256').update(snippet).digest('hex')},null,2));
console.log(JSON.stringify({status:'PASS',commands:records.length,output}));
