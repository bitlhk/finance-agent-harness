"""Compose explicit Harness-owned metadata requirements; never infer grants or support."""
import hashlib
import json
from pathlib import Path


def compose(root: Path, selected: Path, capabilities):
    root = root.resolve()
    sources = {}

    def read(path):
        # Deployments expose the pinned Harness release through /opt/.../harness.
        # Accept that parent alias while continuing to reject a source file that
        # is itself a symlink or resolves outside the pinned repository root.
        resolved = path.resolve()
        if path.is_symlink() or not resolved.is_relative_to(root) or not resolved.is_file() or resolved.stat().st_size > 65536:
            raise ValueError('OBSERVATION_ASSET_INVALID')
        raw = resolved.read_bytes()
        sources[resolved.relative_to(root).as_posix()] = raw
        return json.loads(raw)

    def reference(value):
        if not isinstance(value, str) or not value.startswith('repo://'):
            raise ValueError('OBSERVATION_REFERENCE_INVALID')
        return root / value.removeprefix('repo://')

    data = read(selected)
    if data.get('schemaVersion') == 'finance-observation-requirements.v0.1':
        return data, sources
    if data.get('schemaVersion') != 'finance-observation-selection.v0.1' or set(data) != {
            'schemaVersion', 'id', 'version', 'common', 'units'}:
        raise ValueError('OBSERVATION_SELECTION_INVALID')
    installed = set(capabilities)
    covered, documents = set(), [read(reference(ref)) for ref in data['common']]
    for unit in data['units']:
        if set(unit) != {'id', 'capabilityIds', 'source'}:
            raise ValueError('OBSERVATION_UNIT_INVALID')
        scope = set(unit['capabilityIds'])
        if not scope or covered & scope:
            raise ValueError('OBSERVATION_UNIT_OVERLAP')
        covered |= scope
        if not installed & scope:
            continue
        if not scope <= installed:
            raise ValueError('OBSERVATION_UNIT_INCOMPLETE')
        documents.append(read(reference(unit['source'])))
    if not installed or not installed <= covered:
        raise ValueError('OBSERVATION_SELECTION_UNCOVERED')
    requirements = {}
    for document in documents:
        if document.get('schemaVersion') != 'finance-observation-requirements.v0.1':
            raise ValueError('OBSERVATION_VERSION_UNSUPPORTED')
        for requirement in document['requirements']:
            key = requirement['semanticId']
            scope = set(requirement['capabilityIds'])
            if not scope <= installed:
                raise ValueError('OBSERVATION_REQUIREMENT_UNINSTALLED')
            if key in requirements:
                previous = requirements[key]
                if {k: v for k, v in previous.items() if k != 'capabilityIds'} != {
                        k: v for k, v in requirement.items() if k != 'capabilityIds'}:
                    raise ValueError('OBSERVATION_REQUIREMENT_CONFLICT')
                previous['capabilityIds'] = sorted(set(previous['capabilityIds']) | scope)
            else:
                requirements[key] = {**requirement, 'capabilityIds': sorted(scope)}
    combined = {'schemaVersion': 'finance-observation-requirements.v0.1', 'id': data['id'],
                'version': data['version'], 'requirements': [requirements[k] for k in sorted(requirements)]}
    return combined, sources


def materialize(root, selected, candidate, capabilities):
    combined, sources = compose(root, selected, capabilities)
    for name, raw in sources.items():
        path = candidate / 'assets' / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(raw)
    name = 'observation-composed.json'
    raw = (json.dumps(combined, sort_keys=True, indent=2) + '\n').encode()
    (candidate / 'assets' / name).write_bytes(raw)
    return {'id': combined['id'], 'version': combined['version'], 'source': 'repo://' + name,
            'digest': 'sha256:' + hashlib.sha256(raw).hexdigest()}
