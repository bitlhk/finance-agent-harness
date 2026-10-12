"""Explicit live ASR smoke. Keys stay server-side; synthetic audio is NOT human microphone acceptance."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import time

parser=argparse.ArgumentParser()
parser.add_argument("--source-env",type=Path,required=True)
parser.add_argument("--adapter",type=Path,required=True)
parser.add_argument("--audio",type=Path,required=True)
parser.add_argument("--report",type=Path,required=True)
parser.add_argument("--install-env",type=Path)
parser.add_argument("--expected", default="请查询我的账户余额")
parser.add_argument("--audio-kind", default="ESPEAK_SYNTHETIC_SPEECH_NOT_HUMAN_RECORDING")
args=parser.parse_args()
source={}
for line in args.source_env.read_text().splitlines():
    key,sep,value=line.partition("=")
    if sep: source[key.strip()]=value.strip().strip('\"\'')
mapping={"FH_ASR_APP_ID":"XFYUN_APPID","FH_ASR_API_KEY":"XFYUN_API_KEY","FH_ASR_API_SECRET":"XFYUN_API_SECRET"}
for dest,key in mapping.items():
    value=source.get(key)
    if not value or "\n" in value: raise SystemExit("ASR_CREDENTIAL_MISSING")
    os.environ[dest]=value
spec=importlib.util.spec_from_file_location("speech",args.adapter)
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
checks=[]; start=time.monotonic()
try:
    result=module.transcribe(args.audio.read_bytes(),"audio/wav",lambda:checks.append("authorized_local_test"))
    report={"status":"LIVE_TRANSCRIBED","provider":result["provider"],"text":result["text"],
        "expected":args.expected,"audioKind":args.audio_kind,
        "matchesExpected":"".join(c for c in result["text"] if c.isalnum())=="".join(c for c in args.expected if c.isalnum()),
        "elapsedMillis":round((time.monotonic()-start)*1000),"authorizationChecks":len(checks),
        "requiresUserSend":result["requiresUserSend"],"humanMicrophone":"NOT_RUN"}
except ValueError as error:
    report={"status":"LIVE_FAILED","code":str(error),"humanMicrophone":"NOT_RUN"}
args.report.write_text(json.dumps(report,ensure_ascii=False,indent=2))
if args.install_env and report["status"]=="LIVE_TRANSCRIBED":
    fd=os.open(args.install_env,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
    with os.fdopen(fd,"w") as stream:
        for name in mapping: stream.write(name+"="+os.environ[name]+"\n")
    args.install_env.chmod(0o600)
print(json.dumps(report,ensure_ascii=False))
if report["status"]!="LIVE_TRANSCRIBED": raise SystemExit(1)
