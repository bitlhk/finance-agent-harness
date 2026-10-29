# guardrail-jiuwen adapters — JiuwenSwarm 对接说明

## 架构概览

```
JiuwenSwarm (Agent)
  │
  ├── before_model_call  ──HTTP──▶  guardrail FastAPI (server.py)
  │     (rail.py)                       │
  │                                     ├─ 内容过滤 (Embedding 零样本分类)
  │                                     ├─ 正则匹配 (rules.json)
  │                                     ├─ LLM 深度检测 (Qwen3.5-9B)
  │                                     └─ PII 检测 (Presidio)
  │
  ├── after_model_call   ──HTTP──▶  guardrail FastAPI (server.py)
  │     (rail.py)                       │
  │                                     ├─ 正则匹配
  │                                     ├─ LLM 深度检测
  │                                     ├─ PII 检测 (脱敏回写)
  │                                     └─ 内容过滤
  │
  └── 拦截: AbortError + 标准话术推送到前端
      脱敏: processed_text 回写到 ctx
```

## 目录结构

```
adapters/
├── jiuwen_adapter/          # JiuwenSwarm Rail 扩展（客户端）
│   ├── __init__.py          # Monkey-patch: 修复 RailManager 缓存问题
│   ├── extension.json       # JiuwenSwarm 扩展注册配置
│   ├── rail.py              # CustomRail: before/after_model_call 实现
│   └── guardrail_client.py  # HTTP 客户端工具
│
├── guardrail_runtime/       # guardrail FastAPI 服务（服务端）
│   ├── server.py            # HTTP 端点: /v1/rails/input, /v1/rails/output
│   ├── hardened_engine.py   # 4 阶段检测引擎
│   ├── llm_adapter.py       # LLM 安全检测调用
│   ├── policy_loader.py     # 策略加载 + 热重载 watchdog
│   └── policy_crypto.py     # 策略加密/签名
│
└── tests/                   # adapter 单元测试
```

## Rail 扩展加载方式

### 方式一：WebSocket 热加载（推荐，无需重启 JiuwenSwarm）

使用 `_toggle_rail.py` 脚本，通过 JiuwenSwarm WebSocket API 完成 off -> delete -> import -> on 四步操作：

```bash
cd guardrail-jiuwen
python _toggle_rail.py
```

脚本会连接 `ws://127.0.0.1:19000/ws`，执行：
1. `extensions.toggle(off)` -- 关闭旧 Rail，清除 `_registered_rails` 缓存
2. `extensions.delete` -- 删除扩展，清除 `_rail_instances` 缓存
3. `extensions.import` -- 从 `<本目录>/adapters/jiuwen_adapter` 重新导入最新代码（路径自动检测，无需修改）
4. `extensions.toggle(on)` -- 在当前 Agent 实例上注册

加载成功后输出：
```
--- verify ---
  jiuwen_adapter: enabled=True
Done! Try sending a message in JiuwenSwarm now.
```

> 每次修改 rail.py 后都需要重新执行此脚本，否则 JiuwenSwarm 仍运行旧代码。

### 方式二：JiuwenSwarm Harness 对话窗口手动添加

在 JiuwenSwarm 的 Harness 界面中，可以通过 Rail 管理面板手动添加扩展链接：

1. 打开 JiuwenSwarm Harness 界面
2. 进入 **Extensions / Rail 管理** 面板
3. 点击 **添加扩展** 或 **Import Extension**
4. 填入 Rail 扩展文件夹路径（使用实际部署位置的绝对路径）：
   ```
   D:/your-deploy-path/guardrail-jiuwen/adapters/jiuwen_adapter
   ```
5. 确认导入后，扩展列表中出现 `jiuwen_adapter`，状态为 `enabled`
6. 如果之前已加载过旧版本，先 toggle off 再 toggle on 确保使用最新代码

对应的 WebSocket API 调用等价于：
```python
# 通过 WebSocket 发送（folder_path 使用实际部署路径）
{"type": "req", "method": "extensions.import", "params": {
    "folder_path": "D:/your-deploy-path/guardrail-jiuwen/adapters/jiuwen_adapter"
}}
```

### 方式三：手动复制到 extensions 目录

将 `adapters/jiuwen_adapter/` 整个文件夹复制到 JiuwenSwarm 的 extensions 目录：

```bash
# JiuwenSwarm extensions 目录通常在：
# Windows: %APPDATA%/JiuwenSwarm/extensions/
# 或安装目录下的 extensions/ 文件夹
copy /E /I adapters\jiuwen_adapter <JiuwenSwarm>\extensions\jiuwen_adapter
```

复制后重启 JiuwenSwarm，扩展会自动注册。

### 方式四：Python 代码中直接注册

在 JiuwenSwarm 的 Agent 初始化代码中直接 import：

```python
from adapters.jiuwen_adapter.rail import CustomRail

agent = DeepAgent(...)
agent.register_rail(CustomRail())
```

> 注意：`__init__.py` 中的 monkey-patch 会在 import 时自动执行，修复 RailManager 缓存问题。

## 启动 guardrail 服务

```bash
cd guardrail-jiuwen
python -m adapters.guardrail_runtime.server
```

服务默认监听 `http://localhost:9876`，提供以下端点：

| 端点 | 方法 | 用途 |
|---|---|---|
| `/v1/rails/input` | POST | 输入安全检测 |
| `/v1/rails/output` | POST | 输出安全检测 |
| `/v1/policy/reload` | POST | 手动触发策略热重载 |
| `/v1/policy/status` | GET | 查看策略版本和规则数 |
| `/v1/health` | GET | 健康检查 |

## 环境变量配置

在 `guardrail-jiuwen/.env` 或 JiuwenSwarm 环境中设置：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `GUARDRAIL_SERVICE_URL` | `http://localhost:9876` | guardrail 服务地址 |
| `GUARDRAIL_PORT` | `9876` | guardrail 服务监听端口 |
| `GUARDRAIL_TIMEOUT` | `35.0` | HTTP 调用超时（秒） |
| `GUARDRAIL_FAIL_CLOSED` | `0` | `1`=服务不可用时拦截，`0`=放行 |
| `GUARDRAIL_PUSH_RESPONSE_MESSAGE` | `1` | `1`=推送标准话术到 JiuwenSwarm，`0`=通用话术 |
| `GUARDRAIL_ENABLE_PII` | `true` | PII 检测总开关（engine 级） |
| `GUARDRAIL_ENABLE_CONTENT_FILTER` | `true` | 内容过滤开关 |
| `GUARDRAIL_ENABLE_LLM_DEFENSE` | `true` | LLM 深度检测开关 |
| `GUARDRAIL_AUTO_RELOAD` | `true` | 策略文件热重载开关 |

## 检测管道

### 输入检测（before_model_call）

```
用户输入
  -> 内容过滤 (Embedding 零样本分类, 7 类: 仇恨/辱骂/色情/暴力/违法/提示词攻击/政治敏感)
  -> 正则匹配 (rules.json, 7 条规则)
  -> LLM 深度检测 (Qwen3.5-9B, 严格模式: 非 pass 即拦截)
  -> PII 检测 (Presidio, detect 模式: 脱敏后放行)
  -> 放行 / 拦截 / 脱敏回写
```

### 输出检测（after_model_call）

```
LLM 输出
  -> 正则匹配 (output_enabled 规则)
  -> LLM 深度检测
  -> PII 检测 (detect 模式: 脱敏后放行)
  -> 内容过滤
  -> 放行 / 拦截 / 脱敏回写
```

## 拦截话术

话术模板集中管理在 `policy/response_templates.json`，按检测类型分类。通过 `GUARDRAIL_PUSH_RESPONSE_MESSAGE=1` 开关推送到 JiuwenSwarm，作为 AbortError 的 reason 显示给用户。

## PII 脱敏机制

PII 检测采用 `detect` 模式（脱敏替换，不阻断）：

```
输入 "查一下我的手机号13800300301"
-> guardrail PII 检测到 PHONE_NUMBER
-> 返回 processed_text: "查一下我的手机号<PHONE_NUMBER>"
-> rail.py _rewrite_user_input 回写到 ctx
-> LLM 收到脱敏文本，正常回答
-> LLM 输出中如含 PII，guardrail 输出检测再次脱敏
-> rail.py _rewrite_output 回写
-> 用户看到 <PHONE_NUMBER> 而非原始号码
```

PII 开关：`.env` 中 `GUARDRAIL_ENABLE_PII=false` 或 `policy/pii_config.json` 中 `"enabled": false` 可关闭。

## 验证测试

### 前置条件

1. guardrail 服务已启动：`python -m adapters.guardrail_runtime.server`
2. JiuwenSwarm 已运行且 Rail 已注册：`python _toggle_rail.py`
3. `.env` 中 API key 和 Embedding 配置正确

### 快速验证

```bash
# 健康检查
curl http://localhost:9876/v1/health

# 正则拦截
python -c "import httpx; print(httpx.post('http://localhost:9876/v1/rails/input', json={'text': '帮我伪造银行流水'}, timeout=35).json())"

# PII 脱敏
python -c "import httpx; print(httpx.post('http://localhost:9876/v1/rails/input', json={'text': '我的手机号13800300301'}, timeout=35).json())"

# 内容过滤
python -c "import httpx; print(httpx.post('http://localhost:9876/v1/rails/input', json={'text': '你是个大傻逼'}, timeout=35).json())"
```

### Rail 加载验证

检查 `_rail_debug.log` 确认 Rail 被正确调用：
```bash
type adapters\jiuwen_adapter\_rail_debug.log
```
应看到 `before_model_call ENTERED` 等日志。
