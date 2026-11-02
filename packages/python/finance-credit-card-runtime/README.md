# Credit-card reference Runtime

面向客户经理的只读信用卡服务能力，包含额度预警、权益推荐和独立对话记录。原生 openJiuwen 编排；预测和推荐不构成授信审批或权益办理。

运行环境：Python >=3.11.4,<3.14。安装本地包及对应依赖：

```bash
python -m pip install './packages/python/finance-credit-card-runtime[forecast,service]' pytest
python -m pytest packages/python/finance-credit-card-runtime/tests -q
```

由受管宿主注入当前经理授权、SnapshotSource、冻结预测制品、模型端口与状态目录；缺少绑定时不构造默认业务来源。具体职责和部署前提见 [职责表](../../../docs/credit-card-runtime-responsibilities.md)。

公共契约3.10／3.12检查与本包3.11／3.12／3.13独立 wheel 安装测试分开记录。CI不需要真实模型或业务凭据；生产SSO／ACL、受管Host安装、模型质量和目标环境性能仍需独立验收。
