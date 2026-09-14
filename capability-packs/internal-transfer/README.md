# 行内转账：嵌入式能力包 V0.1

仅声明 `transfer.internal.create`，由现有 Integrator 安装到宿主已有 Agent。没有页面、服务进程或账本。
调用既有 NonAiBankingProvider；预检、创建、确认、提交和查证均使用银行 `/api/v1` 任务接口。
旧 `retail-banking-basics` 组合包保持兼容，新包的转账声明由一致性测试约束，不另维护业务实现。

## 宿主接入条件

- 显式选择本包，不从目录存在推导已经授权。
- 注入可信会话身份、动态实授和 Provider，保留银行强认证要求。
- 注入持久化 TaskCheckpointStore；只查原句柄，不因超时另建任务。
- 在现有对话界面提供确认组件，通过 Installation.confirmationBinding 绑定原任务，
  经认证和 CSRF 检查后调用 Installation.confirm。模型工具不能制造 HostTaskConfirmation。
- 确认后的结果仍以银行返回为准；拒绝本次宿主授权不等于银行任务已取消。

本包不附带余额或收款人查询，不隐式安装报价/模板/管理能力。银行对象的选择与消歧由宿主通过授权来源完成。
旧余额只读包含只读提示；组合时应使用经验证的组合配置，不能仅把互相矛盾的 Prompt 拼接。

## 验证与发布状态

`OpenJiuwenCapabilityInstallerTest` 新增独立声明一致性、嵌入已有 Agent、可信确认及重复确认、
未选择不安装用例，使用受控 Provider，不代表真实银行验收。
已有 Provider 的银行 API、强认证、异常与恢复用例继续适用，没有新转账引擎。

Builder 的现有创建页、持续宿主及确认组件接线尚未发布。不得将本目录加入就视为已完成 WebUI 上线，
也不把独立试点页面等同于统一 Agent 体验。原只读制品不自动升级，Python TASK 未开放。
