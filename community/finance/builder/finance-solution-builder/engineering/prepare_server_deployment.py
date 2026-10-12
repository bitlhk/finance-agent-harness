"""Render offline deployment assets; never install, reload nginx or print secrets."""
import argparse
import os
from pathlib import Path
import re
import secrets

TEMPLATES = Path(__file__).resolve().parents[1] / "deploy/server"


def prepare(domain: str, output: Path):
    if not re.fullmatch(r"(?=.{1,253}$)[a-z0-9]+(?:[.-][a-z0-9]+)+", domain):
        raise ValueError("DNS_NAME_REQUIRED")
    # New directory only: never rotate a running proxy's credentials implicitly.
    output.mkdir(mode=0o700)
    token = secrets.token_hex(32)
    files = {
        "nginx.conf": (TEMPLATES / "nginx.conf.template").read_text().replace("@@DOMAIN@@", domain),
        "finance-studio.service": (TEMPLATES / "finance-studio.service.template").read_text().replace("@@DOMAIN@@", domain),
        "proxy-token": token + "\n",
        "proxy-header.conf": 'proxy_set_header X-Finance-Proxy-Token "' + token + '";\n',
    }
    for name, content in files.items():
        with os.fdopen(os.open(output / name, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as stream:
            stream.write(content)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--domain", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    prepare(args.domain, args.output)
    print("Deployment templates prepared. Not installed; TLS, login users and model/bank configuration are still required.")


if __name__ == "__main__": main()
