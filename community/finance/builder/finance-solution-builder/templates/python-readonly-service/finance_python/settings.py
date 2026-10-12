"""Frozen model selection; credentials remain in the host environment."""
import hashlib
import json
import os
import re
from datetime import date
from urllib.parse import urlsplit
from zoneinfo import ZoneInfo


def trusted_business_time():
    """Read an all-or-nothing Host clock context; it never grants data access."""
    names = ('FH_BUSINESS_DATE', 'FH_BUSINESS_TIME_ZONE', 'FH_BUSINESS_DATE_SOURCE')
    values = [os.environ.get(name, '').strip() for name in names]
    if not any(values):
        return None
    if not all(values):
        raise ValueError('TRUSTED_BUSINESS_TIME_INCOMPLETE')
    try:
        business_date = date.fromisoformat(values[0])
        zone = ZoneInfo(values[1])
    except (ValueError, KeyError):
        raise ValueError('TRUSTED_BUSINESS_TIME_INVALID') from None
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}', values[2]):
        raise ValueError('TRUSTED_BUSINESS_TIME_INVALID')
    return dict(businessDate=business_date.isoformat(), timeZone=zone.key, source=values[2])


def business_time_instruction(value):
    if value is None:
        return ('宿主未提供可信业务日期。涉及上个月等相对日期，或只有月份而没有可信年份时，'
                '必须先澄清，不得自行使用系统日期或模型知识补齐。')
    return (f"宿主提供的可信业务日期为 {value['businessDate']}，时区为 {value['timeZone']}，"
            f"来源为 {value['source']}。相对日期只能依据该上下文解析；"
            '仅给出月份但没有年份时应澄清，不得用业务日期补年份。')


def model_connection(root, manifest, ref=None):
    selection = manifest.get('modelSelection')
    if selection is None:
        if ref not in (None, 'configured-model'):
            raise ValueError('MODEL_NOT_SELECTED')
        value = dict(ref='configured-model', version='LEGACY_HOST_CONFIGURATION',
                     name=os.environ.get('FH_MODEL_NAME', ''), url=os.environ.get('FH_MODEL_URL', ''),
                     key=os.environ.get('FH_MODEL_KEY', ''))
    else:
        if json.loads((root / 'model-selection.json').read_text()) != selection:
            raise ValueError('MODEL_SELECTION_LOCK_MISMATCH')
        ref = ref or selection['defaultModelRef']
        if ref not in selection['selectedModelRefs']:
            raise ValueError('MODEL_NOT_SELECTED')
        frozen = [v for v in selection['connections'] if v['modelRef'] == ref]
        registry = json.loads(open(os.environ['FH_MODEL_REGISTRY']).read())
        bindings = [v for v in registry['bindings'] if v['modelRef'] == ref]
        if registry['schemaVersion'] != 'finance-model-bindings.v1' or len(bindings) != 1 or len(frozen) != 1:
            raise ValueError('MODEL_CONNECTION_UNAVAILABLE')
        binding, frozen = bindings[0], frozen[0]
        if (binding.get('enabled') is not True or binding['protocol'] != 'OPENAI_CHAT_COMPLETIONS'
                or binding['provider'] not in ('OpenAI', 'OpenRouter')):
            raise ValueError('MODEL_CONNECTION_UNAVAILABLE')
        for field in ('urlEnvironment', 'keyEnvironment'):
            if not re.fullmatch(r'FH_CONN_[A-Z0-9_]{1,100}', binding[field]):
                raise ValueError('MODEL_CONNECTION_UNAVAILABLE')
        url = os.environ[binding['urlEnvironment']]
        fields = ('modelRef', 'revision', 'protocol', 'provider', 'modelName', 'urlEnvironment', 'keyEnvironment')
        digest = hashlib.sha256()
        for text in [*(binding[field] for field in fields), url]:
            data = text.encode()
            digest.update(str(len(data)).encode() + b':' + data)
        version = 'sha256:' + digest.hexdigest()
        if (version != frozen['configVersion'] or binding['modelName'] != frozen['modelName']
                or binding['provider'] != frozen['provider']):
            raise ValueError('MODEL_CONFIGURATION_CHANGED')
        value = dict(ref=ref, version=version, name=binding['modelName'], url=url,
                     key=os.environ[binding['keyEnvironment']])
    url = urlsplit(value['url'])
    if (not value['name'] or not value['key'] or not url.hostname or url.username or url.query or url.fragment
            or not (url.scheme == 'https' or url.scheme == 'http' and url.hostname in ('localhost', '127.0.0.1'))):
        raise ValueError('MODEL_CONNECTION_UNAVAILABLE')
    return value
