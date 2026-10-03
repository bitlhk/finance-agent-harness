"""Owned bounded MCP + two native Java Runtime tests; never emits admission proof."""
import argparse
import hashlib
import json
from pathlib import Path
import socket
import subprocess
import time
import urllib.request
import xml.etree.ElementTree as ET


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')


def rpc(url, payload):
    request = urllib.request.Request(url, json.dumps(payload).encode(), headers={
        'Content-Type': 'application/json', 'Accept': 'application/json, text/event-stream',
        'MCP-Protocol-Version': '2025-06-18'})
    with urllib.request.urlopen(request, timeout=10) as response:
        body = response.read(262145)
        if len(body) > 262144:
            raise RuntimeError('DISCOVERY_TOO_LARGE')
        return json.loads(body)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--eval-repo', type=Path, required=True)
    parser.add_argument('--source-python', type=Path, required=True)
    parser.add_argument('--maven-repo', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    if not args.output.is_absolute() or args.output.exists():
        parser.error('use a new absolute output directory')
    args.output.mkdir(mode=0o700, parents=True)
    write(args.output / 'source-versions.json', {
        'harnessCommit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip(),
        'harnessDirty': bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=root)),
        'evalCommit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=args.eval_repo, text=True).strip(),
        'evalDirty': bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=args.eval_repo)),
    })
    identity = args.output / 'source-identity.json'
    subprocess.run([str(args.source_python), '-m', 'integrations.openjiuwen.test_bounded_reference_mcp',
                    '--identity', str(identity)], cwd=args.eval_repo, check=True, timeout=30)
    contract = json.loads((root / 'providers/curated-synthetic-mcp/reference-contract.v1.json').read_text())
    for name, expected in contract['files'].items():
        if sha(args.eval_repo / 'integrations/openjiuwen' / name) != expected:
            raise RuntimeError('SOURCE_CONTRACT_MISMATCH:' + name)
    packs = ['customer', 'insurance', 'research', 'audit', 'transfer-risk', 'post-loan']
    names = set()
    for pack in packs:
        data = json.loads((root / f'capability-packs/curated-synthetic-{pack}/pack.yaml').read_text())
        names.update(c['toolName'].removeprefix('curated_') for c in data['capabilities'])
    roles_file = args.eval_repo / 'integrations/openjiuwen/role-fixtures.json'
    roles = json.loads(roles_file.read_text())
    expected, resources = {}, {}
    for name in sorted(names):
        if name == 'get_customer_profile':
            resources[name] = ['I001']
            expected[name] = {'source': 'finance-eval-synthetic-fixture', 'snapshotVersion': 'roles-demo-v1',
                              'asOf': '2026-09-05', 'synthetic': True, 'profile': roles['insuranceCustomer']}
        else:
            spec = roles['tools'][name]
            resources[name] = list(spec['records'])
            ref = spec['defaultRef']
            expected[name] = {'source': 'finance-eval-role-fixtures', 'snapshotVersion': 'roles-demo-v1',
                              'asOf': '2026-09-05', 'synthetic': True, 'reference': ref, **spec['records'][ref]}
    facts = args.output / 'expected-facts.json'
    write(facts, expected)
    with socket.socket() as reservation:
        reservation.bind(('127.0.0.1', 0))
        port = reservation.getsockname()[1]
    base = f'http://127.0.0.1:{port}'
    with (args.output / 'source.log').open('w') as log:
        source = subprocess.Popen([str(args.source_python), '-m', 'integrations.openjiuwen.bounded_reference_mcp',
            '--port', str(port), '--identity', str(identity), '--identity-sha256', sha(identity)],
            cwd=args.eval_repo, stdout=log, stderr=subprocess.STDOUT)
        try:
            for _ in range(150):
                if source.poll() is not None:
                    raise RuntimeError('SOURCE_START_FAILED')
                try:
                    with socket.create_connection(('127.0.0.1', port), timeout=.1):
                        break
                except OSError:
                    time.sleep(.1)
            else:
                raise RuntimeError('SOURCE_START_TIMEOUT')
            listed = rpc(base + '/mcp', {'jsonrpc': '2.0', 'id': 1, 'method': 'tools/list', 'params': {}})
            pins = {t['name']: hashlib.sha256(json.dumps(t['inputSchema'], ensure_ascii=False,
                    sort_keys=True, separators=(',', ':')).encode()).hexdigest()
                    for t in listed['result']['tools'] if t['name'] in names}
            if set(pins) != names:
                raise RuntimeError('SOURCE_TOOLS_MISSING')
            config = args.output / 'source-config.json'
            write(config, {'schemaVersion': 'curated-synthetic-source.v2', 'endpoint': base + '/mcp',
                'contractVersion': '1.0.0', 'toolSchemaPins': pins, 'subjects': {'curated-subject': {
                    'customerId': 'I001', 'resources': resources}},
                'runtimePolicy': {'mode': 'STATELESS_BOUNDED', 'sourceIdentitySha256': sha(identity)}})
            command = ['mvn', '-o', '-B', f'-Dmaven.repo.local={args.maven_repo}', '-pl', 'integrations/agentscope-java,integrations/openjiuwen-java',
                '-am', '-Dtest=CuratedNativeQueryTest', '-Dsurefire.failIfNoSpecifiedTests=false',
                f'-Dcurated.sourceConfig={config}', f'-Dcurated.sourceConfigSha256={sha(config)}',
                f'-Dcurated.expectedFacts={facts}', f'-Dcurated.evidenceDirectory={args.output / "raw"}', 'test']
            write(args.output / 'command.json', command)
            with (args.output / 'maven.log').open('w') as output:
                result = subprocess.run(command, cwd=root, stdout=output, stderr=subprocess.STDOUT, timeout=900)
            if result.returncode:
                raise RuntimeError('NATIVE_TEST_FAILED: see maven.log and raw evidence')
            counts = {}
            for runtime in ('agentscope', 'openjiuwen'):
                report = root / f'integrations/{runtime}-java/target/surefire-reports/TEST-io.github.bitlhk.finance.harness.integrations.{runtime}.CuratedNativeQueryTest.xml'
                tree = ET.parse(report).getroot()
                if any(int(tree.attrib[k]) for k in ('failures', 'errors', 'skipped')):
                    raise RuntimeError('TESTS_NOT_ALL_EXECUTED:' + runtime)
                counts[runtime] = int(tree.attrib['tests'])
                (args.output / f'{runtime}-tests.xml').write_bytes(report.read_bytes())
            rows = [json.loads(p.read_text()) for p in sorted((args.output / 'raw').glob('*.json'))]
            if not rows or len(rows) != sum(counts.values()) or any(r['status'] != 'PASS' for r in rows):
                raise RuntimeError('RAW_EVIDENCE_INCOMPLETE')
            write(args.output / 'report.json', {'status': 'PASS', 'tests': len(rows), 'runtimeTests': counts, 'packs': packs,
                'sourceTools': sorted(names), 'sourceIdentitySha256': sha(identity), 'sourceFixtureSha256': sha(roles_file),
                'sourceConfigSha256': sha(config), 'sourceFactsCompared': 'ALL_FIELDS_EACH_SELECTED_TOOL',
                'scope': 'NATIVE_CURATED_COVERAGE_NOT_QUALIFICATION', 'realModel': 'NOT_RUN',
                'skillAcceptance': 'NOT_RUN', 'productionChanged': False})
        finally:
            source.terminate()
            try:
                source.wait(timeout=10)
            except subprocess.TimeoutExpired:
                source.kill()
                source.wait(timeout=5)
            write(args.output / 'cleanup.json', {'sourcePid': source.pid, 'reaped': source.poll() is not None})


if __name__ == '__main__':
    main()
