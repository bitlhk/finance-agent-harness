"""Source/ancestry gate. This checks text preservation, not LLM adherence."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

from finance_solution_builder.business_assets import ancestry, catalog_bindings
from finance_solution_builder.directory import describe_directory
from finance_solution_builder.method_guides import describe_guide
from finance_solution_builder.service import CAPABILITIES, solution_for


def check_text(old, new, method):
    for before, after in method['constraints']:
        assert before in old, ('BASELINE_CONSTRAINT_NOT_FOUND', before)
        assert after in new, ('CHINESE_CONSTRAINT_MISSING', after)
    for term in method['machineTerms']:
        assert term in old and term in new, ('MACHINE_TERM_CHANGED', term)


def verify(harness, project):
    spec = json.loads((harness / 'conformance/fixtures/methods/chinese-readonly.v0.1.json').read_text())
    upstream = spec['upstream']
    raw = (harness / upstream['snapshot']).read_bytes()
    assert hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest() == upstream['gitBlob'], 'UPSTREAM_SNAPSHOT_CHANGED'
    adapted = (harness / upstream['adaptation']).read_text()
    check_text(raw.decode(), adapted, {'constraints': upstream['preserved'], 'machineTerms': []})
    builder_root = Path(subprocess.check_output(['git', '-C', str(project), 'rev-parse', '--show-toplevel'], text=True).strip())
    receipts = []
    for method in spec['methods']:
        root = harness if method['source'] == 'harness' else project
        git_root = harness if method['source'] == 'harness' else builder_root
        path = root / method['path']
        old = subprocess.check_output(['git', '-C', str(git_root), 'show',
            spec[method['source'] + 'Baseline'] + ':' + path.relative_to(git_root).as_posix()], text=True)
        new = path.read_text()
        check_text(old, new, method)
        receipts.append({'path': method['path'], 'version': method['version'],
                         'constraints': len(method['constraints']),
                         'sha256': hashlib.sha256(path.read_bytes()).hexdigest()})
    catalog_bindings(harness, solution_for(sorted(CAPABILITIES), 'chinese-methods-check'))
    directory = describe_directory(harness)
    for component in ('account.balance.query', 'wealth.facts.describe', 'finance.cashflow.readonly'):
        binding, text = ancestry(project, harness, component, directory['revision'])
        guide = describe_guide(component, harness, project)
        assert guide['status'] == 'SOURCE_MATCHED', (component, guide)
        for source in guide['sources']:
            kind, relative = source['source'].split('://', 1)
            body = ((harness if kind == 'repo' else project) / relative).read_text()
            assert body in text, ('COPY_DIFFERS_FROM_DEFAULT', component)
        assert all(ref['version'] != 'content-addressed' for ref in binding['copiedFrom'])
    return {'status': 'PASS', 'scope': spec['scope'], 'methods': receipts,
            'upstream': {'commit': upstream['commit'], 'gitBlob': upstream['gitBlob'],
                         'preservedRules': len(upstream['preserved'])},
            'businessUnits': 3, 'catalogRevision': directory['revision']}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--harness', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = verify(args.harness.resolve(), Path(__file__).resolve().parents[1])
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(result, ensure_ascii=False))
