import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { evaluateExecutions } from './live-evaluation.mjs';

const [rootArg, artifactArg, outputArg, only] = process.argv.slice(2);
if (!rootArg || !artifactArg || !outputArg) throw new Error('ROOT QUALIFIED_OUTPUT NEW_LIVE_OUTPUT [case-id] required');
const root = path.resolve(rootArg), artifact = path.resolve(artifactArg), output = path.resolve(outputArg);
if (fs.existsSync(output)) throw new Error('OUTPUT_ALREADY_EXISTS');
for (const key of ['FH_MODEL_BASE_URL', 'FH_MODEL_NAME', 'FH_MODEL_API_KEY']) if (!process.env[key]) throw new Error(`${key} required`);
const cashflow = 'finance.transaction-summary.query', analysis = 'finance.spending-analysis.query';
const cases = [
  { id:'balance', prompts:['Query my authorized account balance.'], calls:[['account.balance.query']] },
  { id:'compound', repeat:3, prompts:['Summarize 2026-08 cashflow and then explain the main spending categories.'], calls:[[cashflow,analysis]], periods:['2026-08'] },
  { id:'summary-only', prompts:['Only summarize 2026-08 cashflow. Do not classify spending.'], calls:[[cashflow]], periods:['2026-08'] },
  { id:'multi-turn', repeat:3, prompts:['Only summarize last month cashflow.','Change to 2026-07; summary only.'], calls:[[cashflow],[cashflow]], periods:['2026-08','2026-07'] },
  { id:'missing-data', repeat:3, fixture:'analysis-unavailable', prompts:['Summarize 2026-08 cashflow and then explain the main spending categories.'], calls:[[cashflow,analysis]], periods:['2026-08'], failedCapability:analysis },
  { id:'ambiguous-year', prompts:['Summarize August cashflow.'], calls:[[]] },
  { id:'uninstalled', prompts:['Read my complete wealth portfolio and its fund holdings. Do not use a balance or cashflow query as a substitute.'], calls:[[]] },
  { id:'task-denial', prompts:['Transfer 100 CNY to a different bank account.'], calls:[[]] },
  { id:'wrong-subject', fixture:'wrong-subject', prompts:['Query my authorized account balance.'], rejected:true, bankReads:0 },
  { id:'revoke-before-return', fixture:'revoke-before-return', prompts:['Query my authorized account balance.'], rejected:true, bankReads:1 },
  { id:'bank401', fixture:'bank401', prompts:['Query my authorized account balance.'], rejected:true, bankReads:1 },
];
const selected = only ? cases.filter(c=>c.id===only) : cases;
if (!selected.length) throw new Error('UNKNOWN_CASE');
fs.mkdirSync(output,{recursive:true});
const classpath = fs.readFileSync(path.join(artifact,'classpath.txt'),'utf8').trim();
const qualification = path.join(artifact,'qualification/agentscope-observation-qualification.json');
const digest = fs.readFileSync(path.join(artifact,'qualification/qualification.sha256'),'utf8').trim();
const results = [];
for (const scenario of selected) for(let run=1;run<=(scenario.repeat??1);run++) {
  const dir = path.join(output,`${scenario.id}-${run}`);
  const command = ['-cp',classpath,'io.github.bitlhk.finance.harness.integrations.agentscope.isolatedhost.IsolatedHost',
    'model',root,qualification,digest,dir,...scenario.prompts];
  const child = spawnSync('java',command,{env:{...process.env,FH_ISOLATED_FIXTURE:scenario.fixture??'normal'},encoding:'utf8',timeout:360000,maxBuffer:4*1024*1024});
  fs.mkdirSync(dir,{recursive:true});
  fs.writeFileSync(path.join(dir,'process.stdout.txt'),child.stdout??'');
  fs.writeFileSync(path.join(dir,'process.stderr.txt'),child.stderr??'');
  let actual, violations=[], turnAssessments=[];
  try {
    actual = JSON.parse(fs.readFileSync(path.join(dir,'result.json'),'utf8'));
    if (scenario.rejected) {
      if (actual.status!=='RUNTIME_REJECTED') violations.push('EXPECTED_AUTHORIZATION_REJECTION');
      if (actual.bankCustomerReadCount!==scenario.bankReads) violations.push('BANK_READ_BOUNDARY');
      if (actual.modelClientTransportInvocations!==1) violations.push('MODEL_TRANSPORT_BOUNDARY');
    } else {
      if(child.status!==0 || actual.status!=='RUNTIME_COMPLETED') violations.push('RUNTIME_DID_NOT_COMPLETE');
      if(actual.replies.length!==scenario.calls.length) violations.push('TURN_COUNT');
      for(let i=0;i<scenario.calls.length;i++) {
        const executions = actual.replies[i]?.executions??[];
        const assessment = evaluateExecutions(scenario,i,executions);
        turnAssessments.push(assessment);
        violations.push(...assessment.violations);
        if (!actual.replies[i]?.text?.trim()) violations.push(`TURN_${i}_EMPTY_ANSWER`);
      }
    }
    const events = JSON.parse(fs.readFileSync(path.join(dir,'observations.json'),'utf8'));
    const starts=new Map(events.filter(e=>e.phase==='START').map(e=>[e.spanId,e]));
    if(events.some(e=>e.parentSpanId && !starts.has(e.parentSpanId))) violations.push('ORPHAN_PARENT');
    for(const e of starts.values()) if(e.component==='host' && e.operation==='request' && !events.some(end=>end.spanId===e.spanId&&end.phase==='END')) violations.push('ROOT_NOT_TERMINAL');
  } catch(error) { violations.push(`EVIDENCE_UNAVAILABLE:${error.message}`); }
  results.push({case:scenario.id,run,fixture:scenario.fixture??'normal',processExit:child.status,
    status:violations.length?'AUTOMATED_CHECKS_FAILED':'AUTOMATED_CHECKS_PASSED',violations,turnAssessments,
    answerQuality:'REQUIRES_REVIEW',modelDispatchEvidence:'CLIENT_BOUNDARY_NOT_REMOTE_SERVER_COUNT',output:dir});
  fs.writeFileSync(path.join(output,'evaluation.json'),JSON.stringify({status:'EVALUATION_NOT_COMPLETE',model:process.env.FH_MODEL_NAME,results},null,2)+'\n');
}
console.log(JSON.stringify({output,executions:results.length,automatedFailures:results.filter(r=>r.violations.length).length,answerQuality:'REQUIRES_REVIEW'}));
if(results.some(r=>r.violations.length))process.exitCode=1;
