"""Compare business semantics, not runtime-generated identities or observation clocks."""
import argparse
import copy
import json
from pathlib import Path


def business(execution):
    data = execution['data']
    common = {k: data[k] for k in ('capabilityId', 'resultStatus', 'sourceSystem')}
    if data['resultStatus'] == 'FAILED':
        common.update({k: data[k] for k in ('sourceCode','failureClass','retryable','nextActions') if k in data})
        return common
    if data['capabilityId'] == 'account.balance.query':
        common['accounts'] = data['accounts']
        common['asOf'] = data['asOf']
    elif data['capabilityId'] == 'wealth.facts.describe':
        facts = copy.deepcopy(data['facts'])
        # Per-request capture time and hash include observation metadata, not valuation time.
        facts['source'].pop('observedAt', None)
        facts['source'].pop('payloadSha256', None)
        common.update(facts=facts, description=data['description'], missingInputs=data['missingInputs'])
        common['sourceReceipt'] = {k: data['sourceReceipt'][k] for k in ('status', 'sourceSystem', 'asOf')}
    else:
        facts = copy.deepcopy(data['facts'])
        # Preserve source/policy business time; only capture time varies by invocation.
        facts.get('source', {}).pop('observedAt', None)
        common.update(facts=facts, description=data['description'])
    return common


def project(record):
    return {case: [business(x) for x in value] if isinstance(value, list) else business(value)
            for case, value in record.items()}


def compare(root):
    checks = []
    for name in ('balance', 'wealth', 'combo'):
        java = project(json.loads((root/'java-checks'/name/'business-results.json').read_text()))
        python = project(json.loads((root/'release-checks'/name/'business-results.json').read_text()))
        assert java and java.keys() == python.keys(), name
        for case in java:
            assert java[case] == python[case], (name, case, java[case], python[case])
            checks.append({'combination': name, 'case': case, 'passed': True})
    return checks


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', type=Path, required=True)
    args = parser.parse_args()
    checks = compare(args.root)
    (args.root/'business-comparison.json').write_text(json.dumps(checks, indent=2))
    print(json.dumps({'businessComparisons': len(checks), 'passed': True}))
