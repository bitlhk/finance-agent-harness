"""Verify new all-unit candidates against synthetic HTTP model/source endpoints.

The native Runtime executes tools. The model is controlled: this proves installed
content and execution boundaries, not professional quality or natural-language routing.
"""
import argparse
import hashlib
import json
from pathlib import Path

import verify_python_packages as verifier


def verify(candidate, output, harness):
    original = verifier.Endpoints
    captures = []

    class CapturingEndpoints(original):
        def __init__(self):
            super().__init__()
            captures.append(self)

    verifier.Endpoints = CapturingEndpoints
    try:
        checks = verifier.verify(candidate, output, harness)
    finally:
        verifier.Endpoints = original
    requests = captures[-1].models
    # Only this verifier's synthetic endpoint contents are persisted, never live data.
    (output / 'synthetic-model-requests.json').write_text(json.dumps(requests, ensure_ascii=False, indent=2))
    contents = [' '.join(str(message.get('content', '')).split())
                for request in requests for message in request.get('messages', [])]
    installed = []
    paths = ['methods/account-balance.md', 'methods/wealth-facts.md',
             'assets/domain-packs/wealth/shared-facts/skills/wealth-facts-description/SKILL.md',
             'assets/domain-packs/cashflow/customer-self-service/skills/cashflow-review/SKILL.md']
    for relative in paths:
        path = candidate / relative
        body = path.read_text()
        if body.startswith('---\n'):
            body = body.split('---', 2)[2].strip()
        assert any(' '.join(body.split()) in content for content in contents), ('METHOD_NOT_IN_MODEL_REQUEST', relative)
        installed.append({'file': relative, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()})
    result = {'checks': len(checks), 'actualMethodBodiesObserved': installed,
              'lockSha256': hashlib.sha256((candidate / 'service-lock.json').read_bytes()).hexdigest(),
              'mode': 'CONTROLLED_HTTP_NATIVE_RUNTIME', 'professionalModelQuality': 'NOT_RUN'}
    (output / 'method-installation.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--candidate', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--harness', type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(verify(args.candidate.resolve(), args.output.resolve(), args.harness.resolve())))
