from pathlib import Path

from finance_solution_builder.skill_analysis import analyze_skill


HARNESS = Path(__file__).resolve().parents[1] / "tests/fixtures/business-assets"


def test_declared_known_and_unknown_tools_keep_source_locations():
    source = """---
name: asset-check
allowed-tools:
  - wealth_facts_describe
  - customer_secret_write
---
Call wealth_facts_describe and read references/policy.md.
Do not execute scripts/check.py.
"""
    result = analyze_skill(source, HARNESS)
    assert result["dependencies"][0]["capabilityId"] == "wealth.facts.describe"
    assert result["dependencies"][0]["locations"]
    assert result["unknownTools"][0]["toolName"] == "customer_secret_write"
    assert {item["status"] for item in result["resourceReferences"]} == {"REFERENCED", "ANALYZE_ONLY"}
    assert result["professionalQuality"] == "NOT_EVALUATED"


def test_no_tool_is_reported_as_uncertain_not_bound():
    result = analyze_skill("# Method\nSummarize supplied text.", HARNESS)
    assert result["dependencies"] == [] and result["unknownTools"] == []
    assert result["uncertainties"]


def test_explicit_unknown_tool_in_body_is_pending_integration():
    result = analyze_skill("# 产品条款\n调用 `custom_bank_lookup` 获取产品条款。", HARNESS)
    assert result["dependencies"] == []
    assert result["unknownTools"] == [{"toolName":"custom_bank_lookup", "status":"PENDING_INTEGRATION",
                                       "locations":[{"file":"SKILL.md", "line":2, "kind":"BODY_REFERENCE"}]}]
