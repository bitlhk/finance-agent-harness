"""Qualify Java/Python history and snapshot collectors using controlled HTTP."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

from finance_solution_builder.unified_catalog import unified_catalog
from finance_solution_builder.workbench import Jobs
from verify_runtime_capability_matrix import selection, wait
from finance_solution_builder.workbench_contracts import parse_selection
from verify_execution_history import verify as history
from verify_model_snapshots import verify as snapshots

PROJECT = Path(__file__).resolve().parents[1]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--harness', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--maven', required=True)
    parser.add_argument('--runtime', choices=('openjiuwen-java-0.1.15', 'openjiuwen-python-0.1.18'), action='append')
    args = parser.parse_args()
    root = args.output.resolve()
    root.mkdir(exist_ok=False)
    catalog = unified_catalog(PROJECT, args.harness)['templateModuleDirectory']
    jobs = Jobs(PROJECT, args.harness, maven=args.maven, data_dir=root / 'builds')
    collectors = []
    results = []
    try:
        for runtime in args.runtime or ('openjiuwen-java-0.1.15', 'openjiuwen-python-0.1.18'):
            group = [c for c in catalog['components'] if runtime in c['runtimeIds']
                     and c['id'] not in ('internal-transfer', 'channel-preferences')]
            request = selection(catalog, runtime, group)
            created = jobs.create(parse_selection(request).legacy(), 'release-qualification', selection=request)
            assert wait(jobs, created['id']) == 'BUILT', created['id']
            candidate = root / 'builds/workbench' / created['id'] / 'candidate'
            run = root / runtime
            run.mkdir()
            history(candidate, args.harness, run / 'history')
            snapshots(candidate, args.harness, run / 'snapshots')
            jars = list((candidate / 'target').glob('*.jar'))
            command = ['java', '-cp', str(jars[0])+':'+str(candidate / 'target/dependency/*'),
                       'io.github.bitlhk.finance.builder.local.CollectorQualification', str(candidate)]
            identities = json.loads(subprocess.check_output(command, text=True))
            # These evidence documents are produced by real controlled Host tests,
            # not counts invented from a successful compile.
            reports = sorted([p for p in run.rglob('*.json') if p.name in ('report.json', 'summary.json')])
            if not reports:
                raise RuntimeError('QUALIFICATION_REPORTS_MISSING')
            evidence = [{'file': str(p.relative_to(root)), 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()} for p in reports]
            for collector, identity in identities.items():
                collectors.append({'collectorId': collector, 'identity': identity,
                    'semantics': ['execution.history', 'capability.result'] if collector == 'local-host-archive' else ['model.client.snapshot'],
                    'passed': True, 'evidence': evidence})
            results.append({'runtime': runtime, 'candidate': str(candidate), 'status': 'PASS', 'evidence': evidence})
        (root / 'qualification.json').write_text(json.dumps({'schemaVersion': 'finance-observation-qualification.v0.1', 'collectors': collectors}, indent=2))
        (root / 'summary.json').write_text(json.dumps({'status': 'PASS', 'runtimes': results, 'realModel': False}, indent=2))
        print(json.dumps({'status': 'PASS', 'runtimes': len(results)}))
    finally:
        jobs.close()

if __name__ == '__main__':
    main()
