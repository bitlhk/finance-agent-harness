"""Dependency-free transport helpers shared by the workbench and exported UI gateway."""
import hashlib


class ApiError(Exception):
    def __init__(self, code, status=400):
        super().__init__(code)
        self.code, self.status = code, status


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def response_text(value):
    if isinstance(value, str):
        return value
    if isinstance(value, dict):
        for key in ("output", "answer", "content", "response", "result"):
            text = response_text(value.get(key))
            if text:
                return text
    return ""
