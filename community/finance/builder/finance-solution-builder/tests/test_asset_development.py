from finance_solution_builder.asset_contents import development_relations


def value(content):
    return {"files": [{"role": "SKILL", "path": "SKILL.md", "content": content}],
            "bindings": [{"toolName": "lookup"}]}


def test_declared_tools_not_inferred_from_prose():
    result = development_relations(value("---\nallowed-tools: lookup missing\n---\nUse invented_tool"))
    skill = result["development"]["skills"][0]
    assert skill["declaredTools"] == ["lookup", "missing"]
    assert skill["unresolvedTools"] == ["missing"]
    assert result["development"]["executionVerified"] is False


def test_missing_header_does_not_claim_dependency():
    skill = development_relations(value("Call lookup"))["development"]["skills"][0]
    assert skill["status"] == "NOT_DECLARED"
    assert skill["declaredTools"] == []


def test_list_duplicate_and_bad_yaml():
    skill = development_relations(value("---\nallowed-tools: [lookup, lookup]\n---\n"))["development"]["skills"][0]
    assert skill["declaredTools"] == ["lookup"]
    skill = development_relations(value("---\nallowed-tools: [\n---\n"))["development"]["skills"][0]
    assert skill["status"] == "INVALID_DECLARATION"
