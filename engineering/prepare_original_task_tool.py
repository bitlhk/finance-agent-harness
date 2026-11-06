"""Pinned original custody preparation; no model or bank is contacted."""
import argparse,hashlib,json,os,subprocess,sys,shutil
from pathlib import Path
from prepare_original_market_risk_tool import fixed_checkout,BUILDER_URL,UPSTREAM_URL,UPSTREAM_REVISION
BUILDER_REVISION='2d09ee792eba2bc26b0c5ff50a692594fdb72e9f'
TOOL_PATH='community/finance/workshop/tools/managed-original-task-service'

def require_builder(root):
    root=Path(root).resolve()
    if subprocess.check_output(['git','-C',str(root),'rev-parse','HEAD'],text=True,timeout=30).strip()!=BUILDER_REVISION:raise ValueError('TASK_BUILDER_IDENTITY_MISMATCH')
    subprocess.run(['git','-C',str(root),'diff','--exit-code',BUILDER_REVISION,'--',TOOL_PATH],check=True,capture_output=True,timeout=30)
    if subprocess.check_output(['git','-C',str(root),'ls-files','--others','--exclude-standard','--',TOOL_PATH],text=True,timeout=30).strip():raise ValueError('TASK_UNCOMMITTED_TOOL_INPUT')
    return root

def prepare(workspace,worker_python,*,builder_root=None,source_root=None):
    root=Path(workspace).resolve();root.mkdir(parents=True,exist_ok=False)
    env={k:v for k,v in os.environ.items() if not k.startswith(('FH_','OPENAI_','DEEPSEEK_','DASHSCOPE_','LANGFUSE_')) and not k.endswith(('_API_KEY','_SECRET_KEY')) and k not in {'PYTHONPATH','PYTHONHOME'}}
    env.update(PYTHONUTF8='1',PYTHONDONTWRITEBYTECODE='1')
    def run(argv,log,timeout=900):
        with (root/log).open('wb') as stream:r=subprocess.run(argv,env=env,stdout=stream,stderr=subprocess.STDOUT,timeout=timeout)
        if r.returncode:raise RuntimeError('TASK_PREPARATION_FAILED_'+log)
    version=subprocess.check_output([str(worker_python),'-I','-c','import sys;print(".".join(map(str,sys.version_info[:3])))'],text=True,timeout=30).strip()
    if not version.startswith('3.12.'):raise ValueError('TASK_WORKER_PYTHON_312_REQUIRED')
    uv=shutil.which('uv')
    if uv is None or not subprocess.check_output([uv,'--version'],text=True,timeout=30).startswith('uv 0.12.10 '):raise ValueError('TASK_UV_01210_REQUIRED')
    builder=require_builder(builder_root) if builder_root is not None else fixed_checkout(BUILDER_URL,BUILDER_REVISION,root/'builder',[TOOL_PATH])
    tool=builder/TOOL_PATH;manifest=json.loads((tool/'source-manifest.v1.json').read_bytes())
    paths=sorted({str(Path(f['path']).parent) for f in manifest['files'] if '/' in f['path']})
    upstream=Path(source_root).resolve() if source_root is not None else fixed_checkout(UPSTREAM_URL,UPSTREAM_REVISION,root/'upstream',paths)
    env['PYTHONPATH']=str(tool/'src');release=root/'release'
    run([str(worker_python),'-B','-m','managed_original_task_service.publish','--source',str(upstream),'--output',str(release)],'publisher.log')
    pin='sha256:'+hashlib.sha256((release/'source-release.v1.json').read_bytes()).hexdigest()
    if json.loads((root/'publisher.log').read_bytes())['releaseDigest']!=pin:raise ValueError('TASK_RELEASE_IDENTITY_MISMATCH')
    venv=root/'original-env';run([str(worker_python),'-m','venv',str(venv)],'venv.log')
    python=venv/('Scripts/python.exe' if os.name=='nt' else 'bin/python')
    run([uv,'pip','install','--python',str(python),'--require-hashes','--index-url','https://pypi.org/simple','-r',str(release/'adapter/locks/original-custody.requirements.lock')],'dependencies.log')
    config=dict(schemaVersion='finance-task-ci-inputs.v1',sourceRoot=str(upstream),sourceCommit=UPSTREAM_REVISION,builderCommit=BUILDER_REVISION,toolRoot=str(release),toolReleaseDigest=pin,originalPython=str(python),realModelCalls=0,professionalQualification='BLOCKED',productionQualification='BLOCKED')
    config_file=root/'execution-inputs.json';config_file.write_text(json.dumps(config,indent=2)+'\n',encoding='utf8')
    env['PYTHONPATH']=str(release/'adapter/src')
    run([str(python),'-B','-m','managed_original_task_service.qualification','--inputs',str(config_file),'--report',str(root/'original-qualification.json')],'qualification.log')
    if json.loads((root/'original-qualification.json').read_bytes())['status']!='PASS':raise ValueError('TASK_ORIGINAL_QUALIFICATION_FAILED')
    (root/'preparation.json').write_text(json.dumps(dict(status='PREPARED_NOT_EXECUTED',builderCommit=BUILDER_REVISION,sourceReleaseDigest=pin,originalFidelity='PASS',sdkJoint='NOT_RUN',professional='BLOCKED',production='BLOCKED'),indent=2)+'\n',encoding='utf8')
    return config_file,config

def export_properties(config_file,config,target):
    values=dict(FH_TASK_CONFIG=str(Path(config_file).resolve()),FH_TASK_ORIGINAL_PYTHON=config['originalPython'],FH_TASK_SOURCE_RELEASE=config['toolRoot'],FH_TASK_SOURCE_RELEASE_DIGEST=config['toolReleaseDigest'])
    if any('\n' in v or '\r' in v for v in values.values()):raise ValueError('TASK_CI_ENVIRONMENT_REJECTED')
    with Path(target).open('a',encoding='utf8',newline='\n') as stream:
        for k,v in values.items():stream.write(k+'='+v+'\n')
def main():
    p=argparse.ArgumentParser();p.add_argument('--workspace',type=Path,required=True);p.add_argument('--worker-python',required=True);p.add_argument('--builder-root',type=Path);p.add_argument('--source-root',type=Path);p.add_argument('--github-env',type=Path);a=p.parse_args()
    file,config=prepare(a.workspace,a.worker_python,builder_root=a.builder_root,source_root=a.source_root)
    if a.github_env:export_properties(file,config,a.github_env)
    print(json.dumps(dict(status='PREPARED_NOT_EXECUTED',sourceReleaseDigest=config['toolReleaseDigest'])))
if __name__=='__main__':main()
