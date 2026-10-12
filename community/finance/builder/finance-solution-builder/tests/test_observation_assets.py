import json
import pytest
from finance_solution_builder.observation_assets import compose


def selection(tmp_path):
    units = [('balance', ['balance']), ('wealth', ['wealth']), ('cashflow', ['summary', 'analysis'])]
    for name, scope in units:
        (tmp_path / (name + '.json')).write_text(json.dumps({
            'schemaVersion': 'finance-observation-requirements.v0.1', 'id': name, 'version': '0.1',
            'requirements': [{'semanticId': 'capability.result', 'semanticVersion': '0.1',
                'obligation': 'REQUIRED', 'boundary': 'PROVIDER', 'content': 'METADATA',
                'fields': ['businessStatus'], 'capabilityIds': scope}]}))
    entry = tmp_path / 'selection.json'
    entry.write_text(json.dumps({'schemaVersion': 'finance-observation-selection.v0.1', 'id': 'readonly',
        'version': '0.1', 'common': [], 'units': [dict(id=name, capabilityIds=scope,
        source='repo://' + name + '.json') for name, scope in units]}))
    return entry, units


@pytest.mark.parametrize('bits', range(1, 8))
def test_three_units_seven_combinations(tmp_path, bits):
    entry, units = selection(tmp_path)
    installed = [cap for i, (_, scope) in enumerate(units) if bits & (1 << i) for cap in scope]
    value, sources = compose(tmp_path, entry, installed)
    assert value['requirements'][0]['capabilityIds'] == sorted(installed)
    assert len(sources) == 1 + bits.bit_count()


@pytest.mark.parametrize('capabilities', [[], ['unknown'], ['summary']])
def test_uncovered_and_partial_units_reject(tmp_path, capabilities):
    entry, _ = selection(tmp_path)
    with pytest.raises(ValueError):
        compose(tmp_path, entry, capabilities)


def test_conflicting_requirement_does_not_silently_downgrade(tmp_path):
    entry, _ = selection(tmp_path)
    path = tmp_path / 'wealth.json'
    value = json.loads(path.read_text())
    value['requirements'][0]['obligation'] = 'OPTIONAL'
    path.write_text(json.dumps(value))
    with pytest.raises(ValueError, match='CONFLICT'):
        compose(tmp_path, entry, ['balance', 'wealth'])


def test_selection_below_parent_symlink_uses_pinned_root(tmp_path):
    repository = tmp_path / 'release' / 'harness'
    repository.mkdir(parents=True)
    entry, _ = selection(repository)
    alias = tmp_path / 'harness'
    alias.symlink_to(repository, target_is_directory=True)

    value, sources = compose(repository, alias / entry.name, ['wealth'])

    assert value['requirements'][0]['capabilityIds'] == ['wealth']
    assert sorted(sources) == ['selection.json', 'wealth.json']
