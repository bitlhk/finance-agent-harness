"""Re-run seven Java/Python compositions and compare business results, never model prose."""
import argparse
import hashlib
import json
from pathlib import Path

from compare_readonly_business import project
from verify_python_packages import verify


def run(matrix, output, harness):
    output.mkdir(parents=True, exist_ok=True)
    checks = []
    comparisons = []
    for combination in range(1, 8):
        for runtime in ('java', 'python'):
            root = matrix / f'{runtime}-{combination}'
            candidate = root / 'candidate'
            lock = candidate / 'service-lock.json'
            report = json.loads((root / 'build-report.json').read_text())
            assert report['status'] == 'BUILT', root
            assert report['lockSha256'] == hashlib.sha256(lock.read_bytes()).hexdigest(), root
            assert json.loads(lock.read_text())['runtime']['language'] == runtime, root
            result = verify(candidate, output / f'{runtime}-{combination}', harness)
            assert result and all(item['passed'] for item in result), root
            checks.append(dict(combination=combination, runtime=runtime, checks=len(result), passed=True))
            (output / 'matrix-checks.json').write_text(json.dumps(checks, indent=2))
        def load(runtime):
            return project(json.loads((output / f'{runtime}-{combination}' / 'business-results.json').read_text()))
        java, python = load('java'), load('python')
        assert java and java.keys() == python.keys(), combination
        for case in java:
            assert java[case] == python[case], (combination, case)
            comparisons.append(dict(combination=combination, case=case, passed=True))
        (output / 'business-comparisons.json').write_text(json.dumps(comparisons, indent=2))
    return {'artifacts': len(checks), 'businessComparisons': len(comparisons), 'passed': True}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--matrix', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--harness', type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(run(args.matrix, args.output, args.harness)))
