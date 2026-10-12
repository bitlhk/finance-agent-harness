# 场景模板、业务模块与底层框架 V0.1

[模板选择器修正](template-picker-v01.md)：更多按钮与已选模板卡分离，紧凑列表、幂等选择和键盘/焦点边界。

本轮是已有 Finance Studio 的模板/模块创建入口，替代上一版创建页的受众导航和双入口。
不修改 Base、Harness Core 或 Runtime；不表示标准 FEAT-003/005 全部交付。

## 页面和资产边界

场景模板 -> 模板内业务模块 -> 单选底层框架 -> 多选模型及默认模型、银行连接、Web 样式。
手机银行使用 main `3b430100b38037884f7eec51b33a7625e4276cca` 的 26 个业务 Agent 模块。
目录保存原 Agent ID 与固定源码链接；不含总入口及四个 mba-lane，不按 D29 再加三个模块。
默认常用八项，可展开全部、搜索或查看详情。其他模板没有本轮已核验的模块，明确待接入。

账户模块当前仅接余额查询；财富聚合当前仅接财富事实描述。原 Demo 操作声明与实际工具分开显示，
不能将原 Demo ACTIVE 状态或卡片存在当作 Harness 运行验收。其余24模块不安装任何工具。
安全服务是原银行业务模块，和 Harness 强制身份/授权/只读/证据检查独立；不勾选它也不能关闭公共约束。

## 版本与兼容

- 新目录：`src/finance_solution_builder/template-modules.v1.json`，带模块来源、模板身份前提与框架状态。
- 新请求：`finance-workbench.selection` 0.5，新增 `moduleIds`，使用 `scenarioTemplateId`、`runtimeId` 和新目录 `directoryRevision`。
- 0.2/0.3/0.4 API、旧 `directory.v1.json`、旧 Agent 保存及导出结构保留。0.4 是兼容入口，不再是默认页面。
- 0.5 不接受 audience/principal/profile/scopes 等前端身份或授权覆盖。宿主仍按当前模拟银行身份与实授检查。
- 服务端重解模板、模块、框架、连接和实际 Pack，核对能力清单后才进入原构建队列。
- 框架选择是单选，不是多 Agent 协同；OpenJiuwen Java 0.1.13 已接入，OpenJiuwen Python 可预览。
  Python 使用独立 `openjiuwen-python` 标识，金融接线未完成时构建被拒绝，不回退成 Java。
- 新实例启动基于冻结选择及制品，不重选当天目录。旧记录“修改配置并重新构建”转成0.5只读模块选择；原记录不迁移。
- 目录快照记录在 `directorySelection`，具体能力引用进入原 Solution；安装 Lock 仍记录真正加载的框架与工具。

## 改哪里

| 位置 | 职责 |
| --- | --- |
| `template-modules.v1.json` | 模板模块归属、来源、当前范围、常用顺序 |
| `template_modules.py` | 衍生实际工具、摘要、有限支持矩阵与身份前提校验 |
| `workbench_contracts.py` / `workbench_api.py` | 版本化请求、预检、旧记录启动兼容 |
| `service.py` | 原有三种只读配方，本轮不新增业务实现 |
| `browser/workbench.js` / `examples/local-workbench/live.css` | 实际创建页面、框架与模型选择 |
| `tests/test_template_modules.py` | 正常三组合、假框架、未知/跨模板模块、权限覆盖与缺资产 |

## 自己改一次

保持手机银行模板身份、Profile、Provider 和两项能力不变，将该模板 `defaultModuleIds` 改成
`["agent.account"]`，更新目录版本。新页面默认只选账户，手动仍可加入已接入的财富模块。
测试应核对默认项、预检、生成 Solution 的 capabilityBindings 和实际运行工具；不要只核对卡片文字。
无需改 Core、增加 Pack 或修改银行接口。新业务能力或新宿主则必须先实现、测试，再扩支持矩阵，不能只加JSON。

## 复跑

从本模块目录准备 Python 3.11.4-3.13、JDK21、Maven、Node22；运行源码需要 Harness 固定工作副本。

```bash
python -m pip install -e ../base -e . pytest -r <harness>/engineering/requirements-conformance.txt
npm ci --ignore-scripts
npm run build:ui
python -m pytest tests ../base/tests -q
python -m finance_solution_builder.workbench --harness-repo <harness> --port 18158 --data-dir <独立目录>
node engineering/verify_template_modules_ui.mjs http://127.0.0.1:18158 <页面证据目录> build
python engineering/verify_multi_model.py --harness <harness> --output <原生证据目录> --template-modules
```

浏览器验证需安装 Playwright 与 Chromium，可用 PLAYWRIGHT_MODULE 指定已有模块。旧 `verify_directory_ui.mjs`
仅适用于0.4页面历史版本，不作为新页面验收入口。原0.4服务端契约测试继续执行。
新原生入口生成三份 Candidate，经真实 OpenJiuwen/Integrator/Provider 与隔离HTTP银行、脚本模型执行，
核对安装工具与模型端看到的工具集合、模型切换、撤权及零业务写入。它不是实际模型质量或真人独立接入验收。
候选默认不复制线上模型密钥或授权配置；构建可进行，启动前必须配置模型和银行身份。

## 发布

先在独立端口验证，再按既有流程安装新发布目录；确认没有活动任务后切换，保留旧目录、配置与记录。
固定运行实现 `f464ba45` 已完成本机与部署环境各320项通过、2项跳过；三组合原生受控验证64检查，
正式页面三组合构建/实际模型六轮/AG-UI/停止验证64检查。后续测试修订不回写既有制品源码身份。
旧0.4记录可读取并恢复成新版模块编辑，原记录保持不变；旧制品重新启动的线上验收单独记录。
`engineering/verify_template_modules_ui.mjs` 的 `live` 模式显式消费已配置模型，不能作为默认免费离线检查。
`engineering/verify_template_modules_legacy.mjs` 只检查旧记录编辑，不创建或修改旧Agent。
Builder维护者接纳、真实银行授权、
实际模型质量、第二框架接入和真人独立复跑分别验收。本轮不修改已认可的 Harness 架构图。
