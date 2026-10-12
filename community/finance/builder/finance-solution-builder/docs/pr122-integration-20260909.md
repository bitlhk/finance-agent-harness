# PR #122 集成验证

日期：2026-09-09。继续更新现有草稿 PR，不自动合入 `finance`，不更新展示服务。

## 基线与范围

- 原 PR：`ca71f04bc5009c61c0f764d822f1524e318044e3`。
- Studio 增量：`dc9f2cff15dd279da462df628be1eed71e06bcd1`。
- 本次同步上游：`b43614673da9db290e2d8f2033eb5eecaabe9f54`。
- 原生受控验证使用 Harness：`b55d0722cac2905388f57dc4a17b85d346a0e470`。

合并保留双方历史，不强推。文本冲突仅涉及 README 安装方式及 `pyproject.toml`：
保留上游 workspace/pip 说明、Base 兼容范围和 test extra，同时保留 Studio AG-UI 依赖与
Python 版本要求。`builder.py` 自动合并后，传统 CLI 保留上游 Backend 实现，两个只读
原型 Profile 保留显式委托。未修改 Base、Backend 契约或 Feature 接纳状态。

新增 UI 包括模板业务模块、显式 Runtime、版本化选择、紧凑模板弹窗及键盘/触屏支持。
原生 Runtime 仍仅支持 OpenJiuwen Java 0.1.13；其他 Runtime、模板和模块不因展示而启用。
贡献文件全部位于本模块。同步带入的其他 Builder 变更与上游相同，不属于本 PR 功能增量。

## 本轮回归

运行环境：Linux x86_64、Python 3.12.3、Node 22.23.1、JDK 21.0.12、Maven 3.8.7。
测试及生成源码为 `92b466f4`，后续提交仅补本文档，不改变运行代码或新制品的来源记录。

| 检查 | 状态 |
| --- | --- |
| Builder + Base 单元/契约回归 | 438 通过，2 跳过；包括上游新增两类 Backend 测试 |
| 前端 `npm ci` / `npm run build:ui` | 通过；重新生成 bundle 与提交内容一致 |
| 三种新生成 Candidate 与受控 HTTP 验证 | 64 项通过；三者均实际编译、启动、调用、停止 |
| 制品清单与摘要复核 | 余额 127、财富 129、组合 130 个文件逐项匹配；各含 98 个依赖 JAR |
| 紧凑模板选择器浏览器回归 | 74 项通过，1 项条件跳过，无脚本错误；覆盖桌面、窄屏、键盘和触屏 |
| 模板目录与 API 拒绝回归 | 28 项通过，无脚本错误；未产生 Agent 或模型调用 |
| 真实模型/真实银行 | 本轮未执行，不沿用旧版结果作为本次验收 |
| 上游接纳、真人独立接入 | 待维护者/场景工具组验收 |

两项跳过为未安装可选 Intent 消费者依赖、Linux 不执行 Windows 专属路径测试。
浏览器另有一项条件跳过：隔离操作者没有旧 Agent，未执行原记录编辑上下文检查；
旧选择结构和编辑兼容的 Python 回归仍执行，不把条件跳过写成浏览器已验收。
不把 AI 复跑称为真人接入，不把脚本模型称为真实模型，不把 HTTP Fixture 称为真实银行。

受控端点 A 实收 6 次模型请求、B 实收 9 次，未选 C 为 0。
源查询返回前禁用连接，下一次模型调用被阻断；撤销客户授权后没有新增模型/业务读取。
三组安装集合均精确匹配所选 QUERY 能力，业务写入为 0。验证器及其生成实例已停止。

| 组合 | 本轮构建 ID | Artifact SHA-256 |
| --- | --- | --- |
| 余额 | `agent-5466e7ad56a9473a` | `8193b07fa51b3d3f810b90b1494b31b88b08240a43d7cb27d8f5f82a8f437503` |
| 财富 | `agent-14934bd6f4854c68` | `6bd3e68b3fab6338403074df2fca181e4f2d40de8696b1e024c02f0608e2a3d8` |
| 组合 | `agent-19a166ffbc684cf9` | `551e5042928fbe7e294316d7dc6e8883f943fd7e87ef81705b42aa34872d3b6e` |

本机证据在本模块的 `build/pr122-integration-unit.xml`、`build/pr122-native/`、
`build/pr122-browser/` 及对应 `build/workbench/<buildId>/`。它们是可重跑的本地产物，
不将日志、虚构身份配置或生成 JAR 提交到上游源码中。

## 复现入口

在 `community/finance/builder/finance-solution-builder/` 执行：

```bash
python3 -m venv .venv
.venv/bin/pip install -e ../base -e '.[test]'
.venv/bin/pip install -r "$HARNESS_REPO/engineering/requirements-conformance.txt"
.venv/bin/python -m pytest tests ../base/tests -q --junitxml=build/pr122-unit.xml
npm ci --ignore-scripts
npm run build:ui
.venv/bin/python engineering/verify_multi_model.py \
  --harness "$HARNESS_REPO" --template-modules --output build/pr122-native
```

`HARNESS_REPO` 必须指向上述固定 Harness 开发包/正常 Git 副本；需有可用 JDK 21、Maven 和
依赖获取渠道。验证器创建隔离 HTTP 模型与银行，依次构建余额、财富、组合三个 Candidate，
检查实际工具与 Lock、模型允许列表、撤权/禁用时序及零业务写入，结束时停止自己的进程。
输出目录必须尚不存在；如需重复，另选输出目录，不覆盖旧结果。

浏览器检查依赖 Playwright/Chromium，`PLAYWRIGHT_MODULE` 可指向已安装模块的绝对路径：

```bash
node engineering/verify_template_picker.mjs "$TEST_STUDIO_URL" build/pr122-picker
node engineering/verify_template_modules_ui.mjs "$TEST_STUDIO_URL" build/pr122-modules metadata
```

`TEST_STUDIO_URL` 指向本次源码启动的隔离工作台，模型连接仅供目录展示，metadata 模式不调用模型。
前者不修改 Agent；后者只发送应被拒绝的非法构建请求，不生成 Agent。
完整架构接纳和后续 Backend 迁移边界见 [交接说明](upstream-studio-handoff-v01.md)。
