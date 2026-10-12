"""Single-server proxy trust boundary; never grants access to bank customers."""
from __future__ import annotations

from contextlib import contextmanager
from dataclasses import dataclass
import hashlib
import hmac
import os
from pathlib import Path
import re
import stat
from urllib.parse import urlsplit


def private_file(path: Path) -> bytes:
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(fd, "rb") as stream:
        info = os.fstat(stream.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_mode & 0o077 or info.st_uid != os.geteuid():
            raise ValueError("PRIVATE_FILE_REQUIRED")
        value = stream.read(513).strip()
    if not re.fullmatch(rb"[a-f0-9]{64}", value):
        raise ValueError("SECRET_FORMAT_INVALID")
    return value


@dataclass(frozen=True)
class ServerAccess:
    origin: str
    authority: str
    proxy_token: bytes

    @classmethod
    def load(cls, origin: str, token_file: Path):
        parsed = urlsplit(origin)
        if (parsed.scheme != "https" or parsed.username or parsed.password or parsed.path
                or parsed.query or parsed.fragment or not parsed.hostname
                or not re.fullmatch(r"[a-z0-9][a-z0-9.-]*(?::[0-9]{1,5})?", parsed.netloc)
                or parsed.port is not None and not 1 <= parsed.port <= 65535):
            raise ValueError("HTTPS_PUBLIC_ORIGIN_REQUIRED")
        return cls(origin, parsed.netloc, private_file(token_file))

    def authenticate(self, headers, peer: str) -> str:
        # Duplicate headers are ambiguous across HTTP implementations, so reject them.
        for name in ("Host", "Origin", "X-Finance-Proxy-Token", "X-Finance-User"):
            if len(headers.get_all(name, [])) > 1:
                raise PermissionError("PROXY_AUTH_REQUIRED")
        if peer != "127.0.0.1" or headers.get("Host") != self.authority:
            raise PermissionError("PUBLIC_HOST_REQUIRED")
        if headers.get("Origin") not in (None, self.origin):
            raise PermissionError("ORIGIN_REJECTED")
        token = headers.get("X-Finance-Proxy-Token", "").encode()
        username = headers.get("X-Finance-User", "")
        if (not hmac.compare_digest(token, self.proxy_token)
                or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.@-]{0,63}", username)):
            raise PermissionError("PROXY_AUTH_REQUIRED")
        return username

    def owner(self, username: str, session_key: bytes) -> str:
        return hmac.new(session_key, ("server-owner-v1:" + self.origin + ":" + username).encode(),
                        hashlib.sha256).hexdigest()[:32]


@contextmanager
def exclusive_data_dir(root: Path):
    """One trusted Linux service owns the entire build/process registry."""
    import fcntl
    if not root.is_absolute() or root.is_symlink() or root.resolve() != root:
        raise ValueError("DATA_DIRECTORY_INVALID")
    root.mkdir(parents=True, exist_ok=True, mode=0o700)
    info = root.stat()
    if info.st_uid != os.geteuid() or info.st_mode & 0o077:
        raise ValueError("PRIVATE_DATA_DIRECTORY_REQUIRED")
    fd = os.open(root / ".workbench.lock", os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    try:
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise ValueError("DATA_DIRECTORY_IN_USE") from None
        yield
    finally:
        os.close(fd)
