# Studio 使用对象与能力目录 V0.1

历史0.4入口说明：新版首页由[模板/业务模块候选](template-modules-v01.md)替代。服务端旧契约和旧记录仍兼容，
本页原双入口浏览器脚本不用于新版页面验收。

范围：Finance Solution Builder 原型扩展。不是 Base 目录服务，也不宣称已完成上游标准 FEAT-003/005。

## 两个起点，同一条装配路径

面客服务 / 对内业务 / 研发工程是目录视图。面客目前可构建；对内展示共同组件但员工授权与 Host 待接入；研发禁用。
场景模板和能力组件平级，默认模板。模板引用既有组件，调整后保留来源模板及实际选定集合；直接选组件不需要先选模板。
切换对象清空当前方案选择，不改变宿主身份、Profile 或实授。公共指南独立公开；本工作台/API仍登录保护。

当前两个模板：`customer-wealth-readonly`（财富 + 余额）、`customer-account-readonly`（余额）。
当前两个组件：`wealth.facts.describe`、`account.balance.query`。三种原有只读组合不变。
没有新建“面客版余额”和“员工版余额”两份 Pack；员工目录可见不代表员工业务路径可用。

## 文件对应

路径均相对 `community/finance/builder/finance-solution-builder/`：

| 文件 | 改什么 |
| --- | --- |
| `src/finance_solution_builder/directory.v1.json` | 卡片 ID/版本/用途/范围/领域/适用对象，模板引用组件 |
| `src/finance_solution_builder/directory.py` | 目录验证、从真实 Pack 读取工具/权限、来源版本摘要和服务端 ID 解析 |
| `src/finance_solution_builder/service.py` | 唯一有限组合配方 `solution_for`；既有 QUERY-only 安装不变 |
| `src/finance_solution_builder/workbench_contracts.py` | 0.4 严格选择模型与预检；0.2/0.3 兼容 |
| `src/finance_solution_builder/workbench_api.py`、`workbench.py` | `/api/v1/catalog`、预检、队列与目录快照，沿用原生命周期 |
| `browser/workbench.js` | 目录驱动 UI；模型、连接、构建、AG-UI 与原生服务复用 |
| `examples/local-workbench/catalog.css` | 两类入口、卡片与移动端样式 |
| `tests/test_directory.py` | 两入口等价、增权/未知项拒绝、实际 Pack 变更、模板调整 |
| `engineering/verify_directory_ui.mjs` | 真实 API 的页面操作、筛选/选择、可选真实编译 |
| `engineering/verify_multi_model.py --directory` | 三种新选择请求的编译、启动、受控模型与工具、模型切换/撤权等回归 |

目录不存密钥或客户身份；卡片声明版本与引用 Pack 版本不是同一个版本。
`directory.revision`包括目录表达、配方引用及实际读取 Pack 的 SHA256。操作类型、工具名、输入和 requiredScopes 从 Pack读取，
不再在前端手写。required 不等于 granted；模板/目录不能关闭底层身份、授权、只读及证据控制。

## 完整练习：已有余额能力，新增自己的模板

1. 准备获授权的 Harness 工作副本，按 README 安装 Base、当前 Builder、Node 依赖及 JDK 21/Maven。
2. 不修改 Harness Core、余额 Provider 或 Pack。在目录 JSON 的 `templates` 数组加入：

```json
{
  "id": "customer-account-check",
  "version": "0.1.0",
  "kind": "TEMPLATE",
  "label": "账户核对助手",
  "description": "查询当前获授权账户的余额与币种",
  "scope": "客户自助只读，不含支付、转账或资产汇总",
  "domain": "ACCOUNT",
  "audienceIds": ["CUSTOMER"],
  "componentIds": ["account.balance.query"],
  "icon": "landmark"
}
```

3. 更新目录整体 `version`；不改组件/Pack版本，因为业务能力没有变化。客户端刷新目录取得新 revision。
   目录位于 Python 包内，`pyproject.toml`显式包含该JSON；不是仅开发目录可读的散文件。
4. 页面模板视图会出现新项，不需改浏览器分支。模板详情的 `account_balance_query`、`account:read`来自
   Harness `capability-packs/account-balance-readonly/pack.yaml`；引用和Provider来自 `solution_for`。
5. 增加一条测试：用新模板提交0.4选择，核对最终 `capabilityBindings`只有余额，`domainPackRefs`为空；
   保留 `tests/test_directory.py` 中错误角色、篡改列表、必需控制不可关闭等负例。
6. 执行：

```bash
python -m pytest tests/test_directory.py tests/test_workbench_contracts.py -q
npm run build:ui
python -m finance_solution_builder.workbench --harness-repo <harness> --port 18158 --data-dir <独立测试目录>
```

另一个终端运行浏览器验证（Playwright/Chromium需已安装；用 `PLAYWRIGHT_MODULE` 指定现有模块）：

```bash
node engineering/verify_directory_ui.mjs http://127.0.0.1:18158 <新证据目录> build
python engineering/verify_multi_model.py --harness <harness> --output <新原生证据目录> --directory
```

浏览器脚本的模板 ID 是本仓基线；自行新增模板后，再增加针对新ID的点击与快照断言，不把旧22项检查作为新增模板被测证明。
第一条构建不要求已有模型密钥；启动必须有有效冻结模型连接。第二条显式使用隔离HTTP模型/银行，不是付费模型质量验收。

7. 核对构建记录 `directorySelection`、`selection`、生成 `solution.json`和 `service-lock.json`中的精确集合；
   运行后再核对实例返回的工具和安装Lock。目录快照不是安装成功凭证，编译不等于授权或对话成功。

## 真正新增能力时

若新增独立业务操作，先完成 Harness Capability/Contract需要的语义、Provider及正负例，再扩 `service.py`合法配方、
实际生成Host的安装/授权接线和支持矩阵，最后增加目录卡片和实例验证。当前配方只有两项能力，新增JSON项不能绕过这个范围。
改变银行来源主要改 Provider；改变方法主要改 Skill；变更数据语义才扩 Contract；新Runtime需Integrator验证。
这不是“新增特性就新建四套资产”。需维护者扩配方时明确提出，不把菜单可配置等同于任意插件可运行。

余额查询有独立结果；转账办理包含预检、冻结、确认、提交和查证，不把每个阶段机械分成可任意调用的卡片。
必要风控/授权/确认不可关闭。财富推荐、保险教练和投研报告通常是专业方法/场景，不等于一条API。
员工访前引用共同事实是下一步设计示例，需独立验证员工身份、关系、Profile与投影，不用面客模拟授权凑实验。

## 兼容与交接

- 原 0.2/0.3 请求、既有制品和归属不迁移。新0.4请求服务端重新解析，冻结目录来源与最终选择。
- 目录更新会拒绝陈旧的新建请求；启动旧制品不重选最新目录，继续核验原制品、模型绑定及当前授权。
- 搜索/筛选只改变显示；切换开始方式保留可见选择；切换对象显式清空。
- 此次提交仍是上游待评审原型增量。单站部署、维护者接纳、真实模型、独立人工接入分别报告，不相互冒充。
