from __future__ import annotations

import re
from pathlib import Path
from urllib.parse import urlsplit

BUILDER_ROOT = Path(__file__).resolve().parents[3]
REPOSITORY_ROOT = BUILDER_ROOT.parents[2]
LINK = re.compile(r"\[[^]]+\]\(([^)]+)\)")
SUBMODULE_PATH = re.compile(r"^\s*path\s*=\s*(.+?)\s*$", re.MULTILINE)

DOCUMENTS = (
    BUILDER_ROOT / "README.md",
    BUILDER_ROOT / "AGENTS.md",
    BUILDER_ROOT / "base" / "README.md",
    BUILDER_ROOT / "base" / "docs" / "architecture" / "L2-architecture.md",
    BUILDER_ROOT / "base" / "docs" / "architecture" / "runtime" / "L1-architecture.md",
    BUILDER_ROOT / "base" / "docs" / "architecture" / "contracts" / "agent-behavior.md",
    BUILDER_ROOT / "base" / "docs" / "architecture" / "contracts" / "builder-session.md",
    BUILDER_ROOT
    / "base"
    / "docs"
    / "architecture"
    / "contracts"
    / "solution-build-protocol.md",
    BUILDER_ROOT / "base" / "docs" / "architecture" / "agent-l1-template.md",
    BUILDER_ROOT / "base" / "docs" / "knowledge" / "README.md",
    BUILDER_ROOT / "base" / "docs" / "knowledge" / "base-extension-guide.md",
    BUILDER_ROOT / "intent-agent-builder" / "README.md",
    BUILDER_ROOT / "intent-agent-builder" / "docs" / "architecture" / "runtime" / "L1-architecture.md",
    BUILDER_ROOT
    / "intent-agent-builder"
    / "docs"
    / "architecture"
    / "contracts"
    / "agent-behavior.md",
    BUILDER_ROOT
    / "intent-agent-builder"
    / "docs"
    / "architecture"
    / "contracts"
    / "builder-session.md",
    BUILDER_ROOT / "scheduling-agent-builder" / "README.md",
)


def _local_target(document: Path, raw_target: str) -> Path | None:
    target = raw_target.split("#", 1)[0].strip()
    if not target or urlsplit(target).scheme in {"http", "https", "mailto"}:
        return None
    return (document.parent / target).resolve()


def _registered_submodule_roots() -> tuple[Path, ...]:
    config = REPOSITORY_ROOT / ".gitmodules"
    if not config.is_file():
        return ()
    return tuple(
        (REPOSITORY_ROOT / match).resolve()
        for match in SUBMODULE_PATH.findall(config.read_text(encoding="utf-8"))
    )


def _belongs_to_unavailable_submodule(target: Path) -> bool:
    for root in _registered_submodule_roots():
        if target != root and not target.is_relative_to(root):
            continue
        if not root.exists() or not any(root.iterdir()):
            return True
    return False


def test_public_builder_document_links_resolve() -> None:
    missing: list[str] = []
    for document in DOCUMENTS:
        assert document.is_file(), document
        for raw_target in LINK.findall(document.read_text(encoding="utf-8")):
            target = _local_target(document, raw_target)
            if (
                target is not None
                and not target.exists()
                and not _belongs_to_unavailable_submodule(target)
            ):
                missing.append(f"{document.relative_to(BUILDER_ROOT)} -> {raw_target}")
    assert not missing, "missing links:\n" + "\n".join(missing)


def test_ai_coding_entry_routes_to_authoritative_knowledge() -> None:
    agents = (BUILDER_ROOT / "AGENTS.md").read_text(encoding="utf-8")
    guide = (
        BUILDER_ROOT / "base" / "docs" / "knowledge" / "base-extension-guide.md"
    ).read_text(encoding="utf-8")
    for required in (
        "base/docs/architecture/L2-architecture.md",
        "base/docs/knowledge/base-extension-guide.md",
        "Base 不得导入 Intent、Scheduling",
        "DataFactory",
        "Evaluation",
    ):
        assert required in agents
    for required in ("判断能力放在哪里", "扩展步骤", "正反例", "AI Coding 自检"):
        assert required in guide


def test_common_docs_do_not_fix_intent_agent_inventory() -> None:
    common = "\n".join(
        path.read_text(encoding="utf-8")
        for path in DOCUMENTS
        if "intent-agent-builder" not in path.parts
    )
    assert "五个 Agent 各自" not in common
    assert "四个专业 Agent" not in common
