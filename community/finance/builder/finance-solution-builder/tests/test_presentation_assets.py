import json
from pathlib import Path
import pytest
from finance_solution_builder.presentation_assets import presentation_manifest


def fixture(tmp_path):
    folder = tmp_path / "capability-packs/example"
    folder.mkdir(parents=True)
    (folder / "pack.yaml").write_text('capabilities:\n  - capabilityId: example.query\n    operation: QUERY\n')
    view = dict(capabilityId="example.query", factsVersion="example.v1", title="Example",
                scope="Current page only", rows="items", fields={"amount": "Amount"})
    path = folder / "presentation.v0.1.json"
    path.write_text(json.dumps(dict(schemaVersion="finance-pack-presentation.v0.1", views=[view])))
    plan = dict(capabilityPackRefs=[dict(source="repo://capability-packs/example")], capabilityBindings={"example.query": "provider"})
    return path, view, plan


def test_optional_bounded_pack_views(tmp_path):
    path, view, plan = fixture(tmp_path)
    assert presentation_manifest(tmp_path, plan)["views"] == [view]
    path.unlink()
    assert presentation_manifest(tmp_path, plan)["views"] == []


@pytest.mark.parametrize("change", [
    {"capabilityId": "another.query"}, {"script": "alert(1)"}, {"rows": "../../secret"},
    {"fields": {"accountId": "Account"}}, {"fields": {"x.y": "Nested"}},
    {"fields": {}}, {"title": ""}, {"scope": "x" * 241},
])
def test_rejects_executable_unknown_private_and_unbounded_fields(tmp_path, change):
    path, view, plan = fixture(tmp_path)
    view.update(change)
    path.write_text(json.dumps(dict(schemaVersion="finance-pack-presentation.v0.1", views=[view])))
    with pytest.raises(ValueError):
        presentation_manifest(tmp_path, plan)


def test_uninstalled_and_duplicate_views_rejected(tmp_path):
    path, view, plan = fixture(tmp_path)
    plan["capabilityBindings"] = {}
    with pytest.raises(ValueError): presentation_manifest(tmp_path, plan)
    plan["capabilityBindings"] = {"example.query": "provider"}
    path.write_text(json.dumps(dict(schemaVersion="finance-pack-presentation.v0.1", views=[view, view])))
    with pytest.raises(ValueError): presentation_manifest(tmp_path, plan)


def test_symlink_and_path_escape_rejected(tmp_path):
    path, view, plan = fixture(tmp_path)
    source = tmp_path / "outside.json"
    path.rename(source); path.symlink_to(source)
    with pytest.raises(ValueError): presentation_manifest(tmp_path, plan)
    plan["capabilityPackRefs"][0]["source"] = "repo://../outside"
    with pytest.raises(ValueError): presentation_manifest(tmp_path, plan)


def test_changed_embedded_manifest_forbids_binary_reuse(tmp_path):
    from finance_solution_builder.service import reuse_binaries
    source, target = tmp_path / "old", tmp_path / "new"
    for root, data in [(source, '{"views":[]}'), (target, '{"views":[{}]}')]:
        resource = root / "src/main/resources/capability-presentations.json"
        resource.parent.mkdir(parents=True)
        resource.write_text(data)
    with pytest.raises(ValueError, match="REUSABLE_PRESENTATION_MANIFEST_CHANGED"):
        reuse_binaries(None, source, target)
