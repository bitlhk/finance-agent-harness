"""Versioned presentation projection, not an execution graph or an admission engine."""
import json
import yaml

from .asset_identity import AssetIdentityError, prepare_contents, read_declaration, resolve_file, token


FILE_ROLES = {
    "MANIFEST": ("包清单", "Packager 记录版本、依赖与文件摘要。", "由现有发布流程生成；不要手工修改已发布包。"),
    "SKILL": ("专业方法", "Runtime 消费方法正文；步骤不自动成为强制策略。", "创建方法草稿，修改正文和案例，重新绑定工具、检查并试跑。"),
    "DECLARATION": ("能力与方法声明", "Assembler 读取工具定义及依赖。", "在外部工程修改声明，执行 Schema、兼容与安装测试后准入。"),
    "ASSEMBLY": ("装配要求", "构建与安装阶段核对运行环境、执行及观测要求。", "在组件工程修改要求；未知或不支持的协议必须拒绝。"),
    "OBSERVATION": ("观测要求", "声明需要的事件和来源操作；实际是否采集需核对运行证据。", "在组件观测声明和 Provider 观测扩展中修改，运行 Conformance 并核对实际事件；不能只改声明。"),
    "CONTRACT": ("合同定义", "Provider 与消费者共同遵守字段约定。", "外部工程修改；核验字段口径、正反例及双方兼容性。"),
    "CASE": ("验证案例", "验证入口消费输入与预期；不是已通过的结果。", "草稿更新案例，再试跑；新增能力还需工程负向测试。"),
    "VALIDATION_SUMMARY": ("验证摘要", "保留该版本实际验证范围。", "只读；修改内容后重新执行，不沿用旧结论。"),
    "REFERENCE": ("参考资料", "供方法和开发者读取，不作为可执行代码。", "通过已有导入流程形成新草稿。"),
}


def reading_groups(nodes):
    """Navigation indexes only: every original node remains addressable once."""
    definitions = [
        ("methods", "专业方法", "book-open", {"SKILL", "DOMAIN_PACK", "REFERENCE"}),
        ("capabilities", "业务能力", "blocks", {"CAPABILITY", "CAPABILITY_PACK"}),
        ("contracts", "数据与接口约定", "file-check-2", {"CONTRACT", "DECLARATION"}),
        ("providers", "银行接入", "plug", {"EXECUTION_ARTIFACT"}),
        ("requirements", "执行与观测要求", "shield-check", {"REQUIREMENTS", "ASSEMBLY", "OBSERVATION"}),
        ("validation", "验证案例与结果", "clipboard-check", {"CASE", "VALIDATION_SUMMARY"}),
        ("other", "其他引用与包信息", "package", set()),
        ("platform", "公共接入与检查", "shield-check", {"PLATFORM"}),
    ]
    groups = [{"id": id, "label": label, "icon": icon, "memberIds": []} for id, label, icon, _ in definitions]
    for node in nodes:
        index = next((i for i, (_, _, _, kinds) in enumerate(definitions) if node["kind"] in kinds), 6)
        groups[index]["memberIds"].append(node["id"])
    return [g for g in groups if g["id"] != "other" or g["memberIds"]]


def composition(value):
    value = prepare_contents(value)
    nodes, edges = [], []
    files = {f["fileId"]: f for f in value["files"]}
    declarations = {}
    locations = {}
    for file in files.values():
        title, purpose, modify = FILE_ROLES.get(file["role"], FILE_ROLES["REFERENCE"])
        file["guidance"] = {"title": title, "purpose": purpose, "modify": modify}
        if file.get("content") and file["role"] == "DECLARATION":
            document = read_declaration(file)
            if isinstance(document, dict):
                for cap in document.get("capabilities", []):
                    if isinstance(cap, dict) and "capabilityId" in cap:
                        key = (file["fileId"], cap["capabilityId"])
                        if key in declarations:
                            raise AssetIdentityError("ASSET_DECLARATION_CONFLICT", "同一声明文件重复定义能力，请移除重复定义后重新核对。", file)
                        declarations[key] = cap
                tree = yaml.compose(file["content"])
                if isinstance(tree, yaml.MappingNode):
                    for key, collection in tree.value:
                        if key.value != "capabilities" or not isinstance(collection, yaml.SequenceNode):
                            continue
                        for item in collection.value:
                            if not isinstance(item, yaml.MappingNode):
                                continue
                            fields = {key.value: val for key, val in item.value}
                            identity = fields.get("capabilityId")
                            if isinstance(identity, yaml.ScalarNode):
                                locations[(file["fileId"], identity.value)] = {
                                    "path": file["path"], "fileId": file["fileId"], "capabilityId": identity.value,
                                    "startLine": item.start_mark.line + 1,
                                    "endLine": item.end_mark.line,
                                }
        if file["role"] == "CONTRACT" and file.get("content"):
            schema = json.loads(file["content"])
            file["guidance"]["title"] = schema.get("title") or title
            if ((file.get("reference") or {}).get("id") == "capability-result"
                    or schema.get("properties", {}).get("capabilityId") and schema.get("properties", {}).get("data")):
                file["guidance"].update(title="通用能力结果外壳", purpose="统一调用结果与证据外壳；不等于完整金融业务字段合同。")

    def add(id, label, kind, group, purpose, modify, paths=(), **extra):
        node = {"id": id, "label": label, "kind": kind, "group": group,
                "purpose": purpose, "modify": modify, "files": [files[p]["path"] for p in paths if p in files],
                "fileIds": [p for p in paths if p in files],
                "status": "DECLARED", **extra}
        nodes.append(node)
        return id

    def edge(source, target, label):
        if not any(e == {"source": source, "target": target, "label": label} for e in edges):
            edges.append({"source": source, "target": target, "label": label})

    for file in files.values():
        if file["role"] not in {"SKILL", "CASE", "REFERENCE", "CONTRACT", "VALIDATION_SUMMARY", "MANIFEST", "ASSEMBLY", "OBSERVATION"}:
            continue
        guidance = file["guidance"]
        label = guidance["title"]
        if file["role"] == "SKILL":
            text = file.get("content") or ""
            if text.startswith("---\n"):
                front, sep, _ = text[4:].partition("\n---")
                try:
                    metadata = yaml.safe_load(front) if sep else {}
                except yaml.YAMLError:
                    metadata = {}
                if isinstance(metadata, dict):
                    heading = next((line[2:].strip() for line in text.splitlines() if line.startswith("# ")), None)
                    label = heading or metadata.get("name") or label
        add("file:" + file["fileId"], label, file["role"],
            "contents" if file["origin"] == "PACKAGE_FILE" else "dependencies",
            guidance["purpose"], guidance["modify"], [file["fileId"]],
            reference=file.get("reference"), status="AVAILABLE", digest=file.get("sha256"),
            identityStatus=file["identityStatus"], identityMessage=file["identityMessage"])

    dependency_nodes, dependency_ids = {}, {}
    for dep in value["dependencies"]:
        label = dep.get("label") or dep.get("displayName") or dep["id"]
        paths = [f["fileId"] for f in files.values() if f.get("reference") and
                 all(f["reference"].get(k) == dep[k] for k in
                     ("id", "version", "source", "workspaceId", "assetType") if dep.get(k))]
        node_id = "dependency:" + dep["id"] + "@" + str(dep.get("version", "unversioned"))
        kinds = {d['kind'] for d in value['dependencies'] if d['id'] == dep['id'] and d.get('version') == dep.get('version')}
        if dep.get("source") or len(kinds) > 1:
            node_id += ":" + token([dep["kind"], dep.get("source"), dep.get("workspaceId")])
        dependency_ids[token(dep)] = node_id
        if node_id in {n["id"] for n in nodes}:
            continue
        if dep["kind"] == "EXECUTION_ARTIFACT":
            dependency_nodes.setdefault(dep["id"], []).append((node_id, dep))
        add(node_id, label, dep["kind"], "dependencies",
            "受管理执行制品实现能力；连接和密钥由目标环境绑定。" if dep["kind"] == "EXECUTION_ARTIFACT" else "本业务包引用的指定版本资产。",
            "在源码工程修改、编译和测试，再由维护者准入；上传包不会执行代码。" if dep["kind"] == "EXECUTION_ARTIFACT" else "选择兼容且已准入版本，重新检查并发布。",
            paths, reference=dep, status=dep["status"], reason=dep.get("reason"))
        for path in paths:
            if any(n["id"] == "file:" + path for n in nodes):
                edge(node_id, "file:" + path, "包含")
    bindings, binding_keys = [], {}
    for binding in value["bindings"]:
        key = (binding.get("inputFileId"), binding["capabilityId"])
        if key in binding_keys:
            if binding_keys[key] != binding:
                file = files.get(binding.get("inputFileId"), {})
                raise AssetIdentityError("ASSET_BINDING_CONFLICT", "同一能力声明存在不同绑定，请核对绑定来源后再选择。", file)
            continue
        binding_keys[key] = binding
        bindings.append(binding)
    value["bindings"] = bindings
    capability_nodes = {}
    for binding in bindings:
        cid = binding["capabilityId"]
        node_id = "capability:" + cid
        if (str(binding.get("inputFileId", "")).startswith("asset-file:")
                or sum(b["capabilityId"] == cid for b in bindings) > 1):
            node_id += ":" + token(binding.get("inputFileId"))
        capability_nodes[(binding.get("inputFileId"), cid)] = node_id

    def capability_node(binding):
        return capability_nodes[(binding.get("inputFileId"), binding["capabilityId"])]

    for binding in bindings:
        id = binding["capabilityId"]
        cap_node = capability_node(binding)
        declared = declarations.get((binding.get("inputFileId"), id), {})
        label = binding.get("label") or declared.get("name") or declared.get("displayName") or id
        add(cap_node, label, "CAPABILITY", "dependencies",
            binding.get("displayDescription") or declared.get("description") or "声明工具输入、操作类型与所需权限；声明不授予实际权限。",
            "在能力工程修改合同与实现，编译并运行正常、拒绝、缺数和兼容测试。",
            [binding.get("inputFileId")], reference=binding,
            originalDescription=declared.get("description"), descriptionSource=binding.get("descriptionSource"),
            declarationLocation=locations.get((binding.get("inputFileId"), id)))
        for field, relation in (("inputFile", "输入声明"), ("outputFile", "结果外壳")):
            path = binding.get(field + "Id")
            if path in files:
                target = "file:" + path
                if not any(n["id"] == target for n in nodes):
                    f = files[path]
                    add(target, f["guidance"]["title"], f["role"], "dependencies", f["guidance"]["purpose"], f["guidance"]["modify"], [path], reference=f.get("reference"), status="AVAILABLE")
                edge(cap_node, target, "输入定义位于" if field == "inputFile" and files[path]["role"] == "DECLARATION" else "遵循 · " + relation)
        provider = binding.get("providerId")
        provider_ref = binding.get("providerRef")
        choices = binding.get("providerCandidates", [])
        if provider or provider_ref or choices:
            bound_provider = provider or (provider_ref or {}).get("id")
            choice_ids = {choice["id"] for choice in choices}
            provider = bound_provider or (next(iter(choice_ids)) if len(choice_ids) == 1 else None)
            candidates = dependency_nodes.get(provider, []) if not choices else [
                candidate for values in dependency_nodes.values() for candidate in values
                if (not bound_provider or candidate[1]["id"] == bound_provider)
                and any(all(candidate[1].get(k) == choice.get(k) for k in ("id", "version", "source")) for choice in choices)]
            if provider_ref:
                candidates = [(nid, dep) for nid, dep in candidates if all(
                    dep.get(k) == provider_ref[k] for k in ("id", "version", "source") if provider_ref.get(k))]
            target = candidates[0][0] if len(candidates) == 1 else "provider-reference:" + (provider or token(choices))
            if not any(n["id"] == target for n in nodes):
                add(target, "待确定版本", "EXECUTION_ARTIFACT", "dependencies", "能力声明的执行实现引用，未唯一确定对应版本。", "补充目标环境已准入的兼容 Provider；多个版本时先消除绑定歧义。", status="REFERENCE_ONLY", reference={"providerId": provider, "candidates": [dep for _, dep in candidates]})
            edge(target, cap_node, "实现")
        for dependency in nodes:
            if dependency["id"].startswith("dependency:") and binding.get("inputFileId") in dependency["fileIds"]:
                edge(dependency["id"], cap_node, "声明")
    for node in nodes:
        definitions = [b for b in bindings if b.get("inputFileId") in node["fileIds"]]
        if node["kind"] == "DECLARATION" and definitions:
            node["label"] = "能力声明文件"
            node["definitionCount"] = len({b["capabilityId"] for b in definitions})
            node["purpose"] = f"包含{node['definitionCount']}项能力各自的输入定义；同一文件不代表使用相同输入合同。"
    for skill in value["development"]["skills"]:
        skill_file = resolve_file(value["files"], skill.get("fileId") or skill["path"])
        if skill_file is None:
            continue
        skill_node = "file:" + skill_file["fileId"]
        for tool in skill["declaredTools"]:
            matches = [b for b in bindings if b["toolName"] == tool]
            for match in matches:
                edge(skill_node, capability_node(match), "使用 · 声明引用")
        node = next(n for n in nodes if n["id"] == skill_node)
        node["unresolvedTools"] = skill["unresolvedTools"]
    known_capabilities = {b["capabilityId"] for b in value["bindings"]}
    # Pack-level scope is not a claim that each Skill called every available tool.
    for dep in value["dependencies"]:
        if dep["kind"] != "DOMAIN_PACK":
            continue
        declaration = files.get(dep.get("fileId"), {})
        document = yaml.safe_load(declaration.get("content") or "")
        for capability in document.get("capabilities", []) if isinstance(document, dict) else []:
            if isinstance(capability, str) and capability in known_capabilities:
                source = dependency_ids[token(dep)]
                for binding in bindings:
                    if binding["capabilityId"] == capability:
                        edge(source, capability_node(binding), "声明可用能力")
    if value["kind"] == "PUBLISHED_PACKAGE":
        for file in files.values():
            if file["role"] != "MANIFEST" or not file.get("content"):
                continue
            manifest = json.loads(file["content"])
            entry = resolve_file(value["files"], manifest.get("skill", {}).get("entry"), reference=file.get("reference"))
            if entry is None or entry["role"] != "SKILL":
                continue
            for binding in manifest.get("bindings", []):
                if binding.get("capabilityId") in known_capabilities:
                    for match in bindings:
                        if match["capabilityId"] == binding["capabilityId"] and (
                                not binding.get("capabilityPack") or all(
                                    (match.get("capabilityPack") or {}).get(k) == v
                                    for k, v in binding["capabilityPack"].items())):
                            edge("file:" + entry["fileId"], capability_node(match), "使用范围 · 发布绑定")
    add("requirements", "使用与执行要求", "REQUIREMENTS", "contents" if value["kind"] == "PUBLISHED_PACKAGE" else "dependencies",
        "声明适用环境与执行条件；实际权限在运行时从权威来源取得。", "修改声明后重新检查兼容性；新协议需先完成公共机制适配。",
        [f["fileId"] for f in files.values() if f["role"] in {"ASSEMBLY", "MANIFEST"}], reference=value["requirements"])
    for id, label, purpose in [
        ("core", "Core 公共合同", "统一请求、结果与 Provider SPI；领域字段仍由业务合同定义。"),
        ("integrator", "Integrator 接线", "注册工具、接入可信上下文；覆盖范围依赖实际 Runtime 适配。"),
        ("assembler", "Assembler 安装校验", "核验实际安装依赖并记录每个 Agent 的安装 Lock。"),
        ("checks", "身份检查与基础记录", "已接线的宿主和公共包装器检查身份并采集基础事件；声明不代表已埋点。")]:
        add("platform:" + id, label, "PLATFORM", "platform", purpose, "复用公共机制；Provider 补源身份与业务观测，查看具体实例证据确认生效。", status="PLATFORM_SUPPORT")
    value["composition"] = {"schemaVersion": "asset-composition.v0.2", "nodes": nodes, "relations": edges,
        "readingGroups": reading_groups(nodes),
        "skillStatus": "AVAILABLE" if value["development"]["skills"] else "NO_INDEPENDENT_SKILL",
        "semantics": "DECLARED_COMPOSITION_NOT_EXECUTION"}
    return value
