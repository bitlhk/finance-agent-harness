"""Execute the actual Java SDK -> source service -> fixed original process qualification."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import subprocess
import sys


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--config',type=Path,required=True)
    parser.add_argument('--report',type=Path,required=True);parser.add_argument('--maven',default='mvn');parser.add_argument('--settings',type=Path)
    args=parser.parse_args();config=json.loads(args.config.read_text(encoding='utf-8'))
    root=Path(config['harnessRoot']);release=Path(config['toolRoot'])
    if hashlib.sha256((release/'release.json').read_bytes()).hexdigest()!=config['toolReleaseDigest']:
        raise ValueError('CALCULATION_RELEASE_DIGEST_MISMATCH')
    args.report.parent.mkdir(parents=True,exist_ok=True)
    command=[args.maven,'-B','-ntp']
    if args.settings:command+=['-s',str(args.settings)]
    command+=['-f',str(root/'pom.xml'),'-pl','integrations/original-calculation-java','-am',
        '-Dtest=CalculationNativeIntegrationTest,CalculationSourceProcessIntegrationTest','-Dsurefire.failIfNoSpecifiedTests=false',
        '-Dfh.calculationConfig='+str(args.config.resolve()),'verify']
    if args.maven.endswith('/mvn') and os.name!='nt':command=['bash',*command]
    env={k:v for k,v in os.environ.items() if not k.startswith(('FH_','OPENAI_','DEEPSEEK_','DASHSCOPE_','LANGFUSE_')) and not k.endswith(('_API_KEY','_SECRET_KEY'))}
    env.update(PYTHONUTF8='1',PYTHONDONTWRITEBYTECODE='1')
    with args.report.with_suffix('.log').open('w',encoding='utf-8') as log:
        cp=subprocess.run(command,cwd=root,env=env,stdout=log,stderr=subprocess.STDOUT,timeout=1200)
    result=dict(status='PASS' if cp.returncode==0 else 'FAIL',exitCode=cp.returncode,releaseDigest=config['toolReleaseDigest'],
        scope='ACTUAL_NATIVE_SDK_AND_ORIGINAL_PROCESSES',realModelCalls=0,command=command)
    evidence=Path(config['evidenceRoot'])/'native-original-cases.json'
    if cp.returncode==0:
        cases=json.loads(evidence.read_text(encoding='utf-8'))
        if len(cases['cases'])!=11 or any(row['status']!='PASS' for row in cases['cases']):
            result.update(status='FAIL',errorCode='NATIVE_CASE_COVERAGE_INCOMPLETE')
        result['evidence']=cases
    args.report.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({k:v for k,v in result.items() if k not in {'command','evidence'}}))
    return 0 if result['status']=='PASS' else 1


if __name__=='__main__':raise SystemExit(main())
