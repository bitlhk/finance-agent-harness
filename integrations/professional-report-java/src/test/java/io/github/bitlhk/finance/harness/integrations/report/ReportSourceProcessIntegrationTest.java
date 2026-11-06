package io.github.bitlhk.finance.harness.integrations.report;

import io.github.bitlhk.finance.harness.report.ReportMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Real immutable source workers. Missing prepared input fails this qualification. */
class ReportSourceProcessIntegrationTest {
    @TempDir Path temp;
    @Test @Timeout(180) void realWorkersStopAndCurrentAuthorityProtectsRetainedOriginals() throws Exception {
        String input=System.getProperty("fh.reportConfig");
        assertNotNull(input,"Prepare fixed original report sources and pass -Dfh.reportConfig; no simulated fallback.");
        var config=ReportMapper.json().readTree(Path.of(input).toFile());
        String python=config.path("controllerPython").asText(config.path("wealthPython").asText());
        Path script=temp.resolve("source-lifecycle.py");Files.writeString(script,PYTHON);
        var builder=new ProcessBuilder(python,"-B",script.toString(),input,temp.resolve("actual").toString());
        builder.environment().keySet().removeIf(k->k.startsWith("FH_")||k.startsWith("OPENAI_")||k.startsWith("DEEPSEEK_")||k.startsWith("DASHSCOPE_")||k.endsWith("_API_KEY")||k.equals("PYTHONPATH")||k.equals("PYTHONHOME"));
        builder.environment().put("PYTHONUTF8","1");builder.environment().put("PYTHONDONTWRITEBYTECODE","1");
        builder.redirectErrorStream(true).redirectOutput(temp.resolve("lifecycle.log").toFile());
        Process process=builder.start();
        try {
            assertTrue(process.waitFor(150,TimeUnit.SECONDS),"Owned qualification process timed out");
            assertEquals(0,process.exitValue(),Files.readString(temp.resolve("lifecycle.log")));
            var proof=ReportMapper.json().readTree(temp.resolve("actual/lifecycle.json").toFile());
            assertEquals("PASS",proof.path("status").asText());assertEquals(5,proof.path("cases").size());
            assertEquals(config.path("toolReleaseDigest").asText(),proof.path("sourceReleaseDigest").asText());
            String evidence=System.getProperty("fh.reportLifecycleEvidence");
            if(evidence!=null){Path path=Path.of(evidence);Files.createDirectories(path.getParent());Files.write(path,ReportMapper.bytes(proof));}
        } finally {if(process.isAlive()){process.destroy();if(!process.waitFor(5,TimeUnit.SECONDS))process.destroyForcibly().waitFor();}}
    }
    static final String PYTHON="""

import copy,hashlib,json,os,pathlib,sys,time
cfg=json.loads(pathlib.Path(sys.argv[1]).read_bytes());root=pathlib.Path(sys.argv[2]);root.mkdir(parents=True,exist_ok=True)
release=pathlib.Path(cfg['toolRoot']);sys.path.insert(0,str(release/'adapter'))
import service
from source_assets import canonical,digest,load_release
load_release(release,cfg['toolReleaseDigest'])
context=dict(ownerRef='qualification',customerRef='SYN-001',taskRef='lifecycle',authorityRef='local-test')
binding=dict(methodId='original-method',methodVersion='1.0.0',methodDigest='sha256:'+'a'*64,sourceReleaseDigest=cfg['toolReleaseDigest'],buildId='local-build',instanceId='local-instance',methodKind='ORIGINAL_SKILL',executionId='lifecycle-run')
request=dict(schemaVersion='finance-professional-report-request.v1',assetId='wealth-kyc',caseId='synthetic',tool='wealth_assistant_customer_detail',arguments=dict(customer_id='SYN-001'))
binding['inputDigest']=digest(request);request['binding']=binding
grant=dict(assetIds=['wealth-kyc'],caseIds=['synthetic'],allowedCustomers=['SYN-001'],selectedCustomer='SYN-001',methodBinding={k:v for k,v in binding.items() if k in {'methodId','methodVersion','methodDigest','methodKind','buildId','instanceId'}})
active=[True]
source=service.ReportSourceService(release,cfg['toolReleaseDigest'],root/'source',{'wealth-kyc':pathlib.Path(cfg['wealthPython']),'market-risk-report':pathlib.Path(cfg['marketPython'])},lambda c:grant if active[0] and c==context else None)
def rejected(fn):
    try:fn()
    except (ValueError,PermissionError,KeyError):return
    raise AssertionError('Expected controlled disclosure rejection')
def stopped(run_id,deadline=25):
    end=time.monotonic()+deadline
    while time.monotonic()<end:
        state=source.runs[run_id]['run'].status()
        if state['status']!='RUNNING':assert state['workerStopped'];return state
        time.sleep(.02)
    raise AssertionError('Owned original worker did not stop')
proof=dict(sourceReleaseDigest=cfg['toolReleaseDigest'],realModelCalls=0,cases=[])
try:
    first=source.start(context,request);rid=first['runId'];state=stopped(rid)
    assert state['status']=='SUCCEEDED',(state,(source.runs[rid]['private']/'worker.log').read_text())
    original=source.result(context,rid);assert '3500000' in json.dumps(original)
    assert source.input(context,rid)==request
    for key in context:rejected(lambda key=key:source.result({**context,key:'other'},rid))
    a=original['artifacts'][0];assert source.artifact(context,rid,a['id'])['artifact']==a
    path=source.runs[rid]['private']/'output'/a['name'];saved=path.read_bytes();path.write_bytes(saved+b'changed')
    rejected(lambda:source.artifact(context,rid,a['id']));path.write_bytes(saved)
    active[0]=False;rejected(lambda:source.result(context,rid));active[0]=True
    proof['cases'].append(dict(id='actual-original-detail-and-read-authority',status='PASS',sourceResultId=rid))
    # Test-only wrapper enters the actual immutable worker, then holds at execute().
    # No production service, formula, input or deadline implementation is replaced.
    wrapper=root/'qualification-gate.py'
    wrapper.write_text(\"\"\"import hashlib,json,os,pathlib,runpy,sys,time
worker=pathlib.Path(sys.argv[1]).resolve();sys.argv=sys.argv[1:]
def trace(frame,event,arg):
    if event=='call' and pathlib.Path(frame.f_code.co_filename).resolve()==worker and frame.f_code.co_name=='execute':
        pathlib.Path('gate.json').write_text(json.dumps(dict(pid=os.getpid(),phase='worker.execute entered',worker=str(worker),sha256=hashlib.sha256(worker.read_bytes()).hexdigest())))
        while True:time.sleep(.02)
    return trace
sys.path.insert(0,str(worker.parent));sys.settrace(trace);runpy.run_path(str(worker),run_name='__main__')
\"\"\",encoding='utf-8')
    RealRun=service.Run
    def held(command,private,deadline,**kwargs):
        index=command.index(str(release/'adapter/worker.py'))
        return RealRun(command[:index]+[str(wrapper)]+command[index:],private,deadline,**kwargs)
    service.Run=held
    for mode in ['cancel','timeout','revoke']:
        active[0]=True;item=source.start(context,request,deadline=10 if mode=='timeout' else 30);key=item['runId'];entry=source.runs[key];gate=entry['private']/'gate.json'
        end=time.monotonic()+8
        while not gate.is_file() and time.monotonic()<end:time.sleep(.02)
        assert gate.is_file(),'Actual immutable worker did not reach initialization gate'
        observed=json.loads(gate.read_bytes());assert observed['sha256']==hashlib.sha256((release/'adapter/worker.py').read_bytes()).hexdigest()
        assert entry['run'].process.pid==observed['pid'] and entry['run'].process.poll() is None
        if mode=='cancel':source.cancel(context,key)
        elif mode=='revoke':active[0]=False
        final=stopped(key);assert final['status']==('TIMED_OUT' if mode=='timeout' else 'CANCELLED'),final
        assert entry['run'].process.poll() is not None
        # A fabricated late file cannot reopen a cancelled/timeout task.
        (entry['private']/'result.json').write_bytes(canonical(original));active[0]=True
        rejected(lambda:source.result(context,key));assert source.status(context,key)['status']==final['status']
        proof['cases'].append(dict(id=mode,status='PASS',terminal=final['status'],workerPid=observed['pid'],workerStopped=True,gate=observed))
    service.Run=RealRun
    retry=source.start(context,request);assert stopped(retry['runId'])['status']=='SUCCEEDED';assert source.result(context,retry['runId'])['reportId']!=rid
    proof['cases'].append(dict(id='real-original-retry',status='PASS',sourceResultId=retry['runId']))
    proof['status']='PASS';(root/'lifecycle.json').write_bytes(canonical(proof))
finally:source.close()

        """;
}
