import json

import pytest
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from adapters.guardrail_runtime.policy_crypto import encrypt_config, write_encrypted, PolicyCryptoError
from adapters.guardrail_runtime.policy_loader import PolicyLoader, PolicyLoadError


@pytest.fixture
def encrypted_policy(tmp_path, monkeypatch):
    policy = tmp_path / "policy"
    policy.mkdir()
    secret = tmp_path / "secrets" / "test.key"
    secret.parent.mkdir()
    key = AESGCM.generate_key(bit_length=256)
    secret.write_bytes(key)
    monkeypatch.setenv("GUARDRAIL_CONTENT_FILTER_KEY_FILE", str(secret))
    for name, data in {
        "rules.json": {"rules": [{"id": "r", "patterns": ["synthetic"]}]},
        "prompts.json": {"prompts": {"security_check": {"content": "test"}}},
    }.items():
        (policy / name).write_text(json.dumps(data), encoding="utf-8")
    content = {"categories": {"test": {"anchors": ["synthetic-private-anchor"]}}}
    target = policy / "content_filter_config.json"
    target.write_bytes(encrypt_config(json.dumps(content).encode(), key))
    return policy, target, secret, key, content


def test_real_loader_decrypts_and_reloads(encrypted_policy):
    policy, target, secret, key, content = encrypted_policy
    loader = PolicyLoader(policy)
    assert loader.get_content_filter_config() == content
    assert b"synthetic-private-anchor" not in target.read_bytes()
    updated = {"categories": {"updated": {"anchors": ["new-synthetic-anchor"]}}}
    source = secret.parent / "edited.json"
    source.write_text(json.dumps(updated), encoding="utf-8")
    write_encrypted(source, target, secret)
    loader.reload()
    assert loader.get_content_filter_config() == updated


@pytest.mark.parametrize("failure", ["missing_key", "wrong_key", "tampered", "plaintext", "removed"])
def test_failed_reload_retains_entire_previous_policy(encrypted_policy, failure):
    policy, target, secret, key, content = encrypted_policy
    loader = PolicyLoader(policy)
    (policy / "rules.json").write_text('{"rules": []}', encoding="utf-8")
    if failure == "missing_key":
        secret.unlink()
    elif failure == "wrong_key":
        secret.write_bytes(AESGCM.generate_key(bit_length=256))
    elif failure == "tampered":
        data = json.loads(target.read_bytes())
        data["ciphertext"] = ("A" if data["ciphertext"][0] != "A" else "B") + data["ciphertext"][1:]
        target.write_text(json.dumps(data), encoding="utf-8")
    elif failure == "plaintext":
        target.write_text(json.dumps(content), encoding="utf-8")
    else:
        target.unlink()
    with pytest.raises(PolicyLoadError):
        loader.reload()
    assert loader.get_content_filter_config() == content
    assert len(loader.get_rules()) == 1


def test_missing_key_prevents_initialization(encrypted_policy):
    policy, target, secret, key, content = encrypted_policy
    secret.unlink()
    with pytest.raises(PolicyLoadError):
        PolicyLoader(policy)


def test_repeated_in_place_encryption_does_not_destroy_policy(encrypted_policy):
    policy, target, secret, key, content = encrypted_policy
    previous = target.read_bytes()
    with pytest.raises(PolicyCryptoError):
        write_encrypted(target, target, secret)
    assert target.read_bytes() == previous
