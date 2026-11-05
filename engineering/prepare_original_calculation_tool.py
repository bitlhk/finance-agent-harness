"""Prepare eight fixed original environments independently of the CI control Python."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
from prepare_original_market_risk_tool import fixed_checkout,UPSTREAM_URL,UPSTREAM_REVISION,BUILDER_URL

BUILDER_REVISION='945cc68144b2836f52ec9561304cc4558e0bda21'
TOOL_PATH='community/finance/workshop/tools/original-calculation-readonly'
ROOT=Path(__file__).resolve().parents[1]


def local_builder(root):
    """A local fixed checkout is allowed; uncommitted tool changes are not."""
    root=Path(root).resolve()
    actual=subprocess.check_output(['git','-C',str(root),'rev-parse','HEAD'],text=True,timeout=30).strip()
    origin=subprocess.check_output(['git','-C',str(root),'remote','get-url','origin'],text=True,timeout=30).strip().removesuffix('.git')
    if actual!=BUILDER_REVISION or origin!=BUILDER_URL.removesuffix('.git'):
        raise ValueError('CALCULATION_BUILDER_IDENTITY_MISMATCH')
    subprocess.run(['git','-C',str(root),'diff','--exit-code',BUILDER_REVISION,'--',TOOL_PATH],check=True,capture_output=True,timeout=30)
    return root


def worker_version(python):
    version=subprocess.check_output([str(python),'-I','-B','-c','import sys;print(".".join(map(str,sys.version_info[:3])))'],text=True,timeout=30).strip()
    if not re.fullmatch('3\\.12\\.\\d+',version):raise ValueError('CALCULATION_WORKER_PYTHON_312_REQUIRED')
    return version


def prepare(workspace,worker_python,*,builder_root=None,source_root=None):
    workspace=Path(workspace).resolve();workspace.mkdir(parents=True,exist_ok=False)
    version=worker_version(worker_python)
    builder=local_builder(builder_root) if builder_root is not None else fixed_checkout(BUILDER_URL,BUILDER_REVISION,workspace/'builder',[TOOL_PATH])
    manifest=json.loads((builder/TOOL_PATH/'source-manifest.v1.json').read_text(encoding='utf-8'))
    if len(manifest['assets'])!=8 or any(a['sourceCommit']!=UPSTREAM_REVISION for a in manifest['assets']):
        raise ValueError('CALCULATION_SOURCE_MANIFEST_INVALID')
    upstream=Path(source_root).resolve() if source_root is not None else fixed_checkout(UPSTREAM_URL,UPSTREAM_REVISION,workspace/'upstream',sorted({a['sourceEntity'] for a in manifest['assets']}))
    release=workspace/'release';cache=workspace/'uv-cache'
    env={k:v for k,v in os.environ.items() if not k.startswith(('FH_','OPENAI_','DEEPSEEK_','DASHSCOPE_','LANGFUSE_')) and not k.endswith(('_API_KEY','_SECRET_KEY'))}
    env.update(PYTHONUTF8='1',PYTHONDONTWRITEBYTECODE='1',UV_CACHE_DIR=str(cache))
    command=[str(worker_python),'-B',str(builder/TOOL_PATH/'publish_original_tools.py'),'--source-root',str(upstream),
        '--output',str(release),'--schema-root',str(ROOT/'contracts/json-schema/calculation'),'--worker-python',str(worker_python),'--cache',str(cache)]
    with (workspace/'publisher-error.log').open('w',encoding='utf-8') as error:
        cp=subprocess.run(command,env=env,capture_output=True,text=True,encoding='utf-8',timeout=900)
        error.write(cp.stderr)
    if cp.returncode:raise RuntimeError('CALCULATION_ORIGINAL_PREPARATION_FAILED')
    receipt=json.loads(cp.stdout)
    if receipt['assetCount']!=8 or receipt['sourceCommit']!=UPSTREAM_REVISION:raise ValueError('CALCULATION_PUBLICATION_INVALID')
    actual=hashlib.sha256((release/'release.json').read_bytes()).hexdigest()
    if actual!=receipt['releaseDigest']:raise ValueError('CALCULATION_RELEASE_DIGEST_MISMATCH')
    config=dict(schemaVersion='finance-calculation-execution-inputs.v1',sourceRoot=str(upstream),sourceCommit=UPSTREAM_REVISION,
        agentStoreRoot=str(builder),harnessRoot=str(ROOT),toolRoot=str(release),toolReleaseDigest=actual,
        evidenceRoot=str(workspace/'evidence'),workerPython=str(Path(worker_python).resolve()),modelMode='CONTROLLED_TEST',realModelCalls=0)
    path=workspace/'execution-inputs.json';path.write_text(json.dumps(config,indent=2)+'\n',encoding='utf-8')
    (workspace/'preparation.json').write_text(json.dumps(dict(status='PREPARED_NOT_EXECUTED',builderCommit=BUILDER_REVISION,
        upstreamCommit=UPSTREAM_REVISION,workerPythonVersion=version,releaseDigest=actual,assetCount=8,dependencyInstall='EXACT_HASHED_LOCKS'),indent=2)+'\n',encoding='utf-8')
    return path,config


def export_properties(config_file,config,github_env):
    values=dict(FH_CALCULATION_CONFIG=str(Path(config_file).resolve()),FH_CALCULATION_TOOL_RELEASE=config['toolRoot'],FH_CALCULATION_TOOL_RELEASE_DIGEST=config['toolReleaseDigest'])
    if any('\n' in value or '\r' in value for value in values.values()):raise ValueError('INVALID_CI_ENVIRONMENT_VALUE')
    with Path(github_env).open('a',encoding='utf-8',newline='\n') as stream:
        for key,value in values.items():stream.write(key+'='+value+'\n')


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--workspace',type=Path,required=True)
    parser.add_argument('--worker-python',required=True);parser.add_argument('--github-env',type=Path)
    parser.add_argument('--builder-root',type=Path);parser.add_argument('--source-root',type=Path);args=parser.parse_args()
    path,config=prepare(args.workspace,args.worker_python,builder_root=args.builder_root,source_root=args.source_root)
    if args.github_env:export_properties(path,config,args.github_env)
    print(json.dumps(dict(status='PREPARED_NOT_EXECUTED',config=str(path),releaseDigest=config['toolReleaseDigest'])))


if __name__=='__main__':main()
