"""Compare extracted projections against the frozen pre-refactor implementation."""
import json
import os
from pathlib import Path
import shutil
import subprocess

import pytest

PROJECT = Path(__file__).resolve().parents[1]
PACKAGE = "io/github/bitlhk/finance/builder/local"
SOURCES = PROJECT / "templates/local-readonly-service/src/main/java" / PACKAGE
NAMES = ["DisplayProjection", "DisplayFields", "BusinessDisplays", "BalanceDisplay",
         "CashflowDisplay", "WealthDisplay", "StructuredDisplay"]


def test_shared_sources_include_display_dependencies():
    shared = json.loads((PROJECT / "templates/agentscope-query-service/shared-sources.json").read_text())
    assert {name + ".java" for name in NAMES} <= set(shared["main"])


def test_frozen_display_equivalence(tmp_path):
    if not shutil.which("javac") or not shutil.which("java"):
        pytest.skip("JDK required")
    repository = Path.home() / ".m2/repository"
    jars = [repository / "io/github/bitlhk/finance/finance-harness-core/0.1.0-SNAPSHOT/finance-harness-core-0.1.0-SNAPSHOT.jar"]
    for group, artifact in (("core", "jackson-core"), ("core", "jackson-databind"),
                            ("core", "jackson-annotations"), ("datatype", "jackson-datatype-jsr310")):
        found = sorted((repository / "com/fasterxml/jackson" / group / artifact).glob("*/*.jar"))
        if not found:
            pytest.skip("cached Jackson required")
        jars.append(found[-1])
    if not jars[0].exists():
        pytest.skip("cached Harness core required")
    for relative in ["junit/junit/4.13.2/junit-4.13.2.jar",
                     "org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar"]:
        jar = repository / relative
        if not jar.exists():
            pytest.skip("cached JUnit required")
        jars.append(jar)
    for name in NAMES:
        shutil.copyfile(SOURCES / (name + ".java"), tmp_path / (name + ".java"))
    shutil.copyfile(PROJECT / "templates/local-readonly-service/src/test/java" / PACKAGE / "DisplayProjectionTest.java",
                    tmp_path / "DisplayProjectionTest.java")
    relative = "community/finance/builder/finance-solution-builder/templates/local-readonly-service/src/main/java/" + PACKAGE + "/DisplayProjection.java"
    original = subprocess.run(["git", "show", "bf7d2679:" + relative], cwd=PROJECT,
                              check=True, capture_output=True, text=True).stdout
    (tmp_path / "LegacyDisplayProjection.java").write_text(original.replace("DisplayProjection", "LegacyDisplayProjection"))
    (tmp_path / "ProjectionProbe.java").write_text("""
package io.github.bitlhk.finance.builder.local;
import java.util.*;
import java.nio.file.*;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.bitlhk.finance.harness.core.HarnessJson;
public class ProjectionProbe {
    public static void main(String[] args) throws Exception {
        int count = 0;
        for (String line : Files.readAllLines(Path.of(args[0]))) {
            JsonNode fixture = HarnessJson.mapper().readTree(line);
            List<Object> tools = new ArrayList<>();
            fixture.path("tools").forEach(tools::add);
            Set<String> installed = new HashSet<>();
            fixture.path("installed").forEach(n -> installed.add(n.asText()));
            JsonNode before = HarnessJson.mapper().valueToTree(LegacyDisplayProjection.cards(tools, installed));
            JsonNode after = HarnessJson.mapper().valueToTree(DisplayProjection.cards(tools, installed));
            if (!before.equals(after)) throw new AssertionError("Fixture " + count);
            count++;
        }
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        Map<String, java.util.function.Function<JsonNode, List<Object>>> registry =
            Map.of("new.query", data -> { calls.incrementAndGet(); return List.of(Map.of("type", "new")); });
        var tool = Map.of("data", Map.of("capabilityId", "new.query", "resultStatus", "COMPLETED"));
        if (!DisplayProjection.cards(List.of(tool), Set.of(), registry).isEmpty() || calls.get() != 0)
            throw new AssertionError("Uninstalled projection invoked");
        if (DisplayProjection.cards(List.of(tool), Set.of("new.query"), registry).size() != 1 || calls.get() != 1)
            throw new AssertionError("New projection requires dispatcher changes");
        var view = HarnessJson.mapper().valueToTree(Map.of("factsVersion", "example.v1", "title", "Page",
            "scope", "Current page only", "rows", "items", "fields", Map.of("amount", "Amount")));
        var data = HarnessJson.mapper().valueToTree(Map.of("facts", Map.of("schemaVersion", "example.v1",
            "hasMore", true, "accountId", "private", "items", List.of(Map.of("amount", "12.30", "accountId", "private")))));
        JsonNode projected = HarnessJson.mapper().valueToTree(StructuredDisplay.project(data, view));
        if (!projected.get(0).path("context").path("hasMore").asBoolean() || projected.toString().contains("private"))
            throw new AssertionError("Pagination flag or allowlist lost");
        if (!StructuredDisplay.project(HarnessJson.mapper().valueToTree(Map.of()), view).isEmpty())
            throw new AssertionError("Unknown facts version accepted");
        System.out.println("Equivalent fixtures: " + count);
    }
}
""")
    capabilities = ["account.balance.query", "wealth.facts.describe", "finance.transaction-summary.query",
                    "finance.spending-analysis.query", "wealth.customer-context.query",
                    "transfer.internal.create", "channel.preferences.update", "unknown.query"]
    facts = [{}]
    for section in ["SUMMARY", "ANALYSIS"]:
        facts.append({"schemaVersion": "finance-cashflow-facts.v0.1", "section": section,
                      "period": "2026-08", "currency": "CNY",
                      "totals": {"income": "100.10", "expense": "20.00", "netCashflow": "80.10"},
                      "categories": [{"name": "Food", "transactionCount": 2, "totals": {"expense": "20.00"}}],
                      "trend": [{"period": "2026-07", "totals": {"income": "0.00"}}],
                      "gaps": ["PARTIAL", 1], "source": {"system": "synthetic", "recordVersion": "v1"}})
    facts.append({"schemaVersion": "wealth-facts.v0.1",
                  "assets": {"amounts": [{"money": {"value": "10.01", "currency": "CNY"}}],
                             "availability": "PARTIAL", "missingSources": ["positions"]},
                  "risk": {"availability": "MISSING"}, "source": {"kind": "synthetic"}})
    fixtures = []
    for cap in capabilities:
        for status in ["COMPLETED", "FAILED", "NEED_USER", "NEED_CONFIRMATION"]:
            for fact in facts:
                for installed in [[], capabilities]:
                    fixtures.append({"installed": installed, "tools": [{"data": {
                        "capabilityId": cap, "resultStatus": status, "facts": fact,
                        "description": {"gaps": [{"field": "risk", "reason": "missing"}]},
                        "accounts": [{"currency": "CNY", "availableBalance": "90071992547409.91",
                                      "accountId": "private-id"}, {"currency": "CNY", "availableBalance": None}]}}]})
    fixtures.append({"installed": capabilities, "tools": [f["tools"][0] for f in fixtures[1:32:2]]})
    path = tmp_path / "fixtures.jsonl"
    path.write_text("\n".join(json.dumps(f) for f in fixtures))
    classpath = os.pathsep.join(map(str, jars))
    subprocess.run(["javac", "-cp", classpath, "-d", str(tmp_path),
                    *map(str, tmp_path.glob("*.java"))], check=True, capture_output=True, text=True, timeout=30)
    result = subprocess.run(["java", "-cp", os.pathsep.join((str(tmp_path), classpath)),
                             "io.github.bitlhk.finance.builder.local.ProjectionProbe", str(path)],
                            check=True, capture_output=True, text=True, timeout=30)
    assert f"Equivalent fixtures: {len(fixtures)}" in result.stdout
    subprocess.run(["java", "-cp", os.pathsep.join((str(tmp_path), classpath)), "org.junit.runner.JUnitCore",
                    "io.github.bitlhk.finance.builder.local.DisplayProjectionTest"],
                   check=True, capture_output=True, text=True, timeout=30)
