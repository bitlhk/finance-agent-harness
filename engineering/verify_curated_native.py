"""Owned MCP + native Runtime tests; optional packaged AgentScope QUERY qualification, not Builder admission."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
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


def qualify_agentscope(root, args, config, facts, rows):
    output = args.output
    lib, classes = output / 'lib', output / 'host-classes'
    lib.mkdir(); classes.mkdir()
    command = ['mvn', '-o', '-B', f'-Dmaven.repo.local={args.maven_repo}', '-pl', 'integrations/agentscope-java',
               'dependency:copy-dependencies', '-DincludeScope=test',
               '-DexcludeGroupIds=org.junit.jupiter,org.junit.platform,org.opentest4j,org.apiguardian',
               '-DexcludeArtifactIds=slf4j-simple', f'-DoutputDirectory={lib}']
    with (output / 'qualification-build.log').open('w') as log:
        subprocess.run(command, cwd=root, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=180)
        shutil.copy2(root / 'integrations/agentscope-java/target/finance-harness-agentscope-java-0.1.0-SNAPSHOT.jar', lib)
        shutil.copytree(root / 'integrations/agentscope-java/target/test-classes', output / 'test-classes')
        classpath = f'{lib}/*:{output / "test-classes"}:{classes}'
        sources = sorted((root / 'integrations/agentscope-java/isolatedHost').glob('*.java'))
        subprocess.run(['javac', '--release', '21', '-cp', classpath, '-d', str(classes), *map(str, sources)],
                       cwd=root, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=90)
        qualification = output / 'qualification'
        prefix = ['java', '-cp', classpath]
        package = 'io.github.bitlhk.finance.harness.integrations.agentscope.isolatedhost.'
        subprocess.run(prefix + [package + 'QualificationWorker', str(root), str(output / 'raw/agentscope'),
                                str(qualification), '--curated', str(config), sha(config)],
                       cwd=root, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=120)
        mismatched = output / 'mismatched-source-evidence'
        shutil.copytree(output / 'raw/agentscope', mismatched)
        changed = next(mismatched.glob('*.json'))
        row = json.loads(changed.read_text())
        row['sourceConfigDigest'] = '0' * 64
        write(changed, row)
        refused = subprocess.run(prefix + [package + 'QualificationWorker', str(root), str(mismatched),
                                 str(output / 'rejected-qualification'), '--curated', str(config), sha(config)],
                                 cwd=root, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=120)
        (output / 'qualification-negative.log').write_text(refused.stdout)
        if refused.returncode == 0 or 'CURATED_EVIDENCE_SOURCE_MISMATCH' not in refused.stdout:
            raise RuntimeError('QUALIFICATION_SOURCE_MISMATCH_NOT_REJECTED')
        if (output / 'rejected-qualification/agentscope-observation-qualification.json').exists():
            raise RuntimeError('REJECTED_QUALIFICATION_PUBLISHED')
        digest = (qualification / 'qualification.sha256').read_text().strip()
        tested = [r for r in rows if r.get('testMethod') == 'selectedPackUsesActualMcpAndOnlyInstallsItsTools']
        if len(tested) != 7:
            raise RuntimeError('QUALIFIED_PACK_CONTROLS_MISSING')
        for index, row in enumerate(tested):
            controls = row['sessions'][0]['controls']
            subprocess.run(prefix + [package + 'CuratedQualifiedHost', str(root), controls['solution'], controls['support'],
                                    str(config), sha(config), str(qualification / 'agentscope-observation-qualification.json'),
                                    digest, str(facts), str(output / f'qualified-host-{index}')],
                           cwd=root, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=120)
        return {'status': 'PASS', 'qualificationSha256': digest, 'separateJvmPackChecks': len(tested),
                'mismatchedSourceEvidence': 'REJECTED_WITHOUT_QUALIFICATION',
                'scope': 'PACKAGED_QUERY_QUALIFICATION_NOT_BUILDER_HOST_ADMISSION'}


def verify(args, *, source_transport=None, after_native=None):
    """Trusted engineering composition hooks, never selected by uploaded assets."""
    root = Path(__file__).resolve().parents[1]
    if not args.output.is_absolute() or args.output.exists():
        raise ValueError('use a new absolute output directory')
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
    packs = ['customer', 'wealth', 'insurance', 'research', 'audit', 'transfer-risk', 'post-loan']
    names = set()
    for pack in packs:
        data = json.loads((root / f'capability-packs/curated-synthetic-{pack}/pack.yaml').read_text())
        names.update(c['toolName'].removeprefix('curated_') for c in data['capabilities'])
    roles_file = args.eval_repo / 'integrations/openjiuwen/role-fixtures.json'
    roles = json.loads(roles_file.read_text())
    # Independent in-process reads of the pinned synthetic source, not Provider/HTTP responses.
    capture = '''import json
from integrations.openjiuwen import reference_mcp as source
def call(name, **args):
    tool = getattr(source, name)
    return (tool.fn if hasattr(tool, 'fn') else tool)(**args)
print(json.dumps({
    "defaults": {"get_customer_portfolio": call("get_customer_portfolio", customer_id="C005"),
        "get_wealth_policy_basis": call("get_wealth_policy_basis"),
        "prepare_wealth_allocation_context": call("prepare_wealth_allocation_context", customer_id="C005"),
        "wealth_assistant_product_info": call("wealth_assistant_product_info", product_id="P009")},
    "customers": {id: {name: call(name, customer_id=id) for name in
        ("get_customer_profile", "get_customer_portfolio", "prepare_wealth_allocation_context")}
        for id in ("C005", "C006")}}, ensure_ascii=False))'''
    wealth = json.loads(subprocess.check_output([str(args.source_python), '-c', capture], cwd=args.eval_repo, timeout=30))
    expected, resources = {'_customerVariants': wealth['customers']}, {}
    for name in sorted(names):
        if name == 'get_customer_profile':
            resources[name] = ['C005', 'C006', 'I001']
            expected[name] = {'source': 'finance-eval-synthetic-fixture', 'snapshotVersion': 'roles-demo-v1',
                              'asOf': '2026-09-05', 'synthetic': True, 'profile': roles['insuranceCustomer']}
        elif name in wealth['defaults']:
            expected[name] = wealth['defaults'][name]
            resources[name] = (['policy'] if name == 'get_wealth_policy_basis' else
                               ['P009', 'P001', 'F000343'] if name == 'wealth_assistant_product_info' else ['C005', 'C006'])
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
            endpoint = source_transport(base) if source_transport else base + '/mcp'
            listed = rpc(endpoint, {'jsonrpc': '2.0', 'id': 1, 'method': 'tools/list', 'params': {}})
            pins = {t['name']: hashlib.sha256(json.dumps(t['inputSchema'], ensure_ascii=False,
                    sort_keys=True, separators=(',', ':')).encode()).hexdigest()
                    for t in listed['result']['tools'] if t['name'] in names}
            if set(pins) != names:
                raise RuntimeError('SOURCE_TOOLS_MISSING')
            config = args.output / 'source-config.json'
            write(config, {'schemaVersion': 'curated-synthetic-source.v3', 'endpoint': endpoint,
                'contractVersion': '1.0.0', 'toolSchemaPins': pins, 'subjects': {'curated-subject': {
                    'customerSelection': 'SYNTHETIC_ALLOWLIST', 'resources': resources}},
                'runtimePolicy': {'mode': 'STATELESS_BOUNDED', 'sourceIdentitySha256': sha(identity)}})
            command = ['mvn', '-o', '-B', f'-Dmaven.repo.local={args.maven_repo}', '-pl', 'integrations/agentscope-java,integrations/openjiuwen-java',
                '-am', '-Dtest=CuratedNativeQueryTest', '-Dsurefire.failIfNoSpecifiedTests=false',
                f'-Dcurated.sourceConfig={config}', f'-Dcurated.sourceConfigSha256={sha(config)}',
                f'-Dcurated.expectedFacts={facts}', f'-Dcurated.evidenceDirectory={args.output / "raw"}',
                'install' if args.qualify_agentscope else 'test']
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
            rows = []
            for runtime, count in counts.items():
                runtime_rows = [json.loads(p.read_text()) for p in sorted((args.output / 'raw' / runtime).glob('*.json'))]
                if len(runtime_rows) != count:
                    raise RuntimeError('RUNTIME_RAW_EVIDENCE_INCOMPLETE:' + runtime)
                rows.extend(runtime_rows)
            if not rows or len(rows) != sum(counts.values()) or any(r['status'] != 'PASS' for r in rows):
                raise RuntimeError('RAW_EVIDENCE_INCOMPLETE')
            qualification = qualify_agentscope(root, args, config, facts, rows) if args.qualify_agentscope else {'status': 'NOT_RUN'}
            write(args.output / 'report.json', {'status': 'PASS', 'tests': len(rows), 'runtimeTests': counts, 'packs': packs,
                'sourceTools': sorted(names), 'sourceIdentitySha256': sha(identity), 'sourceFixtureSha256': sha(roles_file),
                'sourceConfigSha256': sha(config), 'sourceFactsCompared': 'ALL_FIELDS_EACH_SELECTED_TOOL',
                'scope': 'NATIVE_CURATED_COVERAGE_NOT_QUALIFICATION', 'realModel': 'NOT_RUN',
                'skillAcceptance': 'NOT_RUN', 'productionChanged': False, 'packagedAgentScopeQualification': qualification})
            if after_native:
                after_native(args.output)
        finally:
            source.terminate()
            try:
                source.wait(timeout=10)
            except subprocess.TimeoutExpired:
                source.kill()
                source.wait(timeout=5)
            write(args.output / 'cleanup.json', {'sourcePid': source.pid, 'reaped': source.poll() is not None})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--eval-repo', type=Path, required=True)
    parser.add_argument('--source-python', type=Path, required=True)
    parser.add_argument('--maven-repo', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--qualify-agentscope', action='store_true')
    verify(parser.parse_args())


if __name__ == '__main__':
    main()
