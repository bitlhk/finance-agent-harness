"""Actual generated host lifecycle; no model request or real banking connection."""
import argparse
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time
import zipfile
from unittest.mock import patch

from finance_solution_builder.workbench import Jobs
from finance_solution_builder.service import MAIN, digest, write_json
from verify_local_service import Endpoints, expiry, request, verify


def main():
    parser=argparse.ArgumentParser(); parser.add_argument("build",type=Path); parser.add_argument("--harness",type=Path,required=True)
    args=parser.parse_args(); original=args.build.resolve(); fixture=Endpoints()
    checks=[]
    def check(name, condition):
        if not condition: raise AssertionError(name)
        checks.append(name)
    with tempfile.TemporaryDirectory(prefix="finance-portable-") as directory:
        root=Path(directory); jobs=Jobs(root,args.harness)
        lock=json.loads((original/"candidate/service-lock.json").read_text()); job_id=lock["buildId"]
        target=jobs.path(job_id); shutil.copytree(original,target)
        jobs.jobs[job_id]={"id":job_id,"owner":"owner","status":"BUILT","capabilities":lock["capabilities"]}
        policy=root/"policy.json"
        write_json(policy,{"schemaVersion":"local-simulator-access.v0.1","bankUrl":fixture.base,"subjects":{"customer-test-a":{
            "enabled":True,"expiresAt":expiry(),"scopes":["wealth:facts:read","wealth:customer:read","account:read"]}}})
        policy.chmod(0o600)
        env=dict(FH_MODEL_URL=fixture.base+"/v1",FH_MODEL_KEY="explicit-test-not-a-key",FH_MODEL_NAME="NO_MODEL_CALL_LIFECYCLE",
                 FH_BANK_URL=fixture.base,FH_BANK_LOGIN="demo",FH_BANK_SECRET="demo123",FH_ACCESS_POLICY=str(policy))
        try:
            with patch.dict(os.environ,env):
                started=jobs.start(job_id,"owner"); child=jobs.processes[job_id]
                check("actual_process_start",started["status"]=="RUNNING" and child.poll() is None)
                code,opened=jobs.talk(job_id,"owner","session",{})
                check("native_installation_matches_build",code==200 and opened["buildId"]==job_id)
                stopped=jobs.stop(job_id,"owner")
                check("stop_exits_owned_process",stopped["status"]=="STOPPED" and child.poll() is not None)
                restarted=jobs.start(job_id,"owner"); second=jobs.processes[job_id]
                check("restart_changes_instance",second.pid!=child.pid and restarted["instanceId"]!=started["instanceId"])
                code,denied=jobs.talk(job_id,"owner","chat",{"query":"old context"})
                check("restart_has_no_old_session",code==403 and denied["clearConversation"])
                jobs.stop(job_id,"owner")
                archive=jobs.artifact(job_id,"owner")
                with zipfile.ZipFile(archive) as bundle:
                    check("portable_archive_no_private_state",all(not any(v in n for v in ("instance.json","runtime-evidence","policy.json","test-host.log","service-report")) for n in bundle.namelist()))
                    bundle.extractall(root/"download")
                portable=root/"download"/job_id
                check("portable_sources_and_jars_intact",all(digest(portable/p)==expected for p,expected in lock["files"].items()))
                # The fixture/model is only used by the separately labelled native verifier below.
                check("lifecycle_never_called_model",fixture.models==[])
                with socket.socket() as occupied:
                    occupied.bind(("127.0.0.1",0)); occupied.listen()
                    port=occupied.getsockname()[1]
                    cmd=["java","-Xmx512m","-cp",str(next((portable/"target").glob("*.jar")))+os.pathsep+str(portable/"target/dependency/*"),MAIN,str(portable)]
                    collision=subprocess.run(cmd,env=dict(os.environ,FH_HOST_TOKEN="t"*48,FH_PORT=str(port),FH_MODEL_MODE="CONTROLLED_TEST"),capture_output=True,timeout=30)
                    check("occupied_port_not_adopted",collision.returncode!=0 and not (portable/"instance.json").exists())
                (target/"candidate/solution.json").write_text("{}")
                try: jobs.start(job_id,"owner"); raise AssertionError("modified artifact started")
                except RuntimeError: pass
                check("modified_artifact_fails_start",jobs.jobs[job_id]["status"]=="FAILED" and job_id not in jobs.processes)
            verify(portable)
            check("portable_native_two_turn_verification",(portable/"controlled-service-report.json").exists())
            report={"checks":checks,"mode":"LIFECYCLE_NO_MODEL_CALL_PLUS_EXPLICIT_CONTROLLED_NATIVE_TEST","humanOnboarding":"PENDING"}
            (original/"lifecycle-report.json").write_text(json.dumps(report,indent=2))
            print(json.dumps(report))
        finally: jobs.close(); fixture.close()


if __name__=="__main__": main()
