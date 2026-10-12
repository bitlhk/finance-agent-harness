# 业界标准参考页与服务名称 V0.1

本轮基于 Builder `be8d335d`，只修改具体 Builder 的目录展示与 WebUI，不修改 Base、Harness、Runtime、授权或构建配方。

## 页面与来源

左下“业界标准”在本地与服务端模式均可打开。桌面显示六列表格，小屏按每条标准逐项重排；
它是只读参考页，不需要模型或银行连接，不会自动请求外部标准网站。
服务端的开发指导入口及原四项工作空间导航保留，管理操作的登录要求不变。

唯一内容源为 `browser/industry-standards.v1.json`，构建时由 esbuild 装入现有 UI；不新建后台服务。
每条记录包含名称、版本、类别、用途、适用边界、核实状态、官方链接及核实日期 `2026-09-10`。

| 参考 | 本轮核实与用途 | 不代表什么 |
| --- | --- | --- |
| [BIAN Service Landscape 14.0](https://bian.org/deliverables/service-landscape/) | 官方版本、服务域分类用途 | 不代表项目26模块或Harness能力逐项对应BIAN |
| [ISO 20022 Business Model](https://www.iso20022.org/iso20022-repository/business-model) | 官方业务模型与数据字典说明 | 未固定模型仓库快照，未逐字段证明符合性 |
| [GB/T 27926.1-2021](https://std.samr.gov.cn/gb/search/gbDetailed?id=C8394B1AA0EF4558E05397BE0A0A8876) | 官方条目与等同采用ISO 20022-1:2013的关系 | 未核验全文条款，不是本项目认证 |
| [WCAG 2.2](https://www.w3.org/TR/WCAG22/) | 官方Web可访问性规范入口 | 非ATM硬件规范，局部界面检查不等于整站符合性评估 |

## 名称兼容

`template-modules.v1.json` 目录版本从0.1.0变为0.1.1；仅账户助手、转账助手、信用卡助手的 `label`
改为账户服务、转账服务、信用卡服务。旧名称保存在可选 `aliases` 中，参与搜索，不参与能力安装或授权。
26个模块ID、上游来源SHA、模块归属、能力引用、默认组合和有限配方均不变。
金融助手仍保留原名，财富聚合服务仍保留原范围。

新目录revision变化后，持有旧revision的新构建请求按原机制要求刷新；已保存Agent选择与安装Lock不改写。
旧记录仍由原冻结制品运行。第三方若严格解析目录新增字段，应先允许可选字符串数组 `aliases`；
这不是改变Selection、Capability或Runtime合同。

## 修改与验证入口

从本Builder模块目录运行：

```bash
npm run build:ui
PYTHONPATH=src:../base/src python -m pytest tests/test_industry_standards.py tests/test_template_modules.py -q
PYTHONPATH=src:../base/src python -m finance_solution_builder.workbench \
  --harness-repo <固定Harness工作副本> --port 18178 --data-dir <独立测试目录>
python engineering/verify_industry_standards_ui.py http://127.0.0.1:18178 \
  <截图与报告目录> --chromium <Chromium可执行文件>
```

浏览器验证真实编译后的工作台与本地目录API，覆盖1440、1024、390、320像素；核对四条完整记录、链接、
键盘入口、无页面溢出、手机触控尺寸、导航不挤占、旧名称搜索及能力选择不变。
为了检查两种footer布局，第二轮只模拟bootstrap的部署模式标签，不代表服务端认证部署验收。
不会创建Agent、调用模型或银行；外部来源为人工访问时才打开的链接。

## 本轮验证记录

2026-09-10，本地 Linux、Python 3.12、Node 22、Playwright Chromium：

| 检查 | 实际结果 |
| --- | --- |
| `npm run build:ui` | 成功，实际UI与图标产物已重建 |
| 标准数据与目录定向回归 | 34通过 |
| `pytest tests ../base/tests -q` | 502通过、2跳过，未用跳过项证明真实环境 |
| `verify_industry_standards_ui.py` | 115项通过；本地及模拟服务端菜单、四个屏宽 |
| 源码空白检查 | `git diff --check` 通过 |
| 真实模型、银行业务、生成Candidate、线上部署 | 本小步未执行 |

首次布局探针把小屏刻意隐藏的桌面表头也当可见内容检查，出现一次假失败；已改为检查实际可见的
正文单元格与菜单，并保留真实页面整体横向溢出检查。未通过删减可见内容规避布局问题。
完整截图与摘要报告保存在本轮交接目录 `standards-ui-20260910/screens-final/`。
