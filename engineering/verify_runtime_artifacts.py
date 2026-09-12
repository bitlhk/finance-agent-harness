"""Check pinned Java qualification artifacts; never selects floating latest."""
import argparse
import hashlib
import json
from pathlib import Path


def verify(repository):
    pins = json.loads(Path(__file__).with_name('runtime-qualification-artifacts.json').read_text())
    results = []
    for version, pin in pins['java'].items():
        jar = repository / 'com/openjiuwen/agent-core-java' / version / f'agent-core-java-{version}.jar'
        actual = hashlib.sha256(jar.read_bytes()).hexdigest()
        if actual != pin['sha256']:
            raise ValueError('RUNTIME_ARTIFACT_DIGEST_MISMATCH:' + version)
        results.append({'coordinate': pin['coordinate'], 'sha256': actual, 'status': 'MATCHED'})
    return {'java': results, 'python': 'DECLARATION_ONLY_NOT_EXECUTED'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--maven-repository', type=Path, default=Path.home()/'.m2/repository')
    print(json.dumps(verify(parser.parse_args().maven_repository), indent=2))
