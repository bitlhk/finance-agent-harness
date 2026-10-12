"""Preserved actual generator plans against a packaged Java guard; no network."""
from copy import deepcopy
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

import pytest

from finance_solution_builder import agentscope_service as service

PROJECT = Path(__file__).resolve().parents[1]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


@pytest.mark.skipif(not os.environ.get("FH_DIGEST_PROBE_CANDIDATE"), reason="fixed packaged Host required")
def test_generated_digest_contract_against_packaged_java(tmp_path):
    packaged = Path(os.environ["FH_DIGEST_PROBE_CANDIDATE"]).resolve()
    output = Path(os.environ["FH_DIGEST_PROBE_OUTPUT"]).resolve()
    output.mkdir(parents=True, exist_ok=False)
    original = Path(os.environ["FH_DIGEST_PROBE_GENERATED"]).resolve()
    generated = tmp_path / "generated"
    shutil.copytree(original, generated)
    original_plan = json.loads((generated / "component-plan.json").read_bytes())
    original_lock = json.loads((generated / "service-lock.json").read_bytes())
    binding = original_lock["runtimeBinding"]
    composed = binding["managedTemplate"]["digest"]
    inventory = service.host_template_digest(PROJECT, binding)
    assert composed != inventory
    assert original_lock["verification"] == "NOT_RUN"
    assert original_lock["hostTemplateDigest"] == inventory
    assert original_plan["managedTemplate"]["digest"] == composed
    assert sha(original / "component-plan.json") == sha(packaged / "component-plan.json")
    sources = json.loads((packaged / "service-lock.json").read_bytes())["sharedHostSources"]
    relative = "local-readonly-service/src/main/java/io/github/bitlhk/finance/builder/local/ComponentAdmission.java"
    assert sha(PROJECT / "templates" / relative) == sources[relative]
    jars = sorted((packaged / "target").rglob("*.jar"))
    jar_hashes = {str(path): sha(path) for path in jars}
    classpath = os.pathsep.join(str(path) for path in jars)
    probe = output / "DigestShapeProbe.java"
    probe.write_text('''package io.github.bitlhk.finance.builder.local;
import java.nio.file.Path;
public class DigestShapeProbe {
  public static void main(String[] args) throws Exception {
    var guard = ComponentAdmission.class.getDeclaredMethod("lockedPlan", Path.class);
    guard.setAccessible(true);
    try { guard.invoke(null, Path.of(args[0])); System.out.println("PLAN_ACCEPTED"); }
    catch (java.lang.reflect.InvocationTargetException failure) {
      System.out.println(failure.getCause().getMessage()); System.exit(3);
    }
  }
}
''')
    classes = output / "classes"
    classes.mkdir()
    compiled = subprocess.run(["javac", "-cp", classpath, "-d", str(classes), str(probe)],
                              capture_output=True, text=True, timeout=45)
    assert compiled.returncode == 0, compiled.stderr
    results = {}
    for case in ("actualGenerator", "inventoryAsPlan", "wrongPlan", "wrongLockProjection",
                 "wrongLockBinding", "wrongPlanBinding", "missingLockProjection", "missingLockBinding"):
        plan, lock = deepcopy(original_plan), deepcopy(original_lock)
        if case == "inventoryAsPlan":
            plan["managedTemplate"]["digest"] = inventory
        elif case == "wrongPlan":
            plan["managedTemplate"]["digest"] = "sha256:" + "0" * 64
        elif case == "wrongLockProjection":
            lock["managedTemplateDigest"] = inventory
        elif case == "wrongLockBinding":
            lock["runtimeBinding"]["managedTemplate"]["digest"] = inventory
        elif case == "wrongPlanBinding":
            plan["runtimeBinding"]["managedTemplate"]["digest"] = inventory
        elif case == "missingLockProjection":
            del lock["managedTemplateDigest"]
        elif case == "missingLockBinding":
            del lock["runtimeBinding"]
        (generated / "component-plan.json").write_text(json.dumps(plan))
        for name in ("component-plan.json", "host-support.v0.1.json"):
            lock["files"][name] = sha(generated / name)
        (generated / "service-lock.json").write_text(json.dumps(lock))
        result = subprocess.run(["java", "-cp", str(classes) + os.pathsep + classpath,
                                 "io.github.bitlhk.finance.builder.local.DigestShapeProbe", str(generated)],
                                capture_output=True, text=True, timeout=30)
        expected = 0 if case == "actualGenerator" else 3
        results[case] = {"returnCode": result.returncode, "stdout": result.stdout.strip(),
                         "stderr": result.stderr, "passed": result.returncode == expected and
                         ("PLAN_ACCEPTED" if expected == 0 else "COMPONENT_HOST_SUPPORT_MISMATCH") == result.stdout.strip()}
    evidence = {"scope": "PRESERVED_ACTUAL_GENERATOR_PLAN_PACKAGED_COMPONENT_GUARD",
                "fullCandidateAdmission": "NOT_CLAIMED", "modelHttp": 0, "bankHttp": 0,
                "managedTemplateDigest": composed, "hostTemplateDigest": inventory,
                "originalGeneratorPlanSha256": sha(original / "component-plan.json"),
                "qualifiedCandidatePlanSha256": sha(packaged / "component-plan.json"),
                "packagedJarHashes": jar_hashes, "generatorSha256": sha(Path(service.__file__)),
                "componentAdmissionSourceSha256": sources[relative], "results": results}
    (output / "result.json").write_text(json.dumps(evidence, indent=2) + "\n")
    assert jar_hashes == {str(path): sha(path) for path in jars}
    assert all(result["passed"] for result in results.values()), results
