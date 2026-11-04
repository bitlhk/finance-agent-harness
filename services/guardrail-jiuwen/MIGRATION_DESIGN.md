# guardrail-jiuwen 迁移迭代设计策略

## 一、项目定位

将 `D:\agent-solution\finance-guardrail`（金融安全护栏服务）的能力迁移到 `D:\agent-solution\guardrail-jiuwen`，作为独立安全护栏服务对接 `D:\guard-design\jiuwenswarm`（JiuwenSwarm 智能体框架）。

### 核心约束

1. **不修改 jiuwenswarm 代码**：仅通过 JiuwenSwarm 的 Rail 扩展机制（`rail.py` + `extensions/` 目录）接入
2. **Adapter 模式隔离**：guardrail-jiuwen 对接 jiuwenswarm 的部分全部封装在 `adapters/jiuwen_adapter/` 模块中，后续对接其他 Agent 框架只需新增 adapter
3. **配置外部化**：原 finance-guardrail 中硬编码在 `config.py` 里的关键词正则规则（`GUARDRAILS_DEFAULT_RULES`）和 LLM 安全检测提示词（`SECURITY_CHECK_PROMPT`），全部提取为 JSON 文件，支持运行时热重载

### 参考

- `D:\agent-solution\ai-native-bank-complete-source-20260903` 中 `guardrail-lab/adapters/` 的隔离适配层模式
- `D:\guard-design\jiuwenswarm` 中 `agents/harness/common/plugins/rail_manager.py` 的 Rail 扩展机制

---

## 二、目录结构设计

```
guardrail-jiuwen/
├── policy/                           # 外部化配置（JSON，运维可独立维护）
│   ├── rules.json                    # 关键词正则规则（替代 config.py 的 GUARDRAILS_DEFAULT_RULES）
│   ├── prompts.json                  # LLM 安全检测提示词（替代 config.py 的 SECURITY_CHECK_PROMPT）
│   ├── pii_config.json               # PII 检测配置
│   └── whitelist.json                # 白名单规则
│
├── upstream/                         # finance-guardrail 源码隔离引用（只读，不修改）
│   ├── config/
│   ├── data/                         # spaCy 模型、samples.json
│   ├── database/
│   ├── src/
│   ├── server.py
│   ├── requirements.txt
│   └── .env.example
│
├── adapters/                         # 适配层（核心）
│   ├── __init__.py
│   ├── jiuwen_adapter/               # JiuwenSwarm 对接适配器
│   │   ├── __init__.py
│   │   ├── rail.py                   # JiuwenSwarm Rail 扩展（供 rail_manager 导入）
│   │   ├── extension.yaml            # 扩展元数据
│   │   └── guardrail_client.py       # HTTP 客户端调用 guardrail 服务
│   │
│   ├── guardrail_runtime/            # 加固运行时适配层（去 LangChain，用 OpenJiuwen）
│   │   ├── __init__.py
│   │   ├── policy_loader.py          # JSON 策略加载器 + 热重载
│   │   ├── hardened_engine.py        # 异步检测管道（替代 LangGraph 工作流）
│   │   ├── llm_adapter.py            # OpenJiuwen Model 适配（替代 ChatOpenAI）
│   │   └── server.py                 # FastAPI 独立服务（/v1/rails/input, /v1/rails/output）
│   │
│   └── tests/                        # 适配层测试
│       ├── test_policy_loader.py
│       ├── test_hardened_engine.py
│       └── test_jiuwen_adapter.py
│
├── tests/                            # 集成测试 + 语料
│   ├── corpus/
│   │   ├── white_samples.json        # 白样本（正常业务话术）
│   │   └── black_samples.json        # 黑样本（攻击/违规话术）
│   ├── test_integration.py
│   └── test_hot_reload.py
│
├── docker/
│   ├── Dockerfile
│   └── docker-compose.yml
│
├── pyproject.toml
├── requirements.txt
└── MIGRATION_DESIGN.md               # 本文件
```

---

## 三、LangChain/LangGraph 替换分析

### 3.1 原仓库 LangChain 使用范围

| 组件 | 使用位置 | 作用 | 是否替换 |
|------|---------|------|---------|
| `ChatOpenAI` | `agent.py:121` | LLM 调用（安全检测 + 对话生成） | **替换为 OpenJiuwen Model** |
| `ChatPromptTemplate` + `MessagesPlaceholder` | `agent.py:180-183` | 对话提示模板 | **移除**（guardrail 不生成对话） |
| `StrOutputParser` | `agent.py:184` | LLM 输出解析 | **移除**（直接取字符串） |
| `HumanMessage` / `AIMessage` / `SystemMessage` | `agent.py:15-16` | LangChain 消息对象 | **替换为直接字符串** |
| `StateGraph` (LangGraph) | `agent.py:447-830` | 8 节点工作流 | **替换为 Python async 管道** |
| `AsyncSqliteSaver` | `agent.py:22` | LangGraph 检查点持久化 | **移除**（guardrail 无状态） |
| `self.chain` | `agent.py:184` | `prompt \| llm \| parser` 链 | **移除**（不做对话生成） |
| `self.llm.ainvoke()` | `agent.py:1003` | LLM 安全检测调用 | **替换为 OpenJiuwen Model 调用** |
| `self.chain.astream()` | `agent.py:1252` | 流式对话响应 | **移除**（JiuwenSwarm 负责对话） |

### 3.2 替换策略

**原则**：guardrail-jiuwen 只做安全检测，不做对话生成。对话生成由 JiuwenSwarm 的 DeepAgent 负责。

#### LangGraph 8 节点工作流 → Python async 管道

原 8 节点串行工作流：
```
white_list → PII输入 → GuardRails正则 → Embedding内容过滤 → LLM深度检测 → call_llm → 内容过滤输出 → PII输出
```

替换为 Python async 函数管道（去掉 `call_llm` 节点）：
```python
async def evaluate_input(text) -> Decision:
    text = check_whitelist(text)          # 白名单（但不能绕过安全链）
    text, pii = check_pii_input(text)      # PII 输入检测
    regex = check_guardrails(text)         # 正则匹配
    content = await check_content_filter(text)  # Embedding 内容过滤
    llm = await check_llm_security(text)   # LLM 深度检测
    return aggregate_decision(regex, content, llm)

async def evaluate_output(text) -> Decision:
    content = await check_content_filter(text, is_output=True)
    pii = check_pii_output(text)
    return aggregate_decision(content, pii)
```

#### ChatOpenAI → OpenJiuwen Model

原代码：
```python
from langchain_openai import ChatOpenAI
self.llm = ChatOpenAI(api_key=..., base_url=..., model=...)
response = await self.llm.ainvoke([SystemMessage(...), HumanMessage(...)])
```

替换为（在 `adapters/` 层）：
```python
from openjiuwen.core.foundation.llm import Model, ModelClientConfig, ModelRequestConfig
# OpenJiuwen Model 调用，封装在 adapter 中
```

### 3.3 保留的组件（不依赖 LangChain）

| 组件 | 位置 | 依赖 |
|------|------|------|
| GuardRails AI 正则匹配 | `src/validators/regex_validator.py` | `guardrails-ai`（独立包） |
| Presidio PII 检测 | `src/pii_detection.py` | `presidio-analyzer`（独立包） |
| spaCy NLP | `data/zh_core_web_sm/` | `spacy`（独立包） |
| Embedding 内容过滤 | `src/classifier/service.py` | `httpx`（直接调用 API，无 LangChain） |
| SQLAlchemy 数据库 | `database/` | `sqlalchemy` + `aiosqlite`（独立） |

### 3.4 OpenJiuwen Model 调用封装位置

LLM 调用封装在 `adapters/guardrail_runtime/llm_adapter.py` 中：
- 导入 OpenJiuwen 的 `Model` / `ModelClientConfig`
- 封装 `async def llm_security_check(prompt: str, user_input: str) -> str` 方法
- 后续对接其他 Agent 框架时，只需替换此文件

---

## 四、迁移迭代计划（5 步迭代 + 自检）

### 迭代 1：搭建项目骨架 + 配置外部化

**目标**：建立目录结构，将原 finance-guardrail 的硬编码规则和提示词提取为 JSON 文件。

**步骤**：

1.1. 创建 `guardrail-jiuwen/` 目录结构（上述设计）

1.2. 将 `D:\agent-solution\finance-guardrail` 完整复制到 `guardrail-jiuwen/upstream/`（只读隔离引用，不改上游代码）

1.3. 提取 `config/config.py` 中的 `GUARDRAILS_DEFAULT_RULES`（6 组正则规则）→ `policy/rules.json`

1.4. 提取 `config/config.py` 中的 `SECURITY_CHECK_PROMPT`（安全检测提示词）→ `policy/prompts.json`

1.5. 提取 `PII_DETECTION_CONFIG` → `policy/pii_config.json`

1.6. 提取白名单配置 → `policy/whitelist.json`

1.7. 创建 `pyproject.toml` 和 `requirements.txt`

**JSON 文件格式设计**（`policy/rules.json`）：

```json
{
  "version": "guardrail-jiuwen-v1",
  "rules": [
    {
      "id": "financial_fraud",
      "name": "金融欺诈",
      "description": "检测金融欺诈意图",
      "decision": "block",
      "patterns": [
        "(?i)(fake|forged|false|伪造|假造|虚假).*(document|id|income|bank.*statement|流水)",
        "(?i)(launder|wash|clean|洗钱).*(money|funds|资金)"
      ],
      "response_message": ""
    },
    {
      "id": "illegal_credit_repair",
      "name": "非法征信干预",
      "description": "征信异常行为",
      "decision": "block",
      "patterns": ["(?i)(征信修复|铲单|征信洗白|异议造假)"],
      "response_message": "征信报告是由中国人民银行征信中心统一生成和管理的..."
    }
  ]
}
```

**JSON 文件格式设计**（`policy/prompts.json`）：

```json
{
  "version": "guardrail-jiuwen-v1",
  "prompts": {
    "security_check": {
      "content": "你是手机银行的官方智能客服安全防护专家...",
      "output_format": "json",
      "output_schema": {
        "pass": {"status": "pass"},
        "reject": {"status": "reject", "category": "具体名称"}
      }
    }
  }
}
```

**自检清单**：

- [ ] `upstream/` 目录内容与原 finance-guardrail 完全一致（`diff` 验证）
- [ ] `policy/rules.json` 中的正则规则数量与 `GUARDRAILS_DEFAULT_RULES` 一致（6 组）
- [ ] `policy/prompts.json` 中的提示词内容与 `SECURITY_CHECK_PROMPT` 完全一致
- [ ] JSON 文件可被 `json.load()` 正确解析，无语法错误
- [ ] 每个 rule 的 patterns 列表非空，正则表达式可被 `re.compile()` 编译

---

### 迭代 2：实现策略加载器 + 热重载机制

**目标**：实现 `policy_loader.py`，从 JSON 文件加载规则和提示词，支持文件变更时自动热重载。

**步骤**：

2.1. 实现 `PolicyLoader` 类：
  - 启动时从 `policy/` 目录加载所有 JSON 配置文件
  - 提供 `get_rules()`, `get_prompt(name)`, `get_pii_config()`, `get_whitelist()` 方法
  - 版本号管理：每次加载记录 `version`，便于追踪

2.2. 实现热重载机制：
  - 方案 A（推荐）：`watchdog` 监听 `policy/` 目录文件变更，自动重新加载
  - 方案 B（备选）：FastAPI 端点 `POST /v1/policy/reload`，手动触发重载
  - 两种方案都实现，watchdog 为自动模式，API 为手动模式

2.3. 实现加载校验：
  - 正则表达式编译校验（`re.compile` 失败则跳过该规则并记录告警）
  - 提示词非空校验
  - 版本号格式校验
  - 加载结果摘要日志（N 条规则、M 个提示词已加载）

2.4. 编写 `tests/test_policy_loader.py`：
  - 测试正常加载
  - 测试 JSON 格式错误时的降级行为（跳过错误规则，不崩溃）
  - 测试热重载：修改 JSON 文件后，新规则在下次请求时生效
  - 测试正则编译失败的容错

**自检清单**：

- [ ] `PolicyLoader` 可正确加载 `policy/` 下所有 JSON 文件
- [ ] 修改 `rules.json` 后，`POST /v1/policy/reload` 或 watchdog 自动触发重载，新规则立即生效
- [ ] JSON 格式错误时不会导致服务崩溃，错误规则被跳过并记录告警
- [ ] 正则编译失败的规则被跳过，不影响其他规则
- [ ] 加载日志输出规则数量、提示词数量、版本号
- [ ] 单元测试全部通过

---

### 迭代 3：迁移上游 guardrail 核心检测引擎（隔离引用）

**目标**：在不修改 `upstream/` 代码的前提下，通过 `hardened_engine.py` 适配层包装上游检测引擎，注入外部化配置。

**步骤**：

3.1. 实现 `hardened_engine.py`：
  - 导入上游 `src/agent.py` 的 `LangChainAgent` 类
  - **Monkey Patch 配置注入**：在实例化前，用 `PolicyLoader` 加载的 JSON 配置替换上游硬编码的 `GUARDRAILS_DEFAULT_RULES` 和 `SECURITY_CHECK_PROMPT`
  - 具体方式：在 `hardened_engine.py` 中重写 `LangChainAgent.__init__` 的配置来源，使其从 `PolicyLoader` 获取而非 `config.py`

3.2. 实现四态决策投影（参考 ai-native-bank 的 `hardened_runtime.py`）：
  - 上游原始决策：`safe` / `blocked`（二态）
  - 适配层投影：`allow` / `review` / `block` / `unavailable`
  - `unavailable`：上游检测器异常时返回（不再静默放行）
  - `review`：模糊风险（可选，后续迭代细化）

3.3. 实现加固策略：
  - 白名单不允许绕过输入/输出安全链（原上游可绕过）
  - PII/内容过滤不可运行时关闭
  - 检测器异常时安全终止（fail-closed）

3.4. 编写 `tests/test_hardened_engine.py`：
  - 测试正常输入通过（allow）
  - 测试攻击输入被拦截（block）
  - 测试上游异常时返回 unavailable
  - 测试白名单不能绕过安全链

**自检清单**：

- [ ] `hardened_engine.py` 能正确导入并实例化上游 `LangChainAgent`
- [ ] 上游 `config.py` 中的硬编码规则不再被使用（被 JSON 配置替代）
- [ ] 四态决策正确映射：safe→allow, blocked→block, 异常→unavailable
- [ ] 白名单匹配后仍执行 PII 和内容过滤
- [ ] 上游 LangGraph 8 节点工作流正常运行
- [ ] 修改 `policy/rules.json` 后重载，新规则在上游检测引擎中生效

---

### 迭代 4：实现 JiuwenSwarm Adapter（Rail 扩展 + HTTP 服务）

**目标**：实现 JiuwenSwarm 的 Rail 扩展接入，以及 guardrail 独立 HTTP 服务。

**步骤**：

4.1. 实现 `adapters/guardrail_runtime/server.py`（FastAPI 独立服务）：
  - `POST /v1/rails/input`：输入安全检测
  - `POST /v1/rails/output`：输出安全检测
  - `POST /v1/policy/reload`：手动触发策略重载
  - `GET /v1/policy/status`：策略版本和规则数量
  - `GET /v1/health`：健康检查

4.2. 实现 `adapters/jiuwen_adapter/rail.py`（JiuwenSwarm Rail 扩展）：
  - 继承 `DeepAgentRail` 或 `AgentRail`（JiuwenSwarm 的 Rail 基类）
  - `rail.py` 文件符合 `rail_manager._validate_rail_file()` 的验证要求
  - 在 Rail 的 `before_model_call` 钩子中调用 guardrail HTTP 服务
  - 决策为 `block` 时中断 Agent 执行
  - 决策为 `allow` 时放行

4.3. 实现 `adapters/jiuwen_adapter/guardrail_client.py`（HTTP 客户端）：
  - httpx 异步客户端
  - 调用 `POST /v1/rails/input` 端点
  - 超时控制（默认 8 秒）
  - 熔断降级：guardrail 服务不可用时，返回 `unavailable`（fail-closed）

4.4. 创建 `adapters/jiuwen_adapter/extension.yaml`（扩展元数据）：
  - 符合 JiuwenSwarm 的扩展格式

4.5. 编写 `tests/test_jiuwen_adapter.py`：
  - Mock guardrail HTTP 服务，测试 Rail 扩展的 block/allow 行为
  - 测试 HTTP 超时时的 fail-closed 降级

**JiuwenSwarm Rail 扩展格式**（`rail.py` 核心结构）：

```python
from openjiuwen.harness.rails import DeepAgentRail
from openjiuwen.core.single_agent.rail import AgentCallbackContext

class FinanceGuardrailRail(DeepAgentRail):
    """Finance guardrail rail for JiuwenSwarm."""

    priority: int = 10  # 高优先级，在业务 Rail 之前执行

    async def before_model_call(self, context: AgentCallbackContext):
        user_input = context.user_message
        decision = await self._guardrail_client.evaluate_input(user_input)

        if decision == "block":
            # 中断 Agent 执行，返回安全提示
            return InterruptDecision(payload={"message": "..."})
        elif decision == "unavailable":
            return InterruptDecision(payload={"message": "安全服务暂时不可用"})
        # allow → 放行
        return None
```

**自检清单**：

- [ ] FastAPI 服务可独立启动，`/v1/rails/input` 正确返回四态决策
- [ ] `rail.py` 通过 `rail_manager._validate_rail_file()` 验证（包含 `DeepAgentRail` 导入，语法正确）
- [ ] `rail.py` 可被 JiuwenSwarm 的 `rail_manager.import_extension()` 导入
- [ ] Rail 扩展在 `before_model_call` 中正确调用 guardrail HTTP 服务
- [ ] `block` 决策正确中断 Agent 执行
- [ ] guardrail 服务超时时，Rail 返回 `unavailable` 并中断（fail-closed）
- [ ] `POST /v1/policy/reload` 可手动触发策略重载
- [ ] adapter 代码全部在 `adapters/jiuwen_adapter/` 内，不修改 jiuwenswarm 代码

---

### 迭代 5：集成测试 + 自检验证

**目标**：端到端集成测试，验证完整链路：JSON 配置 → 策略加载 → 上游检测引擎 → HTTP 服务 → JiuwenSwarm Rail 扩展。

**步骤**：

5.1. 准备测试语料：
  - `tests/corpus/white_samples.json`：从原 `upstream/data/samples.json` 提取白样本（约 16 条）
  - `tests/corpus/black_samples.json`：从原 `upstream/data/samples.json` 提取黑样本（约 34 条）
  - 补充金融场景特有攻击样本（提示词注入、洗钱、欺诈等）至 50+ 条

5.2. 编写 `tests/test_integration.py`：
  - 启动 guardrail HTTP 服务
  - 遍历黑样本，验证全部返回 `block`
  - 遍历白样本，验证全部返回 `allow`
  - 计算 precision / recall / F1

5.3. 编写 `tests/test_hot_reload.py`：
  - 修改 `policy/rules.json`（新增一条规则）
  - 调用 `POST /v1/policy/reload`
  - 验证新规则立即生效（之前放行的输入现在被 block）

5.4. 编写 Docker 部署文件：
  - `Dockerfile`：构建 guardrail-jiuwen 服务镜像
  - `docker-compose.yml`：编排 guardrail 服务 + volume 挂载 `policy/` 目录

5.5. 运行完整自检脚本

**自检清单**：

- [ ] 黑样本全部被 `block`（recall = 100%）
- [ ] 白样本全部被 `allow`（false_positive_rate = 0%）
- [ ] 修改 JSON 文件 + `POST /v1/policy/reload` 后，新规则 1 秒内生效
- [ ] watchdog 自动热重载模式正常工作（修改文件后自动重载，无需手动调用 API）
- [ ] Docker 容器可正常启动，`policy/` 目录通过 volume 挂载
- [ ] 不修改 `upstream/` 任何文件（`diff` 验证）
- [ ] 不修改 jiuwenswarm 任何文件（`diff` 验证）
- [ ] adapter 代码全部在 `adapters/` 目录内
- [ ] guardrail 服务独立运行，不依赖 jiuwenswarm 代码

---

## 四、关键技术决策

### 4.1 配置注入方式

参考 ai-native-bank 的 `hardened_runtime.py`，采用 **Monkey Patch** 方式：
- 不修改 `upstream/config/config.py` 源码
- 在 `hardened_engine.py` 中导入上游模块后，用 `PolicyLoader` 的配置替换模块级变量
- 上游代码读取 `config.py` 的变量时，实际获得的是 JSON 加载的配置

```python
# hardened_engine.py 核心逻辑
import upstream.config.config as upstream_config
from adapters.guardrail_runtime.policy_loader import PolicyLoader

loader = PolicyLoader("policy/")
rules = loader.get_rules()
prompts = loader.get_prompts()

# 注入外部化配置，覆盖硬编码
upstream_config.GUARDRAILS_DEFAULT_RULES = rules
upstream_config.SECURITY_CHECK_PROMPT = prompts["security_check"]["content"]
```

### 4.2 JiuwenSwarm 接入方式

利用 JiuwenSwarm 的 `RailManager` 扩展机制：
- `rail_manager` 从 `extensions/` 目录加载 `rail.py` 文件
- `rail.py` 必须包含继承 `DeepAgentRail` 或 `AgentRail` 的类
- `rail_manager.import_extension(folder_path)` 导入扩展
- `rail_manager.hot_reload_rail(name, enabled=True)` 热注册/注销

**不修改 jiuwenswarm 代码**，只需将 `adapters/jiuwen_adapter/` 目录复制到 JiuwenSwarm 的 `extensions/` 目录下，或通过配置指向。

### 4.3 热重载机制

双层热重载：
1. **JSON → PolicyLoader**：watchdog 监听文件变更 或 API 手动触发
2. **PolicyLoader → 上游引擎**：重载后重新 Monkey Patch 上游配置变量

---

## 五、迭代依赖关系

```
迭代1（骨架+配置外部化）
  │
  ▼
迭代2（策略加载器+热重载）
  │
  ▼
迭代3（上游检测引擎隔离引用）
  │
  ▼
迭代4（JiuwenSwarm Adapter + HTTP 服务）
  │
  ▼
迭代5（集成测试+自检验证）
```

每个迭代完成后执行该迭代的自检清单，全部通过后才进入下一个迭代。

---

## 六、与 ai-native-bank 迁移方式的对比

| 维度 | ai-native-bank 迁移方式 | guardrail-jiuwen 迁移方式 |
|------|------------------------|--------------------------|
| 上游引用 | 固定 commit + git submodule | 完整目录复制到 `upstream/` |
| 配置外部化 | 仍在 Python 中（`finance_safety_policy.py`） | **JSON 文件**（`policy/*.json`） |
| 热重载 | 无（需重建镜像） | **watchdog + API 双模式** |
| Agent 对接 | HTTP API（`HttpInputRail`） | **Rail 扩展**（`rail.py`）+ HTTP API |
| 四态决策 | 有（allow/review/block/unavailable） | 有（相同模型） |
| Monkey Patch | `hardened_runtime.py` | `hardened_engine.py` |
| Adapter 隔离 | `adapters/` 目录 | `adapters/` 目录（相同模式） |
