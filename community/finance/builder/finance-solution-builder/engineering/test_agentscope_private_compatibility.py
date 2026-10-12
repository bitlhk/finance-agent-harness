"""Actual private-method generator/native prepare; not a Host collector qualification."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

import pytest

from finance_solution_builder.agentscope_service import AgentScopeServiceBuild
from finance_solution_builder.builder import SolutionBuilder
from finance_solution_builder.managed_runtime_support import declarations
from finance_solution_builder.method_assets import canonical, installation_digest, parse_content, runtime_files
from finance_solution_builder.unified_catalog import unified_catalog
from finance_solution_builder.workbench_contracts import parse_selection

PROJECT = Path(__file__).resolve().parents[1]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


@pytest.mark.skipif(not os.environ.get("FH_PRIVATE_PROBE_CANDIDATE"), reason="fixed packaged native Host required")
def test_actual_private_method_prepare_and_strict_boundaries():
    output = Path(os.environ["FH_PRIVATE_PROBE_OUTPUT"]).resolve()
    output.mkdir(parents=True, exist_ok=False)
    packaged = Path(os.environ["FH_PRIVATE_PROBE_CANDIDATE"]).resolve()
    harness = Path(os.environ["FH_HARNESS_REPO"]).resolve()
    environment = {"FH_CANDIDATE_RUNTIME_IDS": "agentscope-java-2.0.3"}
    catalog = unified_catalog(PROJECT, harness, environment=environment)
    directory = catalog["templateModuleDirectory"]
    entry = next(item for item in catalog["entries"] if item["pack"]["id"] == "account-balance-readonly")
    runtime = next(item for item in declarations(PROJECT) if item["id"] == "agentscope-java-2.0.3")
    host = next(item for item in runtime["hosts"] if item["id"] == "agentscope-query-service")
    data = parse_content({"name": "Private compatibility probe", "content": "# Method\nPRIVATE-PROFILE-PROBE",
                          "capabilityIds": ["account.balance.query"],
                          "cases": [{"name": "balance", "query": "Read my balance", "expectedTerms": ["balance"]}]})
    asset, version = "method-0123456789abcdef", "0.0.0"
    files = runtime_files(asset, version, data)
    snapshot = {"assetId": asset, "version": version, "data": data,
                "releaseDigest": "sha256:" + hashlib.sha256(canonical(data)).hexdigest(),
                "installationDigest": installation_digest(files)}
    selection = {"extension_type": "finance-workbench.selection", "schema_version": "0.10",
                 "runtimeId": runtime["id"], "hostId": host["id"], "profileRef": host["profileRef"],
                 "applicationTemplateId": "banking-readonly-web-v1", "bankConnectionId": "local-simulator",
                 "scenarioTemplateId": "customer-custom", "directoryRevision": directory["revision"],
                 "selectedModelRefs": ["model-controlled"], "defaultModelRef": "model-controlled",
                 "modelCatalogRevision": "0" * 64,
                 "moduleIds": [m["id"] for m in directory["modules"] if entry["componentId"] in m["componentIds"]],
                 "packBindings": [{"pack": entry["pack"], "provider": entry["providers"][0]}],
                 "capabilityIds": ["account.balance.query"],
                 "methodRef": {key: snapshot[key] for key in ("assetId", "version", "releaseDigest")}}
    write(output / "selection-input.json", selection)
    solution, resolved, plan = parse_selection(selection).resolve_details(
        PROJECT, harness, "private-profile-probe", snapshot, environment=environment)
    builder = SolutionBuilder(PROJECT, harness, service_output_root=output)
    generated = AgentScopeServiceBuild(builder, solution, output / "generator", resolved["runtimeBinding"], None,
                                      method_snapshot=snapshot)
    generated.assemble()
    generated.build(verify=False)
    write(output / "selection-plan.json", plan)
    assert plan == generated.plan
    assert sha(output / "selection-plan.json") == sha(generated.candidate / "component-plan.json")
    private_root = generated.candidate / "assets/private-methods" / asset / version
    assert {name: (private_root / name).read_bytes() for name in files} == files
    assert json.loads((generated.candidate / "service-lock.json").read_bytes())["verification"] == "NOT_RUN"

    jars = sorted((packaged / "target").rglob("*.jar"))
    assert jars
    jar_hashes = {str(path): sha(path) for path in jars}
    classpath = os.pathsep.join(str(path) for path in jars)
    probe = output / "PrivateProfileProbe.java"
    probe.write_text('''package io.github.bitlhk.finance.builder.local;
import java.nio.file.Path;
import java.net.URI;
import java.time.Duration;
import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import io.github.bitlhk.finance.harness.integrations.agentscope.*;
public class PrivateProfileProbe {
  public static void main(String[] args) throws Exception {
    var credentials = new java.util.concurrent.atomic.AtomicInteger();
    var connection = new ManagedProviderFactoryV01.Connection("synthetic-bank-http.v1",
      URI.create("http://127.0.0.1:1"), Duration.ofSeconds(1),
      () -> { credentials.incrementAndGet(); throw new SecurityException("NO_GRANT_IN_PREPARE"); },
      () -> null, ignored -> {});
    try {
      var root = Path.of(args[0]);
      var prepared = AgentScopeAdmission.prepare(root, AgentScopeAdmission.providers(root, connection));
      if (!args[1].equals("prepare")) {
        var type = FinanceContextV1.PrincipalType.valueOf(args[1]);
        var identity = new AgentScopeQuerySession.Identity("synthetic", type, "synthetic", "probe",
          args[2], "probe", FinanceContextV1.AuthenticationLevel.STRONG, 1);
        prepared.openSession(identity, () -> { throw new AssertionError("GRANT_EVALUATED"); }, null);
        throw new AssertionError("INVALID_IDENTITY_ACCEPTED");
      }
      System.out.println(HarnessJson.mapper().writeValueAsString(prepared.snapshot()));
    } catch (Exception failure) {
      Throwable cause = failure;
      while (cause.getCause() != null) cause = cause.getCause();
      System.out.println(cause.getMessage());
      if (credentials.get() != 0) throw new AssertionError("CREDENTIALS_REQUESTED");
      System.exit(3);
    }
    if (credentials.get() != 0) throw new AssertionError("CREDENTIALS_REQUESTED");
  }
}
''')
    classes = output / "classes"
    classes.mkdir()
    compiled = subprocess.run(["javac", "-cp", classpath, "-d", str(classes), str(probe)],
                              capture_output=True, text=True, timeout=45)
    assert compiled.returncode == 0, compiled.stderr
    cases = {"actualPrivate": None, "previousSupport": "PROFILE_CAPABILITY_OR_DOMAIN_MISMATCH",
             "undeclaredDomain": "PROFILE_CAPABILITY_OR_DOMAIN_MISMATCH",
             "capabilityOutsideProfile": "PROFILE_CAPABILITY_OR_DOMAIN_MISMATCH",
             "employee": "PROFILE_SESSION_MISMATCH", "system": "PROFILE_SESSION_MISMATCH",
             "wrongPurpose": "PROFILE_SESSION_MISMATCH", "task": "UNSUPPORTED_AGENTSCOPE_MODE",
             "stream": "UNSUPPORTED_AGENTSCOPE_MODE", "fallback": "UNSUPPORTED_AGENTSCOPE_MODE",
             "modelRetry": "UNSUPPORTED_AGENTSCOPE_MODE", "toolRetry": "UNSUPPORTED_AGENTSCOPE_MODE"}
    results = {}
    for case, expected in cases.items():
        candidate = output / case
        shutil.copytree(generated.candidate, candidate)
        support = json.loads((candidate / "agentscope-support.json").read_bytes())
        profile = support["profiles"][0]
        if case == "previousSupport":
            profile["domainProfiles"] = ["customer-self-service"]
        elif case == "capabilityOutsideProfile":
            profile["allowedCapabilityIds"] = ["finance.transaction-summary.query", "finance.spending-analysis.query"]
        elif case == "undeclaredDomain":
            root = candidate / "assets/private-methods" / asset / version
            pack = json.loads((root / "pack.yaml").read_bytes())
            pack["profile"] = "undeclared-domain"
            write(root / "pack.yaml", pack)
            spec = json.loads((candidate / "solution.json").read_bytes())
            next(ref for ref in spec["domainPackRefs"] if ref["id"] == asset)["digest"] = installation_digest(
                {name: (root / name).read_bytes() for name in files})
            write(candidate / "solution.json", spec)
        elif case == "task":
            support["operation"] = "TASK"
        elif case in ("stream", "fallback"):
            support[case] = True
        elif case in ("modelRetry", "toolRetry"):
            support[case.replace("Retry", "Attempts")] = 2
        # Only negative fixtures are relocked; the generator output above stays immutable.
        if expected is not None:
            write(candidate / "agentscope-support.json", support)
            lock = json.loads((candidate / "service-lock.json").read_bytes())
            for name in ("solution.json", "agentscope-support.json"):
                lock["files"][name] = sha(candidate / name)
            write(candidate / "service-lock.json", lock)
        mode = {"employee": "EMPLOYEE", "system": "SYSTEM", "wrongPurpose": "CUSTOMER"}.get(case, "prepare")
        purpose = "customer-self-service" if case == "wrongPurpose" else "LOCAL_READONLY_DESCRIPTION"
        result = subprocess.run(["java", "-cp", str(classes) + os.pathsep + classpath,
                                 "io.github.bitlhk.finance.builder.local.PrivateProfileProbe", str(candidate), mode, purpose],
                                capture_output=True, text=True, timeout=45)
        passed = result.returncode == (0 if expected is None else 3)
        if expected is not None:
            passed = passed and result.stdout.strip() == expected
        elif passed:
            observed = json.loads(result.stdout)
            passed = any(m["packId"] == asset and m["version"] == version for m in observed["methods"])
        results[case] = {"returnCode": result.returncode, "stdout": result.stdout, "stderr": result.stderr,
                         "expected": expected or "PREPARED_WITH_PRIVATE_METHOD", "passed": passed}
    evidence = {"scope": "ACTUAL_SELECTION_GENERATOR_AND_PACKAGED_NATIVE_PREPARE",
                "hostCollectorQualification": "NOT_CLAIMED", "privateTrialExecution": "NOT_RUN",
                "modelConnection": "NOT_CREATED", "sourceSessionSupplierCalls": 0,
                "selectionPlanSha256": sha(output / "selection-plan.json"),
                "generatorPlanSha256": sha(generated.candidate / "component-plan.json"),
                "supportSourceSha256": sha(PROJECT / "templates/agentscope-query-service/agentscope-support.v0.1.json"),
                "privateInstallationDigest": snapshot["installationDigest"], "privateBytesPreserved": True,
                "packagedJarHashes": jar_hashes, "results": results}
    write(output / "result.json", evidence)
    assert jar_hashes == {str(path): sha(path) for path in jars}
    assert all(item["passed"] for item in results.values()), results
