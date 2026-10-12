import importlib.util
from pathlib import Path

import pytest


PATH = (Path(__file__).resolve().parents[1] / 'templates/python-readonly-service/finance_python/settings.py')
SPEC = importlib.util.spec_from_file_location('finance_python_settings_under_test', PATH)
SETTINGS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SETTINGS)


def test_trusted_business_time_is_all_or_nothing(monkeypatch):
    for name in ('FH_BUSINESS_DATE', 'FH_BUSINESS_TIME_ZONE', 'FH_BUSINESS_DATE_SOURCE'):
        monkeypatch.delenv(name, raising=False)
    assert SETTINGS.trusted_business_time() is None
    monkeypatch.setenv('FH_BUSINESS_DATE', '2026-09-15')
    with pytest.raises(ValueError, match='TRUSTED_BUSINESS_TIME_INCOMPLETE'):
        SETTINGS.trusted_business_time()


def test_trusted_business_time_retains_host_source(monkeypatch):
    monkeypatch.setenv('FH_BUSINESS_DATE', '2026-09-15')
    monkeypatch.setenv('FH_BUSINESS_TIME_ZONE', 'Asia/Shanghai')
    monkeypatch.setenv('FH_BUSINESS_DATE_SOURCE', 'host-business-clock')
    value = SETTINGS.trusted_business_time()
    assert value == dict(businessDate='2026-09-15', timeZone='Asia/Shanghai', source='host-business-clock')
    assert '2026-09-15' in SETTINGS.business_time_instruction(value)
    assert 'Asia/Shanghai' in SETTINGS.business_time_instruction(value)


def test_missing_context_requires_clarification(monkeypatch):
    for name in ('FH_BUSINESS_DATE', 'FH_BUSINESS_TIME_ZONE', 'FH_BUSINESS_DATE_SOURCE'):
        monkeypatch.delenv(name, raising=False)
    assert '必须先澄清' in SETTINGS.business_time_instruction(SETTINGS.trusted_business_time())
