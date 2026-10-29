"""Authenticated policy encryption; keys live outside the project."""
from __future__ import annotations

import argparse
import base64
import json
import os
from pathlib import Path
import tempfile

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

FORMAT = "guardrail-content-filter-aes256gcm-v1"
AAD = FORMAT.encode("ascii")


class PolicyCryptoError(ValueError):
    """Safe error message: never includes plaintext or key material."""


def key_path() -> Path:
    configured = os.environ.get("GUARDRAIL_CONTENT_FILTER_KEY_FILE")
    return Path(configured).expanduser() if configured else (
        Path.home() / ".config" / "guardrail-jiuwen" / "content-filter.key"
    )


def read_key(path: Path | None = None) -> bytes:
    try:
        key = (path or key_path()).read_bytes()
        if len(key) != 32:
            raise ValueError()
        return key
    except (OSError, ValueError):
        raise PolicyCryptoError("Content-filter key unavailable or invalid (expected 32 bytes)") from None


def encrypt_config(plaintext: bytes, key: bytes) -> bytes:
    try:
        data = json.loads(plaintext)
        if not isinstance(data, dict) or data.get("format") == FORMAT or len(key) != 32:
            raise ValueError()
    except (ValueError, UnicodeError):
        raise PolicyCryptoError("Expected a JSON object and a 32-byte key") from None
    nonce = os.urandom(12)
    ciphertext = AESGCM(key).encrypt(nonce, plaintext, AAD)
    return (json.dumps({
        "format": FORMAT,
        "nonce": base64.b64encode(nonce).decode("ascii"),
        "ciphertext": base64.b64encode(ciphertext).decode("ascii"),
    }, indent=2) + "\n").encode("ascii")


def decrypt_config(envelope: bytes, key: bytes) -> bytes:
    try:
        data = json.loads(envelope)
        if not isinstance(data, dict) or data.get("format") != FORMAT or len(key) != 32:
            raise ValueError()
        nonce = base64.b64decode(data["nonce"], validate=True)
        ciphertext = base64.b64decode(data["ciphertext"], validate=True)
        if len(nonce) != 12:
            raise ValueError()
        plaintext = AESGCM(key).decrypt(nonce, ciphertext, AAD)
        if not isinstance(json.loads(plaintext), dict):
            raise ValueError()
        return plaintext
    except (ValueError, TypeError, KeyError, UnicodeError, InvalidTag):
        raise PolicyCryptoError("Encrypted content-filter configuration is invalid or authentication failed") from None


def write_encrypted(source: Path, target: Path, key_file: Path | None = None) -> None:
    """Validate round-trip before atomically replacing target. No plaintext temp files."""
    key = read_key(key_file)
    plaintext = source.read_bytes()
    envelope = encrypt_config(plaintext, key)
    if decrypt_config(envelope, key) != plaintext:
        raise PolicyCryptoError("Encryption verification failed")
    fd, name = tempfile.mkstemp(prefix=".encrypted-", dir=target.parent)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(envelope)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(name, target)
    finally:
        Path(name).unlink(missing_ok=True)


def main() -> None:
    parser = argparse.ArgumentParser(description="Encrypt a JSON policy using an existing external key")
    parser.add_argument("source", type=Path)
    parser.add_argument("target", type=Path)
    parser.add_argument("--key-file", type=Path)
    args = parser.parse_args()
    try:
        write_encrypted(args.source, args.target, args.key_file)
    except (OSError, PolicyCryptoError):
        parser.exit(1, "Encryption failed; check source JSON, destination, and external key file.\n")
    print("Encrypted configuration written and verified.")


if __name__ == "__main__":
    main()
