"""Pure identity checks for read projections, not installation admission rules."""
from copy import deepcopy
from hashlib import sha256
import json
from pathlib import PurePosixPath
import re
import yaml

from .experience_contracts import ApiError


class AssetIdentityError(ApiError):
    def __init__(self, code, message, *locations):
        super().__init__(code, 409)
        self.message = message
        self.locations = [safe_location(item) for item in locations]


def safe_path(path):
    return (isinstance(path, str) and bool(path) and path != "."
            and not PurePosixPath(path).is_absolute()
            and ".." not in PurePosixPath(path).parts and "\\" not in path
            and ":" not in path and PurePosixPath(path).as_posix() == path
            and not any(ord(c) < 32 for c in path))


def safe_location(item):
    result = {"path": item["path"] if safe_path(item.get("path")) else "[来源待核对]"}
    reference = item.get("reference") or {}
    source = reference.get("source", "")
    if isinstance(source, str) and source.startswith(("repo://", "package://")) and safe_path(source.split("://", 1)[1]):
        result["source"] = source
    for field in ("startLine", "endLine"):
        if type(item.get(field)) is int and item[field] > 0:
            result[field] = item[field]
    return result


def token(value):
    return sha256(json.dumps(value, sort_keys=True, ensure_ascii=True,
                             separators=(",", ":")).encode()).hexdigest()


def asset_key(reference):
    reference = reference or {}
    return tuple(reference.get(k) for k in ("assetType", "id", "version", "source", "workspaceId"))


def check_asset_claims(entries):
    claims = {}
    for entry in entries:
        reference = entry.get("reference") or entry
        identity = asset_key({"assetType": reference.get("kind"), **reference})
        if not all(isinstance(v, str) and v for v in identity):
            continue
        for field in ("digest", "sha256", "releaseDigest"):
            digest = _digest(reference.get(field))
            if digest:
                key = (*identity, field)
                if key in claims and claims[key][0] != digest:
                    raise AssetIdentityError("ASSET_IDENTITY_CONFLICT", "同一资产来源和版本声明了不同摘要，请核对声明并选择正确版本。", claims[key][1], entry)
                claims[key] = (digest, entry)


def _digest(value):
    if value is None:
        return None
    if not isinstance(value, str) or not re.fullmatch(r"(?:sha256:)?[a-fA-F0-9]{64}", value):
        raise AssetIdentityError("ASSET_CONTENT_DIGEST_INVALID", "文件摘要格式无效，请核对来源声明。")
    return value.removeprefix("sha256:").lower()


def _check_source(file):
    path = file.get("path")
    if not safe_path(path):
        raise AssetIdentityError("ASSET_CONTENT_UNSAFE_ENTRY", "文件路径不在合法资产来源内，请核对声明。", file)
    source = (file.get("reference") or {}).get("source")
    if source is not None and not isinstance(source, str):
        raise AssetIdentityError("ASSET_CONTENT_SOURCE_MISMATCH", "文件来源无效，请核对资产声明。", file)
    if source and source.startswith("repo://"):
        root = source[7:]
        if not safe_path(root) or not (path == root or path.startswith(root + "/")):
            raise AssetIdentityError("ASSET_CONTENT_SOURCE_MISMATCH", "文件不属于声明的资产来源，请重新选择正确来源。", file)
    elif source and (not source.startswith("package://") or not safe_path(source[10:])):
        raise AssetIdentityError("ASSET_CONTENT_SOURCE_MISMATCH", "文件来源协议无效，请重新核对资产来源。", file)


def checked_files(entries):
    """Validate before indexing; unproven historical references stay separate."""
    check_asset_claims(entries)
    result, seen, path_counts, occurrences = [], {}, {}, {}
    for file in entries:
        path_counts[file["path"]] = path_counts.get(file["path"], 0) + 1
    for original in entries:
        file = deepcopy(original)
        _check_source(file)
        identity = asset_key(file.get("reference"))
        digest = _digest(file.get("sha256"))
        content = file.get("content")
        if digest and content is not None and sha256(content.encode("utf-8")).hexdigest() != digest:
            raise AssetIdentityError("ASSET_CONTENT_DIGEST_MISMATCH", "文件正文与已记录摘要不一致，请重新取得此版本。", file)
        complete = all(isinstance(v, str) and v for v in identity)
        reference = file.get("reference") or {}
        asset_digest = _digest(reference.get("digest") or reference.get("sha256"))
        verified_asset_digest = _digest(file.get("verifiedAssetDigest"))
        if asset_digest and verified_asset_digest and asset_digest != verified_asset_digest:
            raise AssetIdentityError("ASSET_CONTENT_DIGEST_MISMATCH", "资产来源内容与声明的整包摘要不一致，请重新取得正确版本。", file)
        verified = complete and digest and (not asset_digest or asset_digest == verified_asset_digest)
        key = (*identity, file["path"])
        previous = seen.get(key) if complete else None
        if previous:
            if (previous["role"] != file["role"] or previous["origin"] != file["origin"]
                    or (_digest(previous.get("sha256")) and digest
                        and _digest(previous["sha256"]) != digest)):
                raise AssetIdentityError("ASSET_IDENTITY_CONFLICT", "同一来源和版本的文件角色或内容摘要冲突，请核对两处声明，不能覆盖旧内容。", previous, file)
            if digest and _digest(previous.get("sha256")) == digest:
                for reference in file.get("references", [file.get("reference")]):
                    if reference and reference not in previous["references"]:
                        previous["references"].append(deepcopy(reference))
                continue
        file["identityStatus"] = "VERIFIED" if verified else "SOURCE_UNVERIFIED"
        file["identityMessage"] = "来源与摘要已核对" if verified else "来源待核对"
        if complete and digest:
            file["fileId"] = "asset-file:" + token([key, file["origin"], file["role"], digest])
        else:
            # Legacy paths remain addressable only when unique; this is not a content digest.
            occurrence_key = (*key, file["role"])
            occurrence = occurrences.get(occurrence_key, 0)
            occurrences[occurrence_key] = occurrence + 1
            file["fileId"] = (file["path"] if path_counts[file["path"]] == 1
                              else "legacy-file:" + token([key, file["role"], occurrence]))
        file["references"] = deepcopy(file.get("references") or ([file["reference"]] if file.get("reference") else []))
        if complete and (not previous or not previous.get("sha256")):
            seen[key] = file
        result.append(file)
    return result


def read_declaration(file):
    """Reject duplicate YAML keys before the parser can overwrite identity fields."""
    class UniqueLoader(yaml.SafeLoader):
        pass

    def mapping(loader, node):
        result = {}
        for key_node, value_node in node.value:
            key = loader.construct_object(key_node, deep=True)
            if not isinstance(key, (str, int, float, bool)) or key in result:
                raise AssetIdentityError("ASSET_DECLARATION_CONFLICT", "声明存在重复或无效字段，请核对标记位置后修正。",
                                         {**file, "startLine": key_node.start_mark.line + 1})
            result[key] = loader.construct_object(value_node, deep=True)
        return result

    UniqueLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, mapping)
    try:
        return yaml.load(file.get("content") or "", Loader=UniqueLoader)
    except yaml.YAMLError:
        raise AssetIdentityError("ASSET_DECLARATION_INVALID", "声明格式无效，请修正此版本的 YAML 文件。", file) from None


def resolve_file(files, identifier, *, reference=None):
    """Resolve only inside an already authorized contents response."""
    if not identifier:
        return None
    candidates = [f for f in files if f.get("fileId") == identifier]
    if not candidates:
        candidates = [f for f in files if f["path"] == identifier]
    if reference:
        fields = ("assetType", "id", "version", "source", "workspaceId")
        candidates = [f for f in candidates if all(
            (f.get("reference") or {}).get(k) == reference[k] for k in fields if reference.get(k))]
    if len(candidates) > 1:
        raise AssetIdentityError("ASSET_FILE_AMBIGUOUS", "此路径对应多个来源或版本，请选择明确的文件来源。", *candidates)
    return candidates[0] if candidates else None


def prepare_contents(value):
    result = deepcopy(value)
    result["files"] = checked_files(value["files"])
    check_asset_claims(result.get("dependencies", []))
    for binding in result["bindings"]:
        for field in ("inputFile", "outputFile"):
            file = resolve_file(result["files"], binding.get(field + "Id") or binding.get(field),
                                reference=binding.get("capabilityPack") if field == "inputFile" else None)
            if file:
                binding[field + "Id"] = file["fileId"]
    for dependency in result.get("dependencies", []):
        file = resolve_file(result["files"], dependency.get("fileId") or dependency.get("filePath"),
                            reference=dependency if dependency.get("source") else None)
        if file:
            dependency["fileId"] = file["fileId"]
    return result
