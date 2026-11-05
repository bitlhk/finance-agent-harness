"""Prepare pinned original report assets and two independent hashed Python environments."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
from prepare_original_market_risk_tool import fixed_checkout, UPSTREAM_URL, UPSTREAM_REVISION, BUILDER_URL

# Exact local candidate. Remote fetch stays unavailable until separately authorized publication.
BUILDER_REVISION='75e97dc0a64c62ea29b3990feea95ef32a1fa4e5'
TOOL_PATH='community/finance/workshop/tools/professional-report-readonly'
ROOT=Path(__file__).resolve().parents[1]


def require_builder(root):
    root=Path(root).resolve()
    actual=subprocess.check_output(['git','-C',str(root),'rev-parse','HEAD'],text=True,timeout=30).strip()
    origin=subprocess.check_output(['git','-C',str(root),'remote','get-url','origin'],text=True,timeout=30).strip().removesuffix('.git')
    # An explicitly supplied local clone may have another local clone as origin.
    # The exact frozen commit and clean tool tree identify its bytes; network
    # preparation still fetches only BUILDER_URL through fixed_checkout.
    if actual!=BUILDER_REVISION:
        raise ValueError('REPORT_BUILDER_IDENTITY_MISMATCH')
    subprocess.run(['git','-C',str(root),'diff','--exit-code',BUILDER_REVISION,'--',TOOL_PATH],check=True,capture_output=True,timeout=30)
    untracked=subprocess.check_output(['git','-C',str(root),'ls-files','--others','--exclude-standard','--',TOOL_PATH],text=True,timeout=30)
    if untracked.strip():raise ValueError('REPORT_UNCOMMITTED_TOOL_INPUT')
    return root


def private_environment():
    return {k:v for k,v in os.environ.items() if not k.startswith(('FH_','OPENAI_','DEEPSEEK_','DASHSCOPE_','LANGFUSE_'))
            and not k.endswith(('_API_KEY','_SECRET_KEY')) and k not in {'PYTHONPATH','PYTHONHOME'}}


def prepare(workspace,worker_python,*,builder_root=None,source_root=None):
    if len(BUILDER_REVISION)!=40 or any(c not in '0123456789abcdef' for c in BUILDER_REVISION):
        raise ValueError('REPORT_SOURCE_NOT_YET_PUBLISHED')
    workspace=Path(workspace).resolve();workspace.mkdir(parents=True,exist_ok=False)
    version=subprocess.check_output([str(worker_python),'-I','-B','-c','import sys;print(".".join(map(str,sys.version_info[:3])))'],text=True,timeout=30).strip()
    if not version.startswith('3.12.'):raise ValueError('REPORT_WORKER_PYTHON_312_REQUIRED')
    builder=require_builder(builder_root) if builder_root is not None else fixed_checkout(BUILDER_URL,BUILDER_REVISION,workspace/'builder',[TOOL_PATH])
    tool=builder/TOOL_PATH
    manifest=json.loads((tool/'source-manifest.v1.json').read_bytes())
    source_dirs=sorted({str(Path(item['path']).parent).replace('\\','/') for asset in manifest['assets'] for item in asset['files'] if '/' in item['path']})
    upstream=Path(source_root).resolve() if source_root is not None else fixed_checkout(UPSTREAM_URL,UPSTREAM_REVISION,workspace/'upstream',source_dirs)
    release=workspace/'release';env=private_environment()
    env.update(PYTHONUTF8='1',PYTHONDONTWRITEBYTECODE='1',UV_CACHE_DIR=str(workspace/'uv-cache'))
    def run(command,log,timeout=900):
        with (workspace/log).open('wb') as stream:
            result=subprocess.run(command,env=env,stdout=stream,stderr=subprocess.STDOUT,timeout=timeout)
        if result.returncode:raise RuntimeError('REPORT_PREPARATION_FAILED_'+log)
    publisher=[str(worker_python),'-B',str(tool/'publish_original_tools.py'),'--source',str(upstream),'--output',str(release)]
    run(publisher,'publisher.log')
    receipt=json.loads((workspace/'publisher.log').read_bytes());pin='sha256:'+hashlib.sha256((release/'source-release.v1.json').read_bytes()).hexdigest()
    if receipt['releaseDigest']!=pin or not receipt['runtimeReady']:raise ValueError('REPORT_RELEASE_IDENTITY_MISMATCH')
    interpreters={}
    for asset,lock in [('wealth','wealth'),('market','market-report')]:
        target=workspace/(asset+'-env');python=target/('Scripts/python.exe' if os.name=='nt' else 'bin/python')
        run([str(worker_python),'-m','venv',str(target)],asset+'-venv.log')
        run([sys.executable,'-m','uv','pip','install','--python',str(python),'--require-hashes','--index-url','https://pypi.org/simple','-r',str(release/'adapter/locks'/(lock+'.requirements.lock'))],asset+'-install.log')
        interpreters[asset]=str(python)
    qualification=workspace/'original-qualification.json'
    run([str(worker_python),'-B',str(release/'adapter/qualification.py'),'--release',str(release),'--release-digest',pin,
         '--wealth-python',interpreters['wealth'],'--market-python',interpreters['market'],'--report',str(qualification)],'qualification.log')
    if json.loads(qualification.read_bytes())['status']!='PASS':raise ValueError('REPORT_ORIGINAL_QUALIFICATION_FAILED')
    config=dict(schemaVersion='finance-report-execution-inputs.v1',sourceRoot=str(upstream),sourceCommit=UPSTREAM_REVISION,
        agentStoreRoot=str(builder),harnessRoot=str(ROOT),toolRoot=str(release),toolReleaseDigest=pin,
        wealthPython=interpreters['wealth'],marketPython=interpreters['market'],controllerPython=str(Path(worker_python).resolve()),
        originalQualification=str(qualification),modelMode='CONTROLLED_TEST',realModelCalls=0)
    path=workspace/'execution-inputs.json';path.write_text(json.dumps(config,indent=2)+'\n',encoding='utf-8')
    (workspace/'preparation.json').write_text(json.dumps(dict(status='PREPARED_NOT_EXECUTED',builderCommit=BUILDER_REVISION,
        sourceCommit=UPSTREAM_REVISION,sourceReleaseDigest=pin,dependencyInstall='EXACT_HASHED_LOCKS',
        workerPythonVersion=version,originalEngine='PASS',professionalQualification='BLOCKED',productionQualification='BLOCKED'),indent=2)+'\n',encoding='utf-8')
    return path,config


def export_properties(config_file,config,github_env):
    values=dict(FH_REPORT_CONFIG=str(Path(config_file).resolve()),FH_REPORT_LIFECYCLE_EVIDENCE=str(Path(config_file).parent/'lifecycle.json'))
    if any('\n' in value or '\r' in value for value in values.values()):raise ValueError('REPORT_CI_ENVIRONMENT_REJECTED')
    with Path(github_env).open('a',encoding='utf-8',newline='\n') as stream:
        for key,value in values.items():stream.write(key+'='+value+'\n')


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--workspace',type=Path,required=True)
    parser.add_argument('--worker-python',required=True);parser.add_argument('--github-env',type=Path)
    parser.add_argument('--builder-root',type=Path);parser.add_argument('--source-root',type=Path);args=parser.parse_args()
    path,config=prepare(args.workspace,args.worker_python,builder_root=args.builder_root,source_root=args.source_root)
    if args.github_env:export_properties(path,config,args.github_env)
    print(json.dumps(dict(status='PREPARED_NOT_EXECUTED',config=str(path),sourceReleaseDigest=config['toolReleaseDigest'])))


if __name__=='__main__':main()
