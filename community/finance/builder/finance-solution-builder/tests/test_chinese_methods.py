import importlib.util
import json
from pathlib import Path

import pytest

PROJECT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('chinese_method_gate', PROJECT / 'engineering/verify_chinese_methods.py')
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


def test_constraint_gate_rejects_deleted_boundary_or_changed_machine_term():
    method = {'constraints': [['no inferred totals', '不得推算前期总额']], 'machineTerms': ['TREND_COMPARABILITY_NOT_VERIFIED']}
    old = 'no inferred totals TREND_COMPARABILITY_NOT_VERIFIED'
    new = '不得推算前期总额 TREND_COMPARABILITY_NOT_VERIFIED'
    gate.check_text(old, new, method)
    for broken in ('TREND_COMPARABILITY_NOT_VERIFIED', '不得推算前期总额'):
        with pytest.raises(AssertionError):
            gate.check_text(old, broken, method)


def test_host_methods_are_versioned_chinese_sources():
    root = PROJECT / 'templates/local-readonly-service/methods'
    catalog = json.loads((root / 'catalog.json').read_text())
    for method in catalog['methods']:
        assert method['version'] == '0.1.1'
        assert (root / method['file']).read_text().startswith(('# 账户余额核验', '# 财富事实查询方法'))
