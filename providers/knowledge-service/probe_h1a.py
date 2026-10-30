"""Exercise real Builder admission; never enable a fictitious Host."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys


def run(builder, output):
    harness=Path(__file__).resolve().parents[2]
    base=builder/'community/finance/builder'
    project=base/'finance-solution-builder'
    sys.path[:0]=[str(project/'src'),str(base/'base/src')]
    from finance_solution_builder.builder import SolutionBuilder
    from finance_solution_builder.service import build_service
    import yaml
    pack=yaml.safe_load((harness/'capability-packs/knowledge-evidence-readonly/pack.yaml').read_text())
    assert [c['capabilityId'] for c in pack['capabilities']]==['knowledge.evidence.search']
    assert pack['capabilities'][0]['requiredScopes']==['knowledge:read']
    solution=json.loads((Path(__file__).parent/'h1a/solution.json').read_text())
    assert solution['capabilityBindings']=={'knowledge.evidence.search':'knowledge-service'}
    assert solution['domainPackRefs']==[]
    assert solution['evaluationPackRef']['id']=='knowledge-evidence-h1a'
    report={'schemaVersion':'knowledge-h1a-admission.v0.1','solution':solution,
        'builderCommit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=builder,text=True).strip(),
        'harnessCommit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=harness,text=True).strip(),
        'builderDirty':bool(subprocess.check_output(['git','status','--porcelain'],cwd=builder,text=True)),
        'harnessDirty':bool(subprocess.check_output(['git','status','--porcelain'],cwd=harness,text=True)),
        'level':'REAL_BUILDER_ADMISSION_NOT_AGENT_INSTALL',
        'installationLock':None,'agentUiToolCall':'NOT_RUN','productionModified':False}
    template=project/'templates/local-readonly-service'
    files=['host-support.v0.1.json','src/main/java/io/github/bitlhk/finance/builder/local/ReadOnlyProviders.java',
           'src/main/java/io/github/bitlhk/finance/builder/local/NativeRuntime.java',
           'src/main/java/io/github/bitlhk/finance/builder/local/LocalAgentHost.java']
    report['hostFiles']={f:hashlib.sha256((template/f).read_bytes()).hexdigest() for f in files}
    report['solutionSha256']=hashlib.sha256((Path(__file__).parent/'h1a/solution.json').read_bytes()).hexdigest()
    output.mkdir(parents=True,exist_ok=False)
    candidate=output/'builder-attempt'
    try:
        build_service(SolutionBuilder(project,harness,service_output_root=output),solution,candidate,True,managed=True)
    except ValueError as exc:
        report.update(status='ADMISSION_BLOCKED',error=str(exc),candidateCreated=candidate.exists())
    else:raise RuntimeError('Unexpected admission; inspect before running any Agent')
    assert report['error']=='ASSEMBLY_PROVIDER_CONNECTION_MISMATCH'
    (output/'h1a-admission.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'status':report['status'],'error':report['error'],'tools':[c['toolName'] for c in pack['capabilities']]}))


if __name__=='__main__':
    ap=argparse.ArgumentParser();ap.add_argument('--builder',type=Path,required=True);ap.add_argument('--output',type=Path,required=True)
    args=ap.parse_args();run(args.builder.resolve(),args.output.resolve())
