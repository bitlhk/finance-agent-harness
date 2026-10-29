# guardrail-jiuwen — 智能体安全防护服务

基于 FastAPI + LangChain + GuardRails AI 的智能体输入输出安全检测服务，通过 JiuwenSwarm Rail 扩展机制接入。

## 架构

```
JiuwenSwarm Agent
  ├── before_model_call ──HTTP──▶ guardrail FastAPI (9876)
  │     (rail.py)                     ├─ 内容过滤 (Embedding 分类)
  │                                   ├─ 正则匹配 (rules.json)
  │                                   ├─ LLM 深度检测 (Qwen3.5-9B)
  │                                   └─ PII 检测 (Presidio)
  ├── after_model_call  ──HTTP──▶ guardrail FastAPI (9876)
  │     (rail.py)                     └─ 同上（输出方向）
  └── 拦截: AbortError + 标准话术
      脱敏: processed_text 回写 ctx
```

## 目录结构

```
guardrail-jiuwen/
├── .env.example              # 环境变量模板（复制为 .env 填入实际值）
├── .gitignore
├── _toggle_rail.py           # Rail 热加载脚本（路径自动检测）
├── adapters/
│   ├── README.md             # Rail 扩展详细文档
│   └── jiuwen_adapter/       # JiuwenSwarm Rail 扩展
│       ├── __init__.py       # Monkey-patch: 修复 RailManager 缓存
│       ├── extension.json    # 扩展注册配置（priority=90）
│       ├── rail.py           # before/after_model_call 实现
│       └── guardrail_client.py
├── upstream/
│   ├── server.py             # FastAPI 服务入口
│   ├── start.bat             # Windows 启动脚本
│   ├── requirements.txt      # Python 依赖
│   ├── config/
│   │   └── config.py         # 配置（BASE_DIR 自动定位，无需硬编码）
│   ├── src/
│   │   ├── agent.py          # LangChain Agent
│   │   └── pii_detection.py  # PII 检测服务
│   └── data/
│       ├── zh_core_web_sm/   # spaCy 中文模型
│       └── en_core_web_sm/   # spaCy 英文模型
└── policy/
    ├── response_templates.json   # 拦截话术模板
    └── pii_config.json           # PII 检测策略
```

## 部署步骤

### 1. 克隆仓库

```bash
git clone https://github.com/masterchubb-ctrl/guardrail-jiuwen.git guardrail-jiuwen
cd guardrail-jiuwen
```

### 2. 配置大模型 API

所有模型配置统一在 `.env` 文件中，代码通过环境变量读取，不在任何源码中硬编码。

```bash
# 复制模板
copy .env.example .env
# 编辑 .env，填入实际的 API key、模型地址和模型名
```

**`.env` 配置项（完整列表）：**

| 变量 | 说明 | 示例 | 必填 |
|---|---|---|---|
| `OPENAI_API_KEY` | LLM 安全检测模型的 API Key | `sk-xxxx` | 是 |
| `OPENAI_BASE_URL` | LLM API 地址（OpenAI 兼容接口） | `https://api.siliconflow.cn/v1` | 是 |
| `OPENAI_MODEL` | LLM 模型名（用于深度安全检测） | `Qwen/Qwen3.5-9B` | 是 |
| `EMBEDDING_BASE_URL` | Embedding API 地址（内容过滤用） | `https://api.siliconflow.cn/v1` | 是 |
| `EMBEDDING_MODEL` | Embedding 模型名 | `BAAI/bge-large-zh-v1.5` | 是 |
| `EMBEDDING_API_KEY` | Embedding API Key（不设则复用 `OPENAI_API_KEY`） | `sk-xxxx` | 否 |
| `GUARDRAIL_ENABLE_PII` | PII 检测总开关（engine 级别） | `true` | 否 |
| `GUARDRAIL_API_KEY` | FastAPI 服务鉴权 Key（留空不启用） | `my-secret-key` | 否 |

> **配置说明**：
> - `OPENAI_*` 三个变量控制 LLM 深度安全检测，支持任何 OpenAI 兼容 API
> - `EMBEDDING_*` 变量控制内容过滤的 Embedding 分类，可与 LLM 使用同一服务
> - `GUARDRAIL_ENABLE_PII` 是 engine 级总开关，`policy/pii_config.json` 中的 `enabled` 是策略级开关，两者都为 `true` 时 PII 才生效
> - `GUARDRAIL_API_KEY` 用于生产部署保护，留空则不启用鉴权

### 3. 安装依赖

```bash
cd upstream
pip install -r requirements.txt
```

> **注意：** `spacy-pkuseg` 要求 `numpy==1.26.4`，不能使用 numpy 2.x。

### 4. 启动 guardrail 服务

```bash
cd upstream
python server.py
```

或使用启动脚本：

```bash
upstream\start.bat
```

服务默认监听 `http://localhost:9876`。验证：

```bash
curl http://localhost:9876/v1/health
# {"status": "ok"}
```

### 5. 被 JiuwenSwarm 加载

确保 JiuwenSwarm 已运行（`ws://127.0.0.1:19000/ws` 可连接），然后：

```bash
# 在 guardrail-jiuwen 根目录执行
python _toggle_rail.py
```

脚本自动检测 `adapters/jiuwen_adapter` 目录路径，无需手动修改。执行流程：

1. `extensions.toggle(off)` — 关闭旧 Rail
2. `extensions.delete` — 清除缓存
3. `extensions.import` — 从自动检测的路径导入
4. `extensions.toggle(on)` — 在当前 Agent 实例上注册

成功输出：

```
--- verify ---
  jiuwen_adapter: enabled=True, loaded=True
Done! Try sending a message in JiuwenSwarm now.
```

### 6. 验证拦截

在 JiuwenSwarm 对话框输入：

```
推荐我稳赚不赔的理财产品
```

预期被拦截：

```
错误: [110600] callback execution aborted: [guardrail] 安全风险提示：检测到金融风险，请换一个话题。
```

检查 Rail debug log 确认被调用：

```bash
type adapters\jiuwen_adapter\_rail_debug.log
```

## 路径适配说明

本项目的所有路径均通过相对定位自动适配，**无需修改任何硬编码路径**：

| 组件 | 路径方式 | 说明 |
|---|---|---|
| `config.py` | `Path(__file__).parent.parent` | 相对于 config.py 所在位置 |
| `rail.py` | `os.path.dirname(__file__)` | 相对于 rail.py 所在位置 |
| `_toggle_rail.py` | `os.path.dirname(os.path.abspath(__file__))` | 自动检测脚本所在目录 |
| `start.bat` | `cd /d "%~dp0"` | 相对于 bat 文件位置 |

因此，无论把项目克隆到哪个目录（如 `D:\agent-solution\guardrail-jiuwen`、`C:\projects\guardrail-jiuwen` 等），都能直接运行，无需修改代码。

## 环境变量参考

| 变量 | 默认值 | 说明 |
|---|---|---|
| `GUARDRAIL_SERVICE_URL` | `http://localhost:9876` | guardrail 服务地址 |
| `GUARDRAIL_TIMEOUT` | `35.0` | HTTP 调用超时（秒） |
| `GUARDRAIL_FAIL_CLOSED` | `0` | `1`=服务不可用时拦截，`0`=放行 |
| `GUARDRAIL_PUSH_RESPONSE_MESSAGE` | `1` | `1`=推送标准话术，`0`=通用话术 |
| `GUARDRAIL_ENABLE_PII` | `true` | PII 检测总开关 |
| `GUARDRAIL_ENABLE_CONTENT_FILTER` | `true` | 内容过滤开关 |
| `GUARDRAIL_ENABLE_LLM_DEFENSE` | `true` | LLM 深度检测开关 |
| `GUARDRAIL_AUTO_RELOAD` | `true` | 策略文件热重载 |

## API 端点

| 端点 | 方法 | 用途 |
|---|---|---|
| `/v1/rails/input` | POST | 输入安全检测 |
| `/v1/rails/output` | POST | 输出安全检测 |
| `/v1/policy/reload` | POST | 手动触发策略热重载 |
| `/v1/policy/status` | GET | 查看策略版本和规则数 |
| `/v1/health` | GET | 健康检查 |

## 检测能力

### 输入检测（before_model_call）

1. **内容过滤** — Embedding 零样本分类，7 类：仇恨/辱骂/色情/暴力/违法/提示词攻击/政治敏感
2. **正则匹配** — rules.json 中 7 条规则：金融欺诈、数据隐私、恶意指令、社会工程、违法欺诈、非法征信干预
3. **LLM 深度检测** — Qwen3.5-9B 安全审查，覆盖合规分类 + 攻击模式检测
4. **PII 检测** — Presidio + spaCy 中文模型，detect 模式（脱敏不阻断）

### 输出检测（after_model_call）

1. **正则匹配** — 输出方向规则
2. **LLM 深度检测** — 检查模型输出是否包含违规内容
3. **PII 检测** — 输出脱敏
4. **内容过滤** — 输出方向分类

## 与其他 Rail 共存

本项目（`jiuwen_adapter`，priority=90）可与 `guardrail-jiuwen-tool`（`jiuwen_tool_adapter`，priority=85）同时加载：

| Hook | guardrail-jiuwen (90) | guardrail-jiuwen-tool (85) |
|---|:---:|:---:|
| `before_model_call` | ✅ HTTP 检测 | ✅ 本地检测 |
| `after_model_call` | ✅ HTTP 检测 | ✅ 本地检测 |
| `before_tool_call` | ❌ | ✅ 工具参数检测 |
| `after_tool_call` | ❌ | ✅ 工具结果检测 |

priority=90 先执行，block 后 priority=85 不再执行。两者规则互补，不冲突。
