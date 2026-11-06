"""Inspect exact local original qualification and task contracts; no live business calls."""
import argparse,json,hashlib
from pathlib import Path

def check(inputs):
    required=['toolRoot','toolReleaseDigest','originalPython']
    missing=[key for key in required if not inputs.get(key)]
    if missing:return dict(status='BLOCKED',missing=missing,professional='BLOCKED',production='BLOCKED')
    root=Path(inputs['toolRoot']);raw=(root/'source-release.v1.json').read_bytes()
    if 'sha256:'+hashlib.sha256(raw).hexdigest()!=inputs['toolReleaseDigest']:raise ValueError('TASK_RELEASE_CHANGED')
    manifest=json.loads(raw)
    if manifest['sourceCommit']!='0e158eee225c698774aa75671c944624cd36550b':raise ValueError('TASK_SOURCE_CHANGED')
    qualification=Path(inputs.get('originalQualification',str(root.parent/'original-qualification.json')))
    if not qualification.is_file():return dict(status='BLOCKED',missing=['originalQualification'],professional='BLOCKED',production='BLOCKED')
    q=json.loads(qualification.read_bytes())
    if q.get('status')!='PASS' or q.get('sourceReleaseDigest')!=inputs['toolReleaseDigest']:raise ValueError('MATCHING_ORIGINAL_QUALIFICATION_REQUIRED')
    return dict(status='PASS',scope='original-source-input-qualification',originalFidelity='PASS',sourceReleaseDigest=inputs['toolReleaseDigest'],sdkJoint='SEPARATE_INDEPENDENT_HOST_GATE',remoteCI='NOT_RUN_LOCALLY',professional='BLOCKED',production='BLOCKED')
def main():
    p=argparse.ArgumentParser();p.add_argument('--inputs',type=Path,required=True);p.add_argument('--report',type=Path,required=True);a=p.parse_args()
    result=check(json.loads(a.inputs.read_bytes()));a.report.parent.mkdir(parents=True,exist_ok=True);a.report.write_text(json.dumps(result,indent=2)+'\n',encoding='utf8');print(result['status']);raise SystemExit(0 if result['status']=='PASS' else 1)
if __name__=='__main__':main()
