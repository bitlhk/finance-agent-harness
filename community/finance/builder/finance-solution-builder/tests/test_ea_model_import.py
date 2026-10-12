import importlib.util
from pathlib import Path

import pytest

spec = importlib.util.spec_from_file_location("ea_import", Path(__file__).resolve().parents[1] / "engineering/sync_ea_model_catalog.py")
importer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(importer)


def test_discovery_preserves_ids_across_alias_changes_and_distinguishes_connections():
    previous = [dict(modelRef="model-persisted-a", eaCatalogId="a", modelName="same-model", provider="OpenAI"),
                dict(modelRef="model-persisted-b", eaCatalogId="b", modelName="same-model", provider="OpenAI")]
    rows = [dict(id=x, name="Renamed " + x, modelName="same-model", provider="OpenAI") for x in "ab"]
    result = importer.merge_metadata(rows, previous)
    assert [r["modelRef"] for r in result] == ["model-persisted-a", "model-persisted-b"]
    with pytest.raises(ValueError, match="AMBIGUOUS"):
        importer.merge_metadata([dict(rows[0], id="changed-id")], previous)


@pytest.mark.parametrize("override", [{"id": "__auto"}, {"modelName": "__auto"}, {"key": "secret"},
                                     {"name": "https://private.invalid/v1"}, {"provider": None}])
def test_discovery_rejects_virtual_routing_and_nonmetadata(override):
    row = dict(id="a", name="A", modelName="test-model", provider="OpenAI")
    row.update(override)
    with pytest.raises(ValueError, match="EA_METADATA_INVALID"):
        importer.merge_metadata([row], [])
