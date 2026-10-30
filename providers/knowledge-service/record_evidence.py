"""Archive only controlled probe receipts and JUnit counts, never environment properties."""
import hashlib
import json
from pathlib import Path
import shutil
import xml.etree.ElementTree as ET

module = Path(__file__).resolve().parent
root = module.parents[1]
out = module / "evidence"
out.mkdir(exist_ok=True)
shutil.copyfile(module / "target/probe-records.json", out / "http-records.json")
counts = {}
for name, folder in [("core", root / "packages/java/core"), ("knowledgeProvider", module)]:
    totals = {key: 0 for key in ("tests", "failures", "errors", "skipped")}
    for file in (folder / "target/surefire-reports").glob("TEST-*.xml"):
        suite = ET.parse(file).getroot()
        for key in totals:
            totals[key] += int(suite.get(key, "0"))
    counts[name] = totals
jar = module / "target/finance-provider-knowledge-service-0.1.0-probe.1.jar"
report = {"schemaVersion": "knowledge-h0-receipt.v0.1", "baseline": "e911c71bdd3404f450bf775f32ebe028f90fb475",
          "testCounts": counts, "artifact": jar.name, "artifactSha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
          "manifestSha256": hashlib.sha256((module / "fixtures/manifest.json").read_bytes()).hexdigest(),
          "level": "H0_ONLY", "agentInstallationLock": None, "agentUiToolCall": "NOT_RUN",
          "modelCalls": 0, "productionDeployment": False}
(out / "h0-summary.json").write_text(json.dumps(report, indent=2) + "\n")
index = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(out.glob("*.json")) if p.name != "INDEX.json"}
(out / "INDEX.json").write_text(json.dumps(index, indent=2) + "\n")
print(json.dumps(report, indent=2))
