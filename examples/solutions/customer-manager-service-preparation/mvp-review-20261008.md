# 客户经理服务准备：隔离候选验收

日期：2026-10-08。分支：`feat/customer-manager-previsit-mvp-v01`。
状态：**隔离合成链路已实现；尚不能宣布灵感账号至线上 CRM 的完整 MVP 通过。禁止据此上线。**

## 本批实现与边界

四步是任务体验，不是四个能力包：选客户与目标、事实汇集、准备报告、经理复核。
仅安装 `wealth.previsit-context.query`；已从本场景移除产品配置 capability/domain 声明。
未变更旧财富配置能力包，也未变更其他 Runtime 或公共 Task 合同。

服务端使用固定合成员工、明确客户映射和只读 Scope。客户选择不能改变 Owner、
岗位、授权或实际客户引用。管理员字样不赋予业务权限。数据源使用现有
`EmployeeAgentWealthSession`、受管 Provider 工厂和 `EmployeeAgentWealthProvider`，
以真实 MCP HTTP 协议读取受控合成服务，未直连数据库、未复制 CRM 业务实现。

报告保存客户引用、映射版本、授权代际、业务日期、来源回执、运行 ID、实际工具证据、
资产文件摘要和运行类摘要。数据日期缺失不拿回执生成时间代替；解释前先保存事实。
解释只选择已有事实引用，Host 渲染数值及核实问题，不接受模型重写金额或客户字段。
这是沟通重点整理，**不是完整财富建议质量已获验证**。

复核意见与事实分开存储，记录服务端复核身份、时间、版本、原因及意见历史。
保存前再次核验 Owner、客户范围、映射和授权，旧版本保存返回冲突。
复核不会把 FAILED/PARTIAL 改成完成。报告存在 Harness 示例 Host 的私有文件目录，
读记录不调用模型或 Provider；不写 CRM，不创建跟进事项，不办理产品或转账。

## 实际资产清单

| 资产 | 位置 | 本批用途 |
| --- | --- | --- |
| 员工 Profile | `profiles` 中 `employee-assisted@0.1.0` | 装配声明；不等于获得员工权限 |
| 方法资产 | `domain-packs/wealth/service-preparation` | 四步方法、缺项和人工复核边界 |
| 只读能力包 | `capability-packs/wealth-manager-previsit` | 唯一业务能力 `wealth.previsit-context.query` |
| EA Provider | `providers/employee-agent` | 调用 `prepare_wealth_previsit_context`，核验返回客户 |
| 员工会话 | `integrations/employee-agent-java/…/EmployeeAgentWealthSession.java` | 身份、范围、授权变化和保存时重新检查 |
| OpenJiuwen 接线 | `integrations/openjiuwen-java` | 现有 Bridge、执行证据、原生工具装配测试 |
| 场景定义 | 本目录 `solution.yaml` | 一个 Profile、一个 Domain Pack、一个 Capability Pack |
| 示例 Host | `../openjiuwen-wealth-manager-previsit/…/ServicePreparation.java` | 客户代际、事实保留、报告、复核与私有记录 |
| 隔离入口 | 同模块 `ServicePreparationDemo.java`、`service-preparation.html` | 本机回环四步页面，不是生产登录入口 |
| 验收 | `ServicePreparationTest.java`、`demo/verify_service_preparation.py` | HTTP 协议、并发竞态、浏览器操作 |

`SNAPSHOT` 不是不可变发布号，因此页面运行记录同时保存实际 Provider/Session/Host
class 的 SHA256 和四个声明/方法文件摘要。产品 MCP、模拟银行产品库、Smart Audit、
真实 CRM 写入、Python 与 AgentScope 均不属于本批安装集合。

## 验收结果

相关 Maven reactor：241 项，238 通过，3 个 opt-in 真实环境测试跳过，0 失败。
其中本场景新增 19 项全部通过；不是整仓所有模块回归，也不是生产可用率。

| 场景 | 证据与结果 |
| --- | --- |
| 正常 | 实际 Provider HTTP 查询、来源回执、报告生成、复核保存、原记录读取；PASS |
| 越权 | 授权集合外客户、错误 Owner、管理员冒充；Provider 调用 0；PASS |
| 映射缺失 | 缺映射、缺员工绑定；不回退客户本人身份，不查询；PASS |
| 客户切换 | Latch 控制工具迟到；新客户不等待旧查询锁；旧结果 DISCARDED，有审计；PASS |
| 工具失败 | HTTP 503，FAILED，无伪造事实，不调用解释；PASS |
| 解释失败 | TimeoutException/未知事实引用；PARTIAL，事实与缺项保留，不重新查询；PASS |
| 保存与回放 | 撤权后拒绝读取/保存，版本冲突拒绝，重建 Host 后原记录可读且不执行；PASS |
| 生成与复核竞争 | 解释进行中可读已保存事实，但拒绝提前复核，防止后续生成覆盖意见；PASS |
| 原生模型路径 | 真正 OpenJiuwen Runner 调用本机受控模型端点；一次调用、无业务 tools、只返回事实引用；PASS |

浏览器 r2/r3/r4：各 19 项通过，包含正常四步、缺项展示、两个客户切换、CSRF、Owner 注入、
映射拒绝、保存版本冲突、原记录读取、390px 手机无横向滚动。r1 的 14 项结果保留。
默认页面使用确定性重点选择器，模型调用为 0；不能把页面检查写成真实模型验证。

本地证据：

- `/home/ubuntu/outputs/service-preparation-mvp-20261008-browser-r1/`
- `/home/ubuntu/outputs/service-preparation-mvp-20261008-browser-r2/`
- `/home/ubuntu/outputs/service-preparation-mvp-20261008-browser-r3/`：内容/启动错误处理更新后的复验。
- `/home/ubuntu/outputs/service-preparation-mvp-20261008-browser-r4/`：并发复核保护后的最终复验，不覆盖前次记录。
- 各模块 `target/surefire-reports/`：实际方法名、结果和耗时。

首次测试受沙箱 Socket 权限限制；获准回环监听后继续。
开发中发现接线层把 runId 当成 Provider traceId，触发已有证据一致性拒绝，已修正为透传
原始 requestId/traceId，runId 在报告层关联。另一次失败是重启读取测试的 JSON 数字节点
类型比较，不改变业务数值；归一化后通过。未放宽证据门禁。

## 未解决项与上线门槛

1. **真实灵感 Owner → EA 岗位 → 客户授权接线未完成。** 当前演示只使用明确标识的本机
   合成身份；未冒用个人岗位、未自动生成或续签 EA 岗位授权。线上正常登录不能直接访问此入口。
2. **尚未接入受管 Builder 的创建/启动与现有运行记录页面。** 本批运行 ID、工具回执、
   报告和复核在示例 Host 内关联，可回看；不宣称已完成生产历史页面集成。
3. 原生 SDK 的受控模型协议验证通过，真实 DeepSeek/GLM 的本场景效果与时延仍 NOT_RUN。
   事实校验采用引用白名单，不能据此宣布整个 D04 公共解释问题已解决。
4. 四步演示还不是岗位财富经理助手全部效果。当前只整理有依据的沟通重点，没有产品
   检索、投资建议或独立业务 Owner 评定。
5. 多用户、多浏览器独立会话、生产报告保留/容量策略、人工准备加复核耗时基线待补。
   本机单一合成 Owner 的会话不能作为生产登录/权限平台。

本轮不部署、不合主线、不改变线上六卡；测试进程按脚本 finally 停止。
后续先完成 1、2 并做真实合成 CRM 验收，再由用户决定上线。不能用受控 fixture
替代这两项，也不能把模拟管理员提升为业务授权。

## 复现

```bash
# Harness 根目录
mvn -q -pl examples/solutions/openjiuwen-wealth-manager-previsit -am test
mvn -q -pl examples/solutions/openjiuwen-wealth-manager-previsit -am install -DskipTests
cd examples/solutions/openjiuwen-wealth-manager-previsit
mvn -q dependency:build-classpath -Dmdep.outputFile=target/demo-classpath.txt
python3 demo/verify_service_preparation.py --output /private/new-evidence-directory --chromium /path/to/chrome
java -cp "target/classes:$(cat target/demo-classpath.txt)" \
  io.github.bitlhk.finance.harness.examples.wealth.ServicePreparationDemo \
  --synthetic 18741 /private/service-preparation-records
```

打开 `http://127.0.0.1:18741/`；不得直接反代为生产入口。
可选 `--native-model` 只从现有 `FINANCE_AGENT_MODEL*` 服务端配置读取模型连接；
缺配置失败退出，不提供默认密钥。该参数不会把受控客户数据源换成线上 CRM。

业务评估基线：固定正常样本的已提供事实错误为 0，未取得收支显式列缺项；这仅是
合成确定性检查。关键遗漏的业务评价、人工准备/复核总耗时与经营价值均未评估，
不以报告生成成功率替代。
