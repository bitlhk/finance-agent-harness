# 财富双 Candidate V1.1 验证记录

验证日期：2026-09-03。

## 锁定基线

- Finance Harness：`9ca56226f7b33c042975daa715c9030865d0bd59`；
- OpenJiuwen Java 模板：`f89c9fb2215dc4ebca169f676f96fac2d18b0e8c`；
- Finance Skill Eval：`e960d2e316f1de0954b1477c41109887c24ed630`。

两套输入均直接来自 Harness V1.1 交接样例：

- `examples/wealth-customer-diagnosis/solution-build-input.v1.json`；
- `examples/wealth-manager-previsit/solution-build-input.v1.json`。

## 验证结果

| Candidate | 结果 | 完整耗时 | 资产解析 | 关键验证 |
|---|---:|---:|---:|---|
| 客户财富体检助手 | `passed_with_risk` | 87.70 秒 | 10 毫秒 | Harness 测试、编译、资产测试、启动健康检查、冻结 Eval 回放 |
| 财富经理访前助手 | `passed_with_risk` | 88.05 秒 | 9 毫秒 | Harness 测试、编译、资产测试、启动健康检查、冻结 Eval 回放 |

两者均生成独立 Candidate JAR、`solution-lock.json` 和 `build-report.json`。面客 Candidate 只装配 `non-ai-banking` Provider；员工辅助 Candidate 只装配 Employee Agent Provider 与 Token Integration，没有互相混入未声明 Provider。

`passed_with_risk` 的唯一业务含义是：本轮 Candidate 尚未连接授权测试环境执行在线评测。面客侧需要 Provider 测试凭据与授权测试主体；员工侧需要 EA 部署身份、签名密钥和授权客户引用。冻结证据回放不能替代当前 Candidate 的在线效果。

## 冷启动改进证据

初次实现虽然把缓存移出输出目录，但归档缓存仍会在下轮被误删，导致资产解析耗时 25～41 秒。修复固定提交归档复用后，两套增量构建的资产解析分别为 10 毫秒和 9 毫秒；Candidate 装配约 0.6 秒。当前主要耗时已经集中在 Maven 编译、测试和真实启动验证。

预检也已移动到输出目录清理之前。不支持的组合会返回结构化 `rejected`，并保留上一份成功产物。
