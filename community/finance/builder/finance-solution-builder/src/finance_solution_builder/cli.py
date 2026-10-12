from __future__ import annotations

import argparse
import json
from pathlib import Path

from .assets import optional_archive_anchor
from .builder import SolutionBuilder


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(prog="finance-solution-builder")
    commands = root.add_subparsers(dest="command", required=True)
    build = commands.add_parser("build", help="assemble and verify a Finance Harness solution")
    build.add_argument("solution", type=Path)
    build.add_argument("--harness-repo", type=Path, required=True)
    build.add_argument("--harness-source")
    build.add_argument("--harness-revision")
    build.add_argument("--harness-tree-digest")
    build.add_argument("--output", type=Path, required=True)
    build.add_argument("--maven", default="mvn")
    build.add_argument("--git", default="git")
    build.add_argument("--node", default="node")
    build.add_argument("--pnpm", default="pnpm")
    build.add_argument("--allow-template-fallback", action="store_true")
    build.add_argument("--no-verify", action="store_true")
    return root


def main() -> int:
    args = parser().parse_args()
    project_root = Path(__file__).resolve().parents[2]
    try:
        archive_anchor = optional_archive_anchor(
            source=args.harness_source,
            revision=args.harness_revision,
            tree_digest=args.harness_tree_digest,
        )
    except ValueError as exc:
        print(json.dumps({"status": "rejected", "reason": str(exc)}, ensure_ascii=False, indent=2))
        return 2
    builder = SolutionBuilder(
        project_root,
        args.harness_repo,
        maven=args.maven,
        git=args.git,
        node=args.node,
        pnpm=args.pnpm,
        allow_template_fallback=args.allow_template_fallback,
        harness_archive_anchor=archive_anchor,
    )
    try:
        result = builder.build(args.solution, args.output, verify=not args.no_verify)
    except (ValueError, RuntimeError) as exc:
        print(
            json.dumps(
                {
                    "status": "rejected",
                    "reason": str(exc),
                },
                ensure_ascii=False,
                indent=2,
            )
        )
        return 2
    print(
        json.dumps(
            {
                "status": result.status,
                "candidate": str(result.candidate_dir),
                "lock": str(result.lock_path),
                "report": str(result.report_path),
                "warnings": result.warnings,
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    return 0 if result.status != "failed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
