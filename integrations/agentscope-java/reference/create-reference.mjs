import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
const root = fileURLToPath(new URL('../../../', import.meta.url));
const output = path.join(root, 'integrations/agentscope-java/reference');
const hash = file => 'sha256:' + crypto.createHash('sha256').update(fs.readFileSync(path.join(root, file))).digest('hex');
function tree(dir) {
  const base=path.join(root,dir), files=[];
  const walk=(d)=>{for(const e of fs.readdirSync(d,{withFileTypes:true})){const f=path.join(d,e.name);if(e.isDirectory())walk(f);else files.push(f);}};
  walk(base); const h=crypto.createHash('sha256');
  for(const f of files.sort())h.update(path.relative(base,f)).update('\0').update(fs.readFileSync(f)).update('\0');
  return 'sha256:'+h.digest('hex');
}
const ref=(id,version,file,isTree=false)=>({id,version,source:'repo://'+file,digest:isTree?tree(path.dirname(file)):hash(file)});
const caps=[ref('account-balance-readonly','0.1.0','capability-packs/account-balance-readonly/pack.yaml'),ref('cashflow-readonly','0.1.0','capability-packs/cashflow-readonly/pack.yaml')];
const descriptions=['account-balance-readonly','cashflow-readonly'].map(n=>{const file=`routing/capability-descriptions/${n}.v0.1.json`;const data=JSON.parse(fs.readFileSync(path.join(root,file)));return ref(data.assetId,data.version,file);});
const termFile='routing/terminology/demo-bank-zh-cn.v0.1.json', term=JSON.parse(fs.readFileSync(path.join(root,termFile)));
const support={schemaVersion:'agentscope-query-support.v0.1',family:'agentscope',language:'java',sdkVersion:'2.0.3',operation:'QUERY',modelAttempts:1,toolAttempts:1,stream:false,fallback:false,pendingToolRecovery:false,
  descriptions,terminology:[ref(term.assetId,term.version,termFile)],observationRequirements:['balance','cashflow'].map(n=>ref(`readonly-${n}-observation`,'0.1.0',`observation-packs/readonly/${n}.json`))};
const solution={schemaVersion:'finance-agent-solution-build.v1',solutionId:'agentscope-balance-cashflow-isolated',displayName:'AgentScope QUERY isolation candidate',runtime:{family:'agentscope',language:'java',templateRef:'maven://io.agentscope:agentscope-core:2.0.3',integrationRef:'maven://io.github.bitlhk.finance:finance-harness-agentscope-java:0.1.0-SNAPSHOT'},domainPackRefs:[ref('cashflow-customer-self-service','0.1.3','domain-packs/cashflow/customer-self-service/pack.yaml',true)],capabilityPackRefs:caps,providerRefs:[ref('non-ai-banking-reference','0.1.0-SNAPSHOT','providers/non-ai-banking/assembly.v0.1.json')],capabilityBindings:{},modelRef:'host-managed-model'};
solution.profileRef=ref('local-read-only','0.1.0','profiles/local-read-only.md');
support.profiles=['local-read-only','cashflow-read-only'].map(id=>({profile:ref(id,'0.1.0',`profiles/${id}.md`),
  allowedCapabilityIds:[...(id==='local-read-only'?['account.balance.query']:[]),'finance.transaction-summary.query','finance.spending-analysis.query'],
  domainProfiles:['customer-self-service'],principalTypes:['CUSTOMER'],purposes:['customer-self-service']}));
const provider=JSON.parse(fs.readFileSync(path.join(root,'providers/non-ai-banking/assembly.v0.1.json')));
support.providers=[{...provider.owner,artifactRef:provider.artifactRef,connectionKind:provider.connectionKind}];
fs.writeFileSync(path.join(output,'solution.json'),JSON.stringify(solution,null,2)+'\n');
fs.writeFileSync(path.join(output,'support.json'),JSON.stringify(support,null,2)+'\n');
console.log('Generated reference declarations; source business packages unchanged.');
