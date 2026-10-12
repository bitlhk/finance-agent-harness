"""Child-only permissions for reproducible Maven artifacts."""

import os


def build_process_options(*, maven: bool = False) -> dict[str, int]:
    # JAR entries retain compiler output modes; never change the server's umask.
    return {"umask": 0o022} if maven and os.name == "posix" else {}
