"""Read-only, version-pinned asset composition. No caller-supplied filesystem paths."""
from __future__ import annotations

import base64
import io
import json
from pathlib import PurePosixPath
import subprocess
import zipfile

import yaml

from .experience_contracts import ApiError
from .method_assets import sha
from .asset_identity import AssetIdentityError, checked_files, prepare_contents, read_declaration, safe_path, token

MAX_FILES = 128
MAX_TOTAL = 1024 * 1024
MAX_PREVIEW = 64 * 1024


def development_relations(value, root=None):
    """Report declared Skill references, never infer execution from prose."""
    value = prepare_contents(value)
    skills = []
    known = {b["toolName"] for b in value["bindings"]}
    for file in value["files"]:
        if file["role"] != "SKILL":
            continue
        content = file.get("content") or ""
        declared, status = [], "NOT_DECLARED"
        if content.startswith("---\n"):
            front, separator, _ = content[4:].partition("\n---")
            try:
                metadata = yaml.safe_load(front) if separator else None
                tools = metadata.get("allowed-tools") if isinstance(metadata, dict) else None
                if isinstance(tools, str):
                    declared, status = tools.split(), "DECLARED"
                elif isinstance(tools, list) and all(isinstance(t, str) for t in tools):
                    declared, status = tools, "DECLARED"
            except yaml.YAMLError:
                status = "INVALID_DECLARATION"
        skills.append({"path": file["path"], "fileId": file["fileId"], "status": status,
                       "declaredTools": list(dict.fromkeys(declared)),
                       "unresolvedTools": sorted(set(declared) - known)})
    value["development"] = {"schemaVersion": "asset-development.v0.1", "skills": skills,
                            "executionVerified": False, "sourceReferences": []}
    if root is not None:
        snapshot = RepositorySnapshot(root)
        from .workbench_contracts import catalog
        descriptions = {c["id"]: c for c in catalog(root)["capabilities"]}
        for binding in value["bindings"]:
            entry = descriptions.get(binding["capabilityId"], {})
            binding["label"] = entry.get("label", binding["capabilityId"])
            if entry.get("description"):
                binding["displayDescription"] = entry["description"]
                binding["descriptionSource"] = "CURRENT_CATALOG_PRESENTATION"
        asset_catalog = json.loads(_git(root, "show", snapshot.commit + ":catalog/finance-assets.v1.json"))
        labels = [a for group in asset_catalog.values() if isinstance(group, list)
                  for a in group if isinstance(a, dict)]
        for dependency in value["dependencies"]:
            matches = [a for a in labels if a.get("id") == dependency["id"]
                       and a.get("version") == dependency.get("version")]
            if len(matches) > 1 and dependency.get("source"):
                matches = [a for a in matches if a.get("source") == dependency["source"]]
            dependency["label"] = matches[0].get("displayName", dependency["id"]) if len(matches) == 1 else dependency["id"]
        java = "packages/java/core/src/main/java/io/github/bitlhk/finance/harness/core/"
        references = [
            ("Provider 接口：query / executeTask", java + "spi/FinanceProvider.java"),
            ("受管 Provider 工厂接口", java + "spi/ManagedProviderFactoryV01.java"),
            ("Provider 工厂与发现", java + "spi/ManagedProviderFactoriesV01.java"),
            ("工厂注册参考（模拟银行）", "providers/non-ai-banking/src/main/resources/META-INF/services/io.github.bitlhk.finance.harness.core.spi.ManagedProviderFactoryV01"),
            ("公共授权检查包装器", java + "spi/BoundFinanceProvider.java"),
            ("公共工具观测包装器", java + "observation/ObservedFinanceProvider.java"),
            ("观测 Sink 接口", java + "observation/ObservationSink.java"),
            ("Provider Conformance 参考", "conformance/java-provider-kit/src/main/java/io/github/bitlhk/finance/harness/conformance/ProviderConformanceKit.java"),
            ("来源操作名称参考（模拟银行）", "providers/non-ai-banking/src/main/java/io/github/bitlhk/finance/harness/providers/nonaibanking/ReferenceObservationLabels.java"),
            ("公共观测测试", "packages/java/core/src/test/java/io/github/bitlhk/finance/harness/core/ObservedFinanceProviderTest.java"),
            ("固定版本接入手册", "docs/onboarding-wealth-v01.md"),
        ]
        for label, path in references:
            if _git(root, "ls-tree", snapshot.commit, "--", path):
                value["development"]["sourceReferences"].append({
                    "label": label, "path": path, "commit": snapshot.commit,
                    "url": "https://github.com/bitlhk/finance-agent-harness/blob/" + snapshot.commit + "/" + path})
    return value


def file_view(path, raw, origin, role, reference=None):
    content, status = None, "TOO_LARGE"
    if len(raw) <= MAX_PREVIEW:
        try:
            content, status = raw.decode("utf-8"), "AVAILABLE"
        except UnicodeDecodeError:
            status = "BINARY"
    return {"path": path, "origin": origin, "role": role, "size": len(raw),
            "sha256": sha(raw), "content": content, "previewStatus": status,
            "reference": reference}


def _git(root, *args):
    result = subprocess.run(["git", "-C", str(root), *args], capture_output=True)
    if result.returncode:
        raise ApiError("ASSET_CONTENT_VERSION_UNAVAILABLE", 409)
    return result.stdout


class RepositorySnapshot:
    def __init__(self, root):
        self.root = root
        self.commit = _git(root, "rev-parse", "HEAD").decode().strip()
        self.workspace_id = token(str(root.resolve()))

    def files(self, source, allowed_root, reference):
        if not isinstance(source, str) or not source.startswith("repo://"):
            raise ApiError("ASSET_CONTENT_REFERENCE_UNAVAILABLE", 409)
        path = source.removeprefix("repo://")
        if (not safe_path(path)
                or not path.startswith(allowed_root + "/")):
            raise ApiError("ASSET_CONTENT_REFERENCE_UNAVAILABLE", 409)
        listing = _git(self.root, "ls-tree", "-rz", self.commit, "--", path)
        result = []
        total = 0
        for entry in listing.split(b"\0"):
            if not entry:
                continue
            meta, name = entry.split(b"\t", 1)
            mode, kind, oid = meta.split()
            name = name.decode()
            if mode not in {b"100644", b"100755"} or kind != b"blob":
                raise ApiError("ASSET_CONTENT_UNSAFE_ENTRY", 409)
            size = int(_git(self.root, "cat-file", "-s", oid.decode()))
            total += size
            if len(result) >= MAX_FILES or total > MAX_TOTAL:
                raise ApiError("ASSET_CONTENT_LIMIT_EXCEEDED", 409)
            raw = _git(self.root, "cat-file", "blob", oid.decode())
            # Check the working projection with Git's clean filters; checkout line endings
            # may differ from the blob even when Git considers the file unchanged.
            disk = self.root / name
            try:
                safe = (not disk.is_symlink() and disk.is_file()
                        and disk.resolve().is_relative_to(self.root.resolve()))
            except OSError:
                safe = False
            if not safe:
                raise ApiError("ASSET_CONTENT_SOURCE_CHANGED", 409)
            try:
                cleaned = subprocess.run(
                    ["git", "-C", str(self.root), "hash-object", "--path=" + name, "--", name],
                    capture_output=True,
                )
            except OSError:
                raise ApiError("ASSET_CONTENT_SOURCE_CHANGED", 409) from None
            if cleaned.returncode or cleaned.stdout.strip() != oid:
                raise ApiError("ASSET_CONTENT_SOURCE_CHANGED", 409)
            role = "SKILL" if name.endswith("SKILL.md") else "DECLARATION" if name.endswith("pack.yaml") else "ASSEMBLY" if name.endswith("assembly.v0.1.json") else "OBSERVATION" if name.endswith("observation.v0.1.json") else "CASE" if name.endswith("smoke-cases.yaml") else "CONTRACT" if name.endswith("schema.json") else "REFERENCE"
            identity = {**reference, "source": source, "workspaceId": self.workspace_id,
                        "commit": self.commit, "assetType": {
                            "capability-packs": "CAPABILITY_PACK", "domain-packs": "DOMAIN_PACK",
                            "contracts": "CONTRACT"}[allowed_root]}
            item = file_view(name, raw, "REFERENCED_ASSET", role, identity)
            if PurePosixPath(name).suffix not in {".md", ".json", ".yaml", ".yml", ".txt"}:
                item.update(content=None, previewStatus="UNSUPPORTED_TYPE")
            result.append(item)
        if not result:
            raise ApiError("ASSET_CONTENT_REFERENCE_UNAVAILABLE", 409)
        if allowed_root in {"capability-packs", "domain-packs"}:
            declaration = next((f for f in result if f["path"] == path + "/pack.yaml"), None)
            if not declaration or not declaration.get("content"):
                raise ApiError("ASSET_CONTENT_REFERENCE_UNAVAILABLE", 409)
            document = read_declaration(declaration)
            if not isinstance(document, dict) or any(
                    reference.get(field) and reference[field] != document.get(declared)
                    for field, declared in (("id", "packId"), ("version", "version"))):
                raise AssetIdentityError("ASSET_CONTENT_SOURCE_MISMATCH", "资产声明的 ID 或版本与所选来源不一致，请核对该版本的 pack.yaml。", declaration)
        return checked_files(result)


def _usage(jobs, owner, *, reference=None, packs=()):
    result = []
    with jobs.lock:
        for job in jobs.jobs.values():
            if job.get("owner") != owner:
                continue
            if reference is not None:
                ref = job.get("methodRef") or job.get("selection", {}).get("methodRef") or {}
                matched = all(ref.get(k) == v for k, v in reference.items())
            else:
                selected = job.get("selection", {}).get("packBindings", [])
                matched = any(p.get("pack") in packs for p in selected)
            if matched:
                result.append({"buildId": job["id"], "status": job["status"],
                               "displayName": job.get("selection", {}).get("interfaceConfig", {}).get("assistantName"),
                               "nameSource": "BUILD_SELECTION",
                               "association": "DECLARED_SELECTION", "url": "/?agent=" + job["id"]})
    return result


def release_contents(jobs, owner, reference):
    from .business_packages import export_release, inspect_archive
    from .method_verification import release_verification
    release, trial = jobs.method_assets.release_trial(owner, reference)
    archive = export_release(release, trial, jobs.builder.harness_repo, jobs.business_package_anchor)
    raw = base64.b64decode(archive["base64"])
    manifest, files = inspect_archive(raw, jobs.builder.harness_repo, jobs.business_package_anchor)
    with zipfile.ZipFile(io.BytesIO(raw)) as zip_file:
        manifest_raw = zip_file.read("finance-business-package.json")
    package_reference = {"assetType": "BUSINESS_PACKAGE", "id": reference["assetId"],
                         "version": reference["version"], "releaseDigest": reference["releaseDigest"],
                         "source": "package://" + reference["assetId"],
                         "workspaceId": token([str(jobs.method_assets.root.resolve()), owner])}
    entries = [file_view("finance-business-package.json", manifest_raw, "PACKAGE_FILE", "MANIFEST", package_reference)]
    if {item["path"] for item in manifest["files"]} != set(files):
        raise AssetIdentityError("ASSET_CONTENT_SOURCE_MISMATCH", "发布清单与实际文件集合不一致，请重新取得该版本。")
    for item in sorted(manifest["files"], key=lambda item: item["path"]):
        file = file_view(item["path"], files[item["path"]], "PACKAGE_FILE", item["role"], package_reference)
        if item.get("sha256") != file["sha256"] or item.get("size") != file["size"]:
            raise AssetIdentityError("ASSET_CONTENT_DIGEST_MISMATCH", "发布清单的文件摘要或大小与实际内容不一致，请核对声明。", file)
        entries.append(file)
    entries = checked_files(entries)
    snapshot = RepositorySnapshot(jobs.builder.harness_repo)
    catalog = json.loads(_git(snapshot.root, "show", snapshot.commit + ":catalog/finance-assets.v1.json"))
    dependencies = []
    for ref in manifest["skill"]["basePackageRefs"]:
        candidates = [item for item in catalog["capabilityPacks"]
                      if item["id"] == ref["id"] and item["version"] == ref["version"]]
        if len(candidates) != 1:
            dependencies.append({"kind": "REFERENCED_ASSET", **ref, "status": "REFERENCE_ONLY",
                                 "reason": "目标环境未取得此准确版本，不能预览其他版本作为替代。"})
            continue
        dependency_files = snapshot.files(candidates[0]["source"], "capability-packs", {**ref, "commit": snapshot.commit})
        entries.extend(dependency_files)
        declaration = candidates[0]["source"][7:] + "/pack.yaml"
        declaration_file = next(f for f in dependency_files if f["path"] == declaration)
        dependencies.append({"kind": "REFERENCED_ASSET", **ref, "commit": snapshot.commit,
                             "source": candidates[0]["source"], "assetType": "CAPABILITY_PACK",
                             "workspaceId": snapshot.workspace_id,
                             "status": "AVAILABLE", "filePath": declaration, "fileId": declaration_file["fileId"]})
        for binding in manifest["bindings"]:
            if binding["capabilityPack"] == ref:
                binding["inputFile"] = declaration
                binding["inputFileId"] = declaration_file["fileId"]
    contract = "contracts/json-schema/capability/capability-result.v1.schema.json"
    try:
        contract_files = snapshot.files("repo://" + contract, "contracts", {"id": "capability-result", "version": "1", "commit": snapshot.commit})
        entries.extend(contract_files)
        for binding in manifest["bindings"]:
            binding["outputFile"] = contract
            binding["outputFileId"] = next(f["fileId"] for f in contract_files if f["path"] == contract)
    except ApiError as error:
        if str(error) != "ASSET_CONTENT_REFERENCE_UNAVAILABLE":
            raise
    entries = checked_files(entries)
    if len(entries) > MAX_FILES or sum(item["size"] for item in entries) > MAX_TOTAL:
        raise ApiError("ASSET_CONTENT_LIMIT_EXCEEDED", 409)
    return prepare_contents({"schemaVersion": "finance-asset-contents.v0.1", "kind": "PUBLISHED_PACKAGE",
            "name": manifest["package"]["displayName"], "version": release["version"],
            "identity": {**reference, "archiveSha256": archive["sha256"], "harnessCommit": jobs.business_package_anchor.revision},
            "files": entries, "bindings": manifest["bindings"], "requirements": manifest["requirements"],
            "dependencies": dependencies,
            "validation": manifest["validation"],
            "verification": {"createdAt": trial.get("createdAt"),
                             "runtime": trial.get("report", {}).get("trialRuntime"),
                             "model": trial.get("report", {}).get("model"),
                             "source": trial.get("report", {}).get("source"),
                             "comparisonKind": trial.get("report", {}).get("comparisonKind"),
                             "trialId": trial.get("trialId"),
                             **release_verification(release, trial)},
            "usages": _usage(jobs, owner, reference=reference),
            "downloadUrl": "/api/v1/assets/" + reference["assetId"] + "/packages/" + reference["version"] + "/" + reference["releaseDigest"]})


def component_contents(jobs, owner, component_id, revision):
    from .unified_catalog import unified_catalog
    root = jobs.builder.harness_repo
    snapshot = RepositorySnapshot(root)
    directory = unified_catalog(jobs.project, root)["directory"]
    if directory["revision"] != revision:
        raise ApiError("DIRECTORY_CHANGED", 409)
    part = next((p for p in directory["components"] if p["id"] == component_id), None)
    if part is None:
        raise ApiError("ASSET_CONTENT_REFERENCE_UNAVAILABLE", 404)
    files, dependencies, bindings = [], [], []
    for field, prefix, kind in (("capabilityPackRefs", "capability-packs", "CAPABILITY_PACK"),
                                ("domainPackRefs", "domain-packs", "DOMAIN_PACK")):
        for ref in part[field]:
            identity = {**ref, "commit": snapshot.commit,
                        "assetType": kind, "source": ref["source"], "workspaceId": snapshot.workspace_id}
            entries = snapshot.files(ref["source"], prefix, identity)
            manifest_file = next((f for f in entries if f["path"] == ref["source"][7:] + "/pack.yaml"), None)
            if not manifest_file or manifest_file["content"] is None:
                raise ApiError("ASSET_CONTENT_REFERENCE_UNAVAILABLE", 409)
            manifest = read_declaration(manifest_file)
            if manifest.get("packId") != ref["id"] or manifest.get("version") != ref["version"]:
                raise ApiError("ASSET_CONTENT_SOURCE_CHANGED", 409)
            files.extend(entries)
            dependencies.append({"kind": kind, **identity, "source": ref["source"],
                                 "status": "AVAILABLE", "filePath": manifest_file["path"]})
            for definition in manifest.get("capabilities", []) if kind == "CAPABILITY_PACK" else []:
                bindings.append({"capabilityId": definition["capabilityId"], "toolName": definition["toolName"],
                                 "operation": definition["operation"], "inputSchema": definition.get("inputSchema"),
                                 "inputFile": manifest_file["path"], "inputFileId": manifest_file["fileId"],
                                 "capabilityPack": identity, "outputSchemaRef": "schema://capability-result.v1",
                                 "requiredScopes": definition.get("requiredScopes", []),
                                 "providerId": part["capabilityBindings"].get(definition["capabilityId"])})
    contract = "contracts/json-schema/capability/capability-result.v1.schema.json"
    contract_files = snapshot.files("repo://" + contract, "contracts", {"id": "capability-result", "version": "1", "commit": snapshot.commit})
    files.extend(contract_files)
    for binding in bindings:
        binding["outputFile"] = contract
        binding["outputFileId"] = next(f["fileId"] for f in contract_files if f["path"] == contract)
    provider_refs = list(part["providerRefs"])
    for option in part.get("providerOptions", []):
        for ref in option.get("providerRefs", []):
            if ref not in provider_refs:
                provider_refs.append(ref)
    for binding in bindings:
        candidates = [ref for option in part.get("providerOptions", [])
                      if binding["capabilityId"] in option.get("capabilityBindings", {})
                      for ref in option.get("providerRefs", [])
                      if ref["id"] == option["capabilityBindings"][binding["capabilityId"]]]
        binding["providerCandidates"] = [ref for i, ref in enumerate(candidates) if ref not in candidates[:i]]
    for ref in provider_refs:
        dependencies.append({"kind": "EXECUTION_ARTIFACT", "id": ref["id"], "version": ref["version"],
                             "source": ref["source"], "workspaceId": snapshot.workspace_id,
                             "commit": snapshot.commit, "status": "REFERENCE_ONLY",
                             "reason": "此处是 Provider 声明引用；是否为源码或 Maven 制品以引用协议为准，实际安装 JAR 与摘要请查看具体 Agent 的安装记录。"})
    files = checked_files(files)
    if len(files) > MAX_FILES or sum(f["size"] for f in files) > MAX_TOTAL:
        raise ApiError("ASSET_CONTENT_LIMIT_EXCEEDED", 409)
    support = None
    if part.get("methodCustomizationSupported") and bindings and all(b["operation"] == "QUERY" for b in bindings):
        from .business_assets import component_development_support
        try:
            support = component_development_support(jobs.project, root, component_id, revision,
                                                    store=jobs.method_assets, owner=owner)
        except ApiError as error:
            # Optional documentation may be unavailable; identity/admission errors remain fatal.
            if error.code not in {"DEVELOPMENT_SUPPORT_UNAVAILABLE", "DEVELOPMENT_SUPPORT_INVALID"}:
                raise
            support = {"schemaVersion": "finance-development-support-projection.v0.1",
                       "status": "UNAVAILABLE", "message": "此版本开发验证资料暂不可用，请核对声明来源。",
                       "error": error.code}
    return prepare_contents({"schemaVersion": "finance-asset-contents.v0.1", "kind": "CATALOG_COMPONENT",
            "name": part["label"], "version": part["version"],
            "identity": {"directoryRevision": revision, "harnessCommit": snapshot.commit},
            "files": files, "bindings": bindings, "dependencies": dependencies,
            "developmentSupport": support,
            "requirements": {"runtime": part["runtime"], "runtimeIds": part["runtimeIds"], "profile": part["profileRef"]},
            "validation": {"directoryStatus": part["status"], "professionalQuality": "NOT_EVALUATED"},
            "usages": _usage(jobs, owner, packs=[{"id": r["id"], "version": r["version"]} for r in part["capabilityPackRefs"]])})
