"""Small paired local experiment, not a production P95 or throughput claim."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import statistics
import subprocess
import time
from verify_local_service import Endpoints, expiry, request


def measure(candidate,harness,output, snapshots=False):
    output.mkdir(parents=True,exist_ok=False);fixture=Endpoints();children=[];logs=[]
    fixture.cashflow={k:json.loads((harness/'conformance/fixtures/cashflow/source'/(k+'.json')).read_text())
                      for k in ('summary','analysis','summary-policy','analysis-policy')}
    policy=output/'policy.json';token=secrets.token_urlsafe(40)
    policy.write_text(json.dumps({'schemaVersion':'local-simulator-access.v0.1','bankUrl':fixture.base,
        'subjects':{fixture.customer:{'enabled':True,'expiresAt':expiry(),'scopes':['cashflow:read','account:read','wealth:facts:read','wealth:customer:read']}}}));policy.chmod(0o600)
    env=dict(os.environ,FH_BANK_URL=fixture.base,FH_BANK_LOGIN='demo',FH_BANK_SECRET='synthetic',FH_ACCESS_POLICY=str(policy),
        FH_HOST_TOKEN=token,FH_MODEL_URL=fixture.base+'/v1',FH_MODEL_NAME='controlled',FH_MODEL_KEY='synthetic',FH_MODEL_MODE='CONTROLLED_TEST',FH_PORT='0')
    modes=('on','off','snapshot') if snapshots else ('on','off')
    endpoints={};samples={mode:[] for mode in modes};cold={};storage=output/'archive'
    try:
        for mode in modes:
            (candidate/'instance.json').unlink(missing_ok=True)
            log=(output/(mode+'.log')).open('wb');logs.append(log)
            child=subprocess.Popen(['java','-Xmx512m','-cp',str(next((candidate/'target').glob('*.jar')))+os.pathsep+str(candidate/'target/dependency/*'),
                'io.github.bitlhk.finance.builder.local.LocalAgentHost',str(candidate)],cwd=candidate,
                env=dict(env,FH_HISTORY_ENABLED='false' if mode=='off' else 'true',FH_HISTORY_DIR=str(storage/mode),
                         FH_HISTORY_OWNER='overhead-synthetic',FH_SNAPSHOTS_ENABLED='true' if mode=='snapshot' else 'false',
                         FH_HISTORY_PROFILE='true',
                         FH_SNAPSHOT_CAPTURE_OWNERS='overhead-synthetic'),stdout=log,stderr=subprocess.STDOUT)
            children.append(child);deadline=time.monotonic()+30
            while not (candidate/'instance.json').exists():
                assert child.poll() is None and time.monotonic()<deadline;time.sleep(.05)
            instance=json.loads((candidate/'instance.json').read_text());base='http://127.0.0.1:'+str(instance['port'])
            code,value=request(base,token,'/sessions',{});assert code==200
            endpoints[mode]=(base,value['sessionId'])
        def turn(mode):
            base,sid=endpoints[mode];started=time.perf_counter_ns()
            code,_=request(base,token,f'/sessions/{sid}/chat',{'query':'summary-probe 2026-08'})
            assert code==200;return (time.perf_counter_ns()-started)/1e6
        for mode in modes: cold[mode]=turn(mode);turn(mode)
        for index in range(12):
            for mode in (modes if index%2==0 else tuple(reversed(modes))): samples[mode].append(turn(mode))
        time.sleep(.2)
        decomposition={}
        for mode in modes:
            timings=[];business=[]
            ordered=sorted((storage/mode).glob('*.jsonl'),
                           key=lambda p:json.loads(p.read_text().splitlines()[0]).get('createdAt',''))
            for path in ordered[2:]:
                events=[json.loads(line) for line in path.read_text().splitlines()]
                timings.extend(e for e in events if e.get('recordType')=='diagnostic-timing')
                spans=[e for e in events if e.get('component')=='provider-http' and e.get('phase')=='END']
                business.append(sum(e.get('durationNanos',0) for e in spans)/1e6)
            decomposition[mode]={
                'profiledRequestsExcludingWarmups':len(timings),
                'medianMs':{key:statistics.median([t[key]/1e6 for t in timings]) if timings else None
                            for key in ('appendNanos','forceNanos','projectionNanos')},
                'serialProviderAttemptMedianMs':statistics.median(business) if business else None,
                'scope':'Append includes force; projection excludes async body writes. Provider spans include observations. Not additive; off has no trace data.'}
        result={'sampleCountPerMode':12,'warmupsPerMode':2,'order':'alternating-paired','clock':'client perf_counter',
            'coldFirstTurnMs':cold,'samplesMs':samples,'medianMs':{k:statistics.median(v) for k,v in samples.items()},
            'empiricalP95Ms':{k:sorted(v)[-1] for k,v in samples.items()},
            'p95Limitation':'12 samples: nearest-rank is maximum, not a stable production P95',
            'eventBytes':sum(p.stat().st_size for p in storage.rglob('*.jsonl')),
            'allArchiveBytes':sum(p.stat().st_size for p in storage.rglob('*') if p.is_file()),
            'bytesPerMode':{mode:sum(p.stat().st_size for p in (storage/mode).rglob('*') if p.is_file()) for mode in modes},
            'recordedRequests':len(list(storage.rglob('*.jsonl'))),'businessWrites':len(fixture.writes),
            'decomposition':decomposition,
            'instrumentation':'FH_HISTORY_PROFILE=true for all modes; no change to force(false)/body force(true)',
            'sampleRangeMs':{k:[min(v),max(v)] for k,v in samples.items()},
            'serviceLockSha256':hashlib.sha256((candidate/'service-lock.json').read_bytes()).hexdigest()}
        (output/'report.json').write_text(json.dumps(result,indent=2));print(json.dumps(result['medianMs']))
    finally:
        for child in children:
            if child.poll() is None:
                child.terminate()
                try:child.wait(timeout=10)
                except subprocess.TimeoutExpired:child.kill();child.wait()
        for log in logs:log.close()
        fixture.close();policy.unlink(missing_ok=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('candidate',type=Path);parser.add_argument('--harness',type=Path,required=True);parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--snapshots',action='store_true')
    args=parser.parse_args();measure(args.candidate.resolve(),args.harness.resolve(),args.output.resolve(),args.snapshots)
