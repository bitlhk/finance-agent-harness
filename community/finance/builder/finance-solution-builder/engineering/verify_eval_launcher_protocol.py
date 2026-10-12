"""Run the installed Eval JavaScript client against a controlled Launcher backend.

This checks protocol compatibility, NOT a native Runtime or model execution.
The Eval checkout is read-only and its existing dist files must already exist.
"""
import argparse
import hashlib
import json
from pathlib import Path
import secrets
import subprocess
import sys
import threading

project = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(project / "src"), str(project.parent / "base/src"), str(project / "tests")]
from finance_solution_builder.eval_launcher_http import make_server
from finance_solution_builder.eval_launcher_service import Launcher
from test_eval_launcher_service import Backend, preset, request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--eval-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    module = args.eval_root.resolve() / "dist/runtime-launcher.js"
    if not module.is_file():
        raise RuntimeError("EVAL_BUILT_CLIENT_REQUIRED")
    token = secrets.token_urlsafe(40)
    backend = Backend()
    bound_preset = {**preset(), "owner": "protocol-test"}
    launcher = Launcher(backend, {"oj": bound_preset, "as": {**bound_preset, "runtime": {"type": "agentscope", "version": "2.0.3"}}})
    server = make_server(launcher, {hashlib.sha256(token.encode()).hexdigest(): {"owner": "protocol-test", "runtimes": ["oj", "as"]}})
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    config = {"module": module.as_uri(), "base": f"http://127.0.0.1:{server.server_port}/", "token": token, "request": request()}
    script = """
const chunks=[]; for await (const chunk of process.stdin) chunks.push(chunk);
const c=JSON.parse(Buffer.concat(chunks).toString());
const {RuntimeLauncherAdapter,runtimeLauncherCapabilitiesSchema}=await import(c.module);
const results=[]; const adapters=[];
try {
  for (const [side,runtime] of [['left','oj'],['right','as']]) {
    const baseUrl=c.base+runtime+'/';
    const headers={Authorization:'Bearer '+c.token};
    const capabilities=runtimeLauncherCapabilitiesSchema.parse(await (await fetch(baseUrl+'v1/runtime-capabilities',{headers})).json());
    const adapter=new RuntimeLauncherAdapter({baseUrl,headers,side,model:{providerId:'test',modelId:c.request.modelId,parameters:{}},
      variant:c.request.variant,skill:c.request.skill,environment:c.request.environment,timeoutMs:1000});
    adapters.push(adapter); await adapter.prepare({runId:'protocol-run'});
    for (let n=0;n<2;n++) {
      const evidence=await adapter.execute({runId:'protocol-run',side,evalCase:{caseId:side+'-'+n,prompt:'Controlled protocol case',requiredTools:['balance']}});
      results.push({runtime:capabilities.runtime,caseId:evidence.caseId,configurationDigest:evidence.metadata.instanceConfigurationDigest,verifiedSchema:true});
    }
  }
} finally { for (const adapter of adapters.reverse()) await adapter.cleanup(); }
console.log(JSON.stringify({mode:'CONTROLLED_PROTOCOL_ONLY',cases:results}));
"""
    try:
        completed = subprocess.run(["node", "--input-type=module", "-e", script], input=json.dumps(config),
                                   text=True, capture_output=True, timeout=30)
        if completed.returncode:
            # A synthetic token is passed via stdin and is never written to evidence.
            raise RuntimeError(completed.stderr.replace(token, "[redacted]"))
        report = json.loads(completed.stdout)
        assert len(report["cases"]) == 4 and len(backend.closed) == 2
        assert all(i.deleted for i in launcher.instances.values())
        report["cleanupVerified"] = True
        report["evalClientSha256"] = hashlib.sha256(module.read_bytes()).hexdigest()
        report["nativeRuntimeExecuted"] = False
        report["realModelExecuted"] = False
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2) + "\n")
        print("PASS: actual Eval client, two protocol targets, four cases, cleanup; no native Runtime/model.")
    finally:
        server.shutdown()
        server.server_close()
        thread.join()
        launcher.close()


if __name__ == "__main__":
    main()
