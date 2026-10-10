# 客户服务准备接线检查点

本批为 `feat/customer-manager-previsit-mvp-v01` 候选实现，不部署、不变更线上六卡。
Smart Audit 不合入。Builder 同事仓不合主线。

## 代码核验结果

| 边界 | 现有代码 | 判断 |
| --- | --- | --- |
| 灵感登录 | Linggan `server/_core/index.ts` 的 `api/embed/auth-check` | 输出平台用户引用，不是 CRM Grant |
| 渠道身份 | Linggan `server/miniprogram/experience-client.ts` | 已有服务端 subject 与受验证身份发送链；不可从浏览器取得邮箱就仿造 |
| EA 岗位归属 | EA `server/_core/mini-experience.ts` 的 `resolveRegisteredAdoption` | 检查账号下岗位；默认可自动创建，MVP 不应使用自动创建路径 |
| EA 账号解析 | EA `server/db/channel-identities.ts` | 会更新 lastSeen，缺少绑定还可能创建用户；不能把调用描述成严格只读盘点 |
| 客户范围与上下文 | EA `wealth-previsit-tool-handler.ts`、`platform-tools-mcp.ts` | 保留岗位授权、客户范围和 Context Receipt，不复制 CRM 数据库 |
| Harness Source Adapter | `providers/employee-agent` | 访前／配置候选工具已有映射和前后授权核验 |
| 受管 Builder | 三处旧 employee/previsit Java 模板 | 仍引用旧 RuntimeIdentity，且默认员工身份／Scope 不足以证明授权，不可直接启用 |

## 本批实际代码

EA Java Integration 增加 `EmployeeAgentWealthSession`，将当前登录 Owner、EA 归属绑定、
现有 Grant、当前 Session 和实际安装集合接到现有 Provider 工厂上。
只读取已经核实的权威快照，不以元数据构造权限，不复制 EA 签名密钥。
查询不持锁等待；授权变化或会话关闭后返回的事实被拒绝。缺少可信供应器时失败关闭。

这只是隔离接线组件，不是一个已装到线上 Host 的完整员工系统。
Controller 最终响应代际提交仍须使用宿主现有机制，不由该组件替代。

## 下一步必须补齐

1. 在可信服务间完成灵感 Owner 到已存在 EA 岗位的映射；岗位列表不是业务授权凭证。
2. 从现有 EA 权威路径取得客户范围、用途、Scope、授权版本及期限，不能按岗位名推导。
3. 对受管 Java Host 注入该供应器及 Token 代理，接当前生命周期和运行记录；不复制签名根密钥。
4. 页面显式选择合成客户；访前稿保留来源、日期、缺项，经理复核有身份、版本和原因。
5. 隔离构建、真实账号授权联调、页面回放及旧六卡回归通过后再部署。

本批没有请求真实客户资料、没有 CRM 写入、没有创建岗位、没有修改生产权限。
灵感 SSH 仍按用户要求跳过，不是继续本地实现的前提。

## 验证

```sh
mvn -pl integrations/openjiuwen-java,integrations/employee-agent-java -am test -q
```

本地 HTTP fixture 仅绑定回环地址，全部客户、身份和令牌为合成值。
测试覆盖 Owner／岗位／客户／会话隔离、过期、Scope 不足、未安装、禁止办理、参数注入、
撤权、权限改变、会话关闭、迟到返回和事实保留。
真实 EA smoke 默认跳过，不算线上验收。

2026-10-08 本地执行：新增会话测试 18/18；EA Provider 29/29；
EA Token 5/5；OpenJiuwen Java 80 项中 79 通过、1 个真实模型 opt-in 跳过；
另有 EA 财富／保险 2 个真实环境 smoke 跳过。以上均无失败，不包含真实账户联调。

首次沙箱执行因禁止创建 localhost ServerSocket 中断；在获准的回环测试环境重跑，
不得将该环境错误归因于 EA 接口或业务规则。
