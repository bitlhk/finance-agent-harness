from copy import deepcopy
import pytest
from finance_solution_builder.asset_composition import composition
from finance_solution_builder.asset_contents import development_relations, file_view
from finance_solution_builder.asset_identity import AssetIdentityError


def example():
    return {"kind": "CATALOG_COMPONENT", "files": [
        {"path": "methods/a/SKILL.md", "role": "SKILL", "origin": "REFERENCED_ASSET", "content": "---\nallowed-tools: [a, b, missing]\n---\n"},
        {"path": "pack.yaml", "role": "DECLARATION", "origin": "REFERENCED_ASSET", "content": "capabilities: [a, b]\n"},
        {"path": "result.schema.json", "role": "CONTRACT", "origin": "REFERENCED_ASSET", "content": '{"properties":{"capabilityId":{"type":"string"},"data":{"type":"object"}}}'},
    ], "bindings": [
        {"capabilityId": "cap-a", "toolName": "a", "label": "甲能力", "inputFile": "pack.yaml", "outputFile": "result.schema.json", "providerId": "provider-a"},
        {"capabilityId": "cap-b", "toolName": "b", "label": "乙能力", "inputFile": "pack.yaml", "outputFile": "result.schema.json", "providerId": "provider-b"},
    ], "dependencies": [{"id": "provider-a", "kind": "EXECUTION_ARTIFACT", "version": "1", "status": "REFERENCE_ONLY"},
                         {"id": "provider-b", "kind": "EXECUTION_ARTIFACT", "version": "2", "status": "REFERENCE_ONLY"}], "requirements": {}}


def test_many_to_many_declared_relations_no_execution_claim():
    result = composition(development_relations(example()))
    graph = result["composition"]
    assert graph["semantics"] == "DECLARED_COMPOSITION_NOT_EXECUTION"
    assert len([r for r in graph["relations"] if r["label"].startswith("使用")]) == 2
    assert len([r for r in graph["relations"] if r["label"] == "实现"]) == 2
    skill = next(n for n in graph["nodes"] if n["kind"] == "SKILL")
    assert skill["unresolvedTools"] == ["missing"]
    assert not any(n["group"] == "contents" for n in graph["nodes"])
    assert "外壳" in result["files"][2]["guidance"]["title"]
    assert len({n["id"] for n in graph["nodes"]}) == len(graph["nodes"])


def test_no_skill_does_not_infer_host_method():
    data = example(); data["files"] = data["files"][1:]
    result = composition(development_relations(data))
    assert result["composition"]["skillStatus"] == "NO_INDEPENDENT_SKILL"
    assert not any(n["kind"] in {"SKILL", "HOST_METHOD"} for n in result["composition"]["nodes"])


def test_multiple_skills_keep_distinct_edges_and_files():
    data = example(); second = deepcopy(data["files"][0]); second["path"] = "methods/b/SKILL.md"
    second["content"] = "---\nallowed-tools: [b]\n---\n";data["files"].append(second)
    result = composition(development_relations(data))["composition"]
    assert len([n for n in result["nodes"] if n["kind"] == "SKILL"]) == 2
    assert len([r for r in result["relations"] if r["label"].startswith("使用")]) == 3


def test_published_content_and_references_not_conflated():
    data = example();data["kind"] = "PUBLISHED_PACKAGE";data["files"][0]["origin"] = "PACKAGE_FILE"
    nodes = composition(development_relations(data))["composition"]["nodes"]
    assert next(n for n in nodes if n["kind"] == "SKILL")["group"] == "contents"
    assert all(n["group"] == "dependencies" for n in nodes if n["kind"] == "CAPABILITY")


def test_dependency_versions_are_distinct_and_not_guessed():
    data = example()
    data['dependencies'].append({**data['dependencies'][0], 'version': '3'})
    graph = composition(development_relations(data))['composition']
    assert len({n['id'] for n in graph['nodes']}) == len(graph['nodes'])
    provider = next(n for n in graph['nodes'] if n['id'] == 'provider-reference:provider-a')
    assert provider['status'] == 'REFERENCE_ONLY'
    assert '歧义' in provider['modify']


def test_invalid_skill_metadata_does_not_hide_readable_files():
    data = example(); data['files'][0]['content'] = '---\ninvalid: [\n---\n# 方法'
    result = composition(development_relations(data))
    assert result['development']['skills'][0]['status'] == 'INVALID_DECLARATION'
    assert result['files'][0]['content'] == data['files'][0]['content']


def test_published_manifest_binding_is_scope_not_inferred_execution():
    data = example();data['kind'] = 'PUBLISHED_PACKAGE'
    data['files'][0]['content'] = '# No allowed-tools header'
    data['files'].append({'path':'finance-business-package.json','role':'MANIFEST','origin':'PACKAGE_FILE',
      'content':'{"skill":{"entry":"methods/a/SKILL.md"},"bindings":[{"capabilityId":"cap-a"},{"capabilityId":"cap-b"}]}'})
    edges = composition(development_relations(data))['composition']['relations']
    assert len([e for e in edges if e['label'] == '使用范围 · 发布绑定']) == 2
    assert not any(e['label'] == '使用 · 声明引用' for e in edges)


def test_domain_pack_scope_does_not_assign_each_skill_all_capabilities():
    data=example()
    data['files'][1]['content']='capabilities: [cap-a, cap-b]'
    data['dependencies'].append({'id':'method-pack','version':'1','kind':'DOMAIN_PACK','status':'AVAILABLE','filePath':'pack.yaml'})
    edges=composition(development_relations(data))['composition']['relations']
    scopes=[e for e in edges if e['label']=='声明可用能力']
    assert len(scopes)==2 and all(e['source']=='dependency:method-pack@1' for e in scopes)


def test_reading_groups_preserve_every_node_once_and_unknown_references():
    data=example()
    data['dependencies'].append({'id':'future-reference','version':'9','kind':'FUTURE_ASSET','status':'MISSING'})
    graph=composition(development_relations(data))['composition']
    grouped=[id for g in graph['readingGroups'] for id in g['memberIds']]
    assert len(grouped)==len(set(grouped))==len(graph['nodes'])
    assert set(grouped)=={n['id'] for n in graph['nodes']}
    assert 'dependency:future-reference@9' in next(g for g in graph['readingGroups'] if g['id']=='other')['memberIds']
    assert len(next(g for g in graph['readingGroups'] if g['id']=='providers')['memberIds'])==2
    assert all(e['source'] in grouped and e['target'] in grouped for e in graph['relations'])


def test_group_count_not_linear_with_files_and_origins_are_unchanged():
    data=example()
    data['files'] += [{'path':f'references/{i}.md','role':'REFERENCE','origin':'PACKAGE_FILE','content':'reference'} for i in range(40)]
    graph=composition(development_relations(data))['composition']
    assert len(graph['readingGroups'])<=8
    files=[n for n in graph['nodes'] if n['kind']=='REFERENCE']
    assert len(files)==40 and all(n['group']=='contents' for n in files)
    assert next(n for n in graph['nodes'] if n['kind']=='SKILL')['group']=='dependencies'


def test_no_independent_skill_retains_method_container_without_inventing_skill():
    data=example();data['files']=data['files'][1:]
    graph=composition(development_relations(data))['composition']
    assert next(g for g in graph['readingGroups'] if g['id']=='methods')['memberIds']==[]
    assert graph['skillStatus']=='NO_INDEPENDENT_SKILL'


def test_same_file_keeps_distinct_input_definitions_and_source_locations():
    data = example()
    data['files'][1]['content'] = '''capabilities:
  - capabilityId: cap-a
    description: Original description A
    inputSchema:
      required: [period]
  - capabilityId: cap-b
    description: Original description B
    inputSchema:
      required: [accountId]
'''
    data['bindings'][0].update(displayDescription='按指定月份查看收支', descriptionSource='CURRENT_CATALOG_PRESENTATION')
    graph = composition(development_relations(data))['composition']
    declaration = next(n for n in graph['nodes'] if n['kind'] == 'DECLARATION')
    assert declaration['label'] == '能力声明文件'
    assert declaration['definitionCount'] == 2
    assert '各自' in declaration['purpose']
    edges = [e for e in graph['relations'] if e['target'] == declaration['id']]
    assert len(edges) == 2 and all(e['label'] == '输入定义位于' for e in edges)
    a, b = [next(n for n in graph['nodes'] if n['id'] == 'capability:' + identity) for identity in ('cap-a', 'cap-b')]
    assert a['purpose'] == '按指定月份查看收支' and a['originalDescription'] == 'Original description A'
    assert a['declarationLocation']['startLine'] == 2
    assert b['declarationLocation']['startLine'] == 6
    assert a['declarationLocation']['endLine'] < b['declarationLocation']['startLine']
    assert 'period' in data['files'][1]['content'] and 'accountId' in data['files'][1]['content']


def test_independent_schema_does_not_become_a_declaration_file():
    data=example()
    data['files'].append({'path':'input.schema.json','role':'CONTRACT','origin':'REFERENCED_ASSET','content':'{"type":"object"}'})
    data['bindings'][0]['inputFile']='input.schema.json'
    graph=composition(development_relations(data))['composition']
    assert next(n for n in graph['nodes'] if n['id']=='file:input.schema.json')['kind']=='CONTRACT'
    assert next(e for e in graph['relations'] if e['source']=='capability:cap-a' and e['target']=='file:input.schema.json')['label']=='遵循 · 输入声明'


def qualified(data):
    reference = {'assetType': 'CAPABILITY_PACK', 'id': 'example', 'version': '1',
                 'source': 'package://example', 'workspaceId': 'fixture'}
    data['files'] = [file_view(f['path'], f['content'].encode(), f['origin'], f['role'], reference)
                     for f in data['files']]
    for binding in data['bindings']:
        binding['capabilityPack'] = reference
    return data


def test_graph_file_ids_preserve_paths_and_exact_capability_location():
    data = qualified(example())
    data['files'][1] = file_view('pack.yaml', b'capabilities:\n  - capabilityId: cap-a\n    description: First\n',
                                 'REFERENCED_ASSET', 'DECLARATION', data['files'][1]['reference'])
    result = composition(development_relations(data))
    files = {f['fileId']: f for f in result['files']}
    for node in result['composition']['nodes']:
        assert node['files'] == [files[key]['path'] for key in node['fileIds']]
    cap = next(n for n in result['composition']['nodes'] if n['kind'] == 'CAPABILITY' and n['reference']['capabilityId'] == 'cap-a')
    assert cap['declarationLocation']['fileId'] == cap['fileIds'][0]
    assert files[cap['fileIds'][0]]['content'].splitlines()[cap['declarationLocation']['startLine'] - 1].strip() == '- capabilityId: cap-a'


def test_same_path_distinct_sources_and_versions_keep_graph_nodes():
    data = qualified(example())
    original_graph = composition(development_relations(data))['composition']
    original_id = next(n['id'] for n in original_graph['nodes'] if n['kind'] == 'CAPABILITY' and n['reference']['capabilityId'] == 'cap-a')
    second = deepcopy(data['files'][1])
    second['reference'] = {**second['reference'], 'version': '2', 'source': 'package://second'}
    data['files'].append(second)
    binding = {**data['bindings'][0], 'capabilityPack': second['reference']}
    data['bindings'].append(binding)
    data['dependencies'] += [{'kind': 'CAPABILITY_PACK', 'status': 'AVAILABLE', 'filePath': 'pack.yaml', **f['reference']}
                             for f in (data['files'][1], second)]
    result = composition(development_relations(data))
    nodes, edges = result['composition']['nodes'], result['composition']['relations']
    caps = [n for n in nodes if n['kind'] == 'CAPABILITY' and n['reference']['capabilityId'] == 'cap-a']
    assert len(caps) == 2 and caps[0]['fileIds'] != caps[1]['fileIds']
    assert caps[0]['id'] == original_id
    assert caps[0]['files'] == caps[1]['files'] == ['pack.yaml']
    assert len(nodes) == len({n['id'] for n in nodes})
    assert all(e['source'] in {n['id'] for n in nodes} and e['target'] in {n['id'] for n in nodes} for e in edges)
    packs = [n for n in nodes if n['kind'] == 'CAPABILITY_PACK']
    assert len(packs) == 2 and all(len(n['fileIds']) >= 1 for n in packs)


def test_duplicate_file_references_do_not_duplicate_nodes_or_edges():
    data = qualified(example())
    data['files'] += deepcopy(data['files'])
    data['bindings'] += deepcopy(data['bindings'])
    data['dependencies'] += deepcopy(data['dependencies'])
    result = composition(development_relations(data))
    assert len(result['files']) == 3 and len(result['bindings']) == 2
    nodes = result['composition']['nodes']
    assert len(nodes) == len({n['id'] for n in nodes})


@pytest.mark.parametrize('second', ['capabilityId: cap-a', 'capabilityId: cap-a\n    description: Different'])
def test_duplicate_capability_definition_rejected_before_projection(second):
    data = example()
    data['files'][1]['content'] = 'capabilities:\n  - capabilityId: cap-a\n  - ' + second + '\n'
    with pytest.raises(AssetIdentityError, match='ASSET_DECLARATION_CONFLICT') as caught:
        composition(development_relations(data))
    assert caught.value.locations[0]['path'] == 'pack.yaml'


def test_conflicting_binding_of_same_declaration_rejected_before_node_creation():
    data = example()
    data['bindings'].append({**data['bindings'][0], 'providerId': 'another'})
    with pytest.raises(AssetIdentityError, match='ASSET_BINDING_CONFLICT'):
        composition(development_relations(data))


def test_provider_explicit_version_binding_resolves_only_that_version():
    data = example()
    data['dependencies'].append({**data['dependencies'][0], 'version': '3'})
    data['bindings'][0]['providerRef'] = {'id': 'provider-a', 'version': '3'}
    edges = composition(development_relations(data))['composition']['relations']
    assert {'source': 'dependency:provider-a@3', 'target': 'capability:cap-a', 'label': '实现'} in edges
    assert not any(e['source'] == 'dependency:provider-a@1' and e['label'] == '实现' for e in edges)


def test_provider_same_name_and_version_different_sources_stays_ambiguous():
    data = example()
    data['dependencies'][0]['source'] = 'maven://first:provider:1'
    data['dependencies'].append({**data['dependencies'][0], 'source': 'maven://second:provider:1'})
    graph = composition(development_relations(data))['composition']
    pending = next(n for n in graph['nodes'] if n['id'] == 'provider-reference:provider-a')
    assert pending['label'] == '待确定版本' and len(pending['reference']['candidates']) == 2
    assert not any(e['source'].startswith('dependency:provider-a') and e['label'] == '实现' for e in graph['relations'])


def test_provider_options_without_binding_show_pending_not_first():
    data = example()
    data['dependencies'].append({**data['dependencies'][0], 'version': '3'})
    data['bindings'][0]['providerId'] = None
    data['bindings'][0]['providerCandidates'] = [{'id': 'provider-a', 'version': v} for v in ('1', '3')]
    graph = composition(development_relations(data))['composition']
    assert next(n for n in graph['nodes'] if n['id'] == 'provider-reference:provider-a')['label'] == '待确定版本'
    assert not any(e['source'].startswith('dependency:provider-a') and e['label'] == '实现' for e in graph['relations'])


def test_duplicate_yaml_identity_key_rejected_before_safe_load_overwrite():
    data = example()
    data['files'][1]['content'] = 'capabilities:\n  - capabilityId: cap-a\n    capabilityId: cap-b\n'
    with pytest.raises(AssetIdentityError, match='ASSET_DECLARATION_CONFLICT') as caught:
        composition(development_relations(data))
    assert caught.value.locations == [{'path': 'pack.yaml', 'startLine': 3}]


def test_composition_repeated_preparation_keeps_ids_and_original_response():
    data = qualified(example())
    legacy = {'path': 'old.md', 'role': 'REFERENCE', 'origin': 'PACKAGE_FILE', 'content': 'old'}
    data['files'] = [*data['files'], *deepcopy(data['files']), legacy, legacy]
    original = deepcopy(data)
    result = composition(development_relations(data))
    assert data == original
    assert result == composition(development_relations(result))
