# 客户经理访前准备候选

状态：开发中，未合并、未部署，不是 Dot 可执行的完整 MVP。

业务入口计划放在“客户经营 / 客户经理访前准备”。浏览分类不赋予权限，
也不限制以后跨分类组合能力。首批只验证 OpenJiuwen Java 和专用合成身份。

## PPT 与执行证据

| PPT 步骤 | 资产与证据 | 当前边界 |
| --- | --- | --- |
| 客户洞察 | EA 员工归属绑定、授权客户、访前上下文 | 线上员工连接尚未配置，不能借用客户本人身份 |
| 事实汇集 | `wealth.previsit-context.query`、Context Receipt、来源数据日期 | 未安装或未授权收支入口时明确缺失，不从持仓估算 |
| 服务建议 | `wealth.allocation-context.query`、适当性筛选及政策依据 | 候选适配已实现，产品只供复核，不能购买 |
| 经理复核 | 拟复用宿主运行记录保存人工意见及报告版本 | 尚未接线；方法输出只可标待复核，不能伪造确认 |

## 仓库边界

- Finance Harness：能力包、专业方法、EA Provider 适配和合同测试。
- Employee Agent：客户归属、源数据、政策筛选、CRM 授权及真实写入回执；不复制业务系统。
- Builder 独立分支：员工宿主接线、场景入口及人工复核交互。不得合并同事主线。
- Smart Audit：不属于本批，不合并、不启用。

旧员工模板仍引用已不存在的 `EmployeeAgentWealthProvider.RuntimeIdentity`，
且调用缺少可信绑定的四参数 Config。当前统一创建目录也仍默认 CUSTOMER 和
local-simulator。不能通过只增加卡片或放宽准入，把这些接线缺口隐藏起来。

## 本地复现

在 Harness 仓库根目录执行：

```sh
mvn -pl integrations/openjiuwen-java,providers/employee-agent,integrations/employee-agent-java -am test
```

Provider 测试仅使用回环地址的合成 MCP 服务。装配测试使用真实 OpenJiuwen
工具注册和可控 Provider，验证两个只读工具、四步 Skill 安装与关闭；
它不证明真实模型选择、线上员工授权或页面体验。

## 上线门禁

1. 专用合成员工、客户归属和授权有效期已核验；凭据仅在服务端。
2. 员工辅助宿主完成声明、构建、启动、工具调用、事实保留和证据回放。
3. 经理复核有可信身份、版本和修改原因，不由模型声明完成。
4. 跟进若写入，必须经明确确认并返回真实合成 CRM 回执；未配置时不能显示写入成功。
5. 现有六卡回归通过，候选隔离页面验证通过后才合并发布。
6. 记录制品摘要、配置差异与回滚方式，然后交付 Dot。

冻结的 12 条页面验收项在
`../openjiuwen-wealth-manager-previsit/demo/mvp-acceptance.json`，均为 NOT_RUN。
正式 Dot 包还需要真实测试身份引用、合成客户参数、入口和授权到期时间；
此候选不能作为已具备这些前置条件的测试包发出。
