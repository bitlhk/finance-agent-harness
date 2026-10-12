# OpenJiuwen Python Candidate 接入与运行

## 1. 适用范围

本指南面向维护 Python Candidate 模板、部署 Builder 以及接入 Finance Harness Adapter 的人员。
本文所称 Python Backend 是 `OpenJiuwenPythonZipAppBackend`（backend id
`openjiuwen-python`）：它为标准 Solution 生成确定性 ZipApp，北向使用 A2A，并与 Java Backend 使用
同一套 Candidate 生命周期和 Evaluation。它不会在构建时下载依赖，也不会在缺少 Python 资产时回退到 Java。

仓库另有 `OpenJiuwenPythonBackend`（backend id `openjiuwen-python-finance`），专门服务于
Studio/local-read-only 外部服务路径，产物为 `directory-manifest`，调用协议为 `finance-chat`。两者的
产物、协议和构造入口不同，Builder 按 Solution 路径显式选择，不能互相回退或替换。

## 2. Solution 与模板前置

Solution 必须显式声明：

```yaml
runtime:
  family: openjiuwen
  language: python
  templateRef: <已固定版本的 Python Candidate 模板>
  integrationRef: <已固定版本的 canonical-json-http 集成资产>
```

模板根目录必须包含 `python-candidate-template.json`：

```json
{
  "schemaVersion": "openjiuwen-python-template.v1",
  "moduleEntrypoint": "candidate_service:main",
  "artifactName": "candidate.pyz",
  "pythonRequires": ">=3.11",
  "openjiuwenVersion": "0.1.16",
  "runtimeServiceVersion": "0.1.0",
  "healthPath": "/health",
  "invocationPath": "/a2a",
  "harnessTransport": "canonical-json-http.v1"
}
```

字段含义：

- `moduleEntrypoint`：ZipApp 的唯一启动函数，格式为 `模块:函数`。
- `artifactName`：交付目录内的单文件 `.pyz` 产物名。
- `pythonRequires`：受控解释器必须满足的最低 Python 版本。
- `openjiuwenVersion`、`runtimeServiceVersion`：受控解释器必须已安装的精确版本。
- `healthPath`、`invocationPath`：Candidate 北向健康检查和 A2A 路径。
- `harnessTransport`：Candidate 南向访问 Finance Harness Adapter 的合同版本。

## 3. 构建和运行流程

1. Builder 解析并锁定 Solution、Domain/Capability Pack、Provider、Schema/Fixture、模板和集成资产。
2. Python Backend 在替换旧输出前检查资产闭包、Provider 覆盖、模板字段、集成资产声明和受控解释器中的依赖版本。
3. Backend 把已锁定资产装入 Candidate，检查所有 Python 源码并生成确定性 ZipApp。
4. Builder 校验 ZipApp 与 `candidate-descriptor.json` 中的 SHA-256 一致后，才允许受管运行时启动。
5. 运行时使用受控 Python 解释器执行 `.pyz`，先检查 `/health`，再通过 `/a2a` 执行协议探针或冻结 Evaluation。
6. Candidate 通过部署环境提供的 Adapter 地址访问 Finance Harness Canonical JSON/HTTP；Provider Adapter
   再对接银行 CRM、持仓、产品或交易系统。
7. Builder 收集生命周期证据并停止自己创建的进程，最终写入 Lock、Descriptor 和 Build Report。

## 4. 失败边界

- 模板缺失、入口不合法、依赖版本漂移、Provider 覆盖不完整或集成资产缺失：`preflight` 阻断，旧输出不被替换。
- Python 源码无法编译或 ZipApp 校验失败：`build` 失败，不生成可启动 Descriptor。
- Artifact/Descriptor/Lock 身份不一致：`start` 失败。
- 健康超时、A2A 非法响应或 Harness Adapter 不可达：Evaluation 返回失败或不可判定，并清理受管进程。
- Adapter 地址、银行凭据和授权客户信息只能由部署环境注入，不能写入模板、Solution、Lock、Report 或证据。

## 5. 代码验证

在 `community/finance/builder/finance-solution-builder` 目录运行：

```text
python -m pytest -p no:cacheprovider tests/candidate_backend/test_openjiuwen_python_zipapp.py
python -m pytest -p no:cacheprovider tests/candidate_backend/test_python_zipapp_runtime.py
python -m pytest -p no:cacheprovider tests/evaluation/test_runtime.py tests/candidate_backend/test_conformance.py
```

测试夹具只证明确定性装配、生命周期、A2A 和统一 Evaluation 合同。真实银行业务验收仍需获批的脱敏数据、
可访问的 Harness Adapter、Provider 测试身份和冻结验收规则；夹具通过不能替代该结果。

## 6. 版本升级

升级 OpenJiuwen、Runtime Service 或 Harness transport 时，先发布新的固定模板/集成资产，再更新模板声明；
随后重新执行预检、确定性打包、完整生命周期、Canonical JSON/HTTP conformance、统一 Evaluation 和隐私测试。
旧资产组合继续由原 Lock 重现，不在运行时自动漂移。
