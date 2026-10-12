import argparse
import hashlib
import json
import os
from pathlib import Path
import sys

B=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(B/'src'),str(B.parent/'base/src'),str(B/'engineering')]
from verify_local_service import Endpoints,expiry
from finance_solution_builder.builder import SolutionBuilder
from finance_solution_builder.python_service import python_solution
from finance_solution_builder.candidate_backend.openjiuwen_python import OpenJiuwenPythonBackend
from finance_solution_builder.candidate_backend.models import *
from finance_builder_base import ResourceKind

parser=argparse.ArgumentParser()
parser.add_argument('--harness',required=True,type=Path)
parser.add_argument('--output',required=True,type=Path)
parser.add_argument('--python',default=sys.executable)
args=parser.parse_args()
root=args.output.resolve()
root.mkdir(exist_ok=True)
fixture=Endpoints()
policy=root/'policy.json'
policy.write_text(json.dumps({'schemaVersion':'local-simulator-access.v0.1','bankUrl':fixture.base,
    'subjects':{fixture.customer:{'enabled':True,'expiresAt':expiry(),'scopes':['account:read']}}}))
os.environ.update(FH_BANK_URL=fixture.base,FH_BANK_LOGIN='test',FH_BANK_SECRET='test',FH_ACCESS_POLICY=str(policy),
    FH_MODEL_URL=fixture.base+'/v1',FH_MODEL_NAME='controlled',FH_MODEL_KEY='synthetic',FH_MODEL_MODE='CONTROLLED_TEST',
    FH_PYTHON_EXECUTABLE=args.python)
identity='agent-python-backend-final'
output=root/'workbench'/identity
solution=python_solution(['account.balance.query'],identity)
builder=SolutionBuilder(B,args.harness.resolve(),service_output_root=root)
backend=OpenJiuwenPythonBackend(builder=builder,solution=solution,output=output)
target=CandidateTarget(language='python',runtime_family='openjiuwen',runtime_version='0.1.18',
    artifact_kind='directory-manifest',invocation_protocol='finance-chat',required_operations=tuple(LifecycleOperation),
    required_asset_ids=('account-balance-readonly',),required_capability_ids=('account.balance.query',),
    required_provider_operation_types=('query',),profile_id='local-read-only',host_wiring_ids=('owned-java-pipe',))
binding=hashlib.sha256(json.dumps(solution,sort_keys=True,separators=(',',':')).encode()).hexdigest()
ref=CandidateResourceRef(kind=ResourceKind.WORKSPACE_PATH,locator='solution.json',sha256=binding)
checks=[]
def check(name,passed,result=None):
    checks.append(dict(name=name,passed=bool(passed),result=result.to_wire() if result else None))
    (root/'checks.json').write_text(json.dumps(checks,indent=2))
    assert passed,name
try:
    a=backend.assemble(AssemblyRequest(task_id=identity,target=target,lock_digest=binding,workspace=ref,asset_refs=(ref,)))
    check('native-backend-assemble',a.outcome.status==LifecycleStatus.PASSED,a)
    built=backend.build(BuildRequest(task_id=identity,candidate_id=identity,lock_digest=binding,assembly_refs=a.assembly_refs))
    check('native-backend-real-build',built.outcome.status==LifecycleStatus.PASSED,built)
    descriptor=built.descriptor
    start=StartRequest(descriptor=descriptor,artifact_verification=verify_candidate_artifact(descriptor,output))
    started=backend.start(start)
    check('native-backend-start',started.outcome.status==LifecycleStatus.PASSED,started)
    handle=started.handle
    health=backend.health(HealthRequest(handle=handle))
    check('native-backend-health',health.ready,health)
    payload=output/'question.json';payload.write_text(json.dumps({'query':'balance-probe BACKEND_NATIVE'}))
    invocation=backend.invoke(InvocationRequest(request_id='probe-one',handle=handle,protocol='finance-chat',
        payload_ref=CandidateResourceRef(kind=ResourceKind.WORKSPACE_PATH,locator='question.json',
            sha256=hashlib.sha256(payload.read_bytes()).hexdigest())))
    check('native-backend-invoke',invocation.outcome.status==LifecycleStatus.PASSED,invocation)
    check('native-backend-business-observed',len(fixture.reads)==1 and len(fixture.models)==2)
    evidence=backend.collect_evidence(EvidenceRequest(handle=handle))
    check('native-backend-evidence',bool(evidence.outcome.evidence_refs),evidence)
    instance=json.loads((output/'candidate/instance.json').read_text())
    stopped=backend.stop(StopRequest(handle=handle))
    check('native-backend-stop',stopped.stopped,stopped)
    check('owned-parent-child-reaped',not Path('/proc/'+str(instance['pid'])).exists()
        and not Path('/proc/'+str(instance['bridgePid'])).exists())
    restarted=backend.start(start)
    check('native-backend-restart-new-handle',restarted.handle is not None and restarted.handle!=handle,restarted)
    check('old-handle-health-rejected',not backend.health(HealthRequest(handle=handle)).ready)
    check('old-stop-does-not-stop-new',backend.stop(StopRequest(handle=handle)).stopped
        and backend.health(HealthRequest(handle=restarted.handle)).ready)
    check('new-stop',backend.stop(StopRequest(handle=restarted.handle)).stopped)
    check('zero-business-writes',not fixture.writes)
finally:
    if backend._jobs: backend._jobs.close()
    fixture.close()
print(json.dumps({'passed':len(checks)}))
