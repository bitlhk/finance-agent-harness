# 嵌入式转账：分项交付

2026-09-14，基于 main e506e212。目标是现有 Agent 装配 Harness 能力，不新建转账页面或业务引擎。

## 本批完成

- internal-transfer V0.1：只安装 transfer.internal.create，原零售组合包不变。
- 沿用 NonAiBankingProvider 和原银行任务接口，无新银行状态机、扣款或账本实现。
- 资产目录更新至 0.2.4，目录引用样例摘要同步。
- 三项新增回归：与旧包的业务声明相同；装入已有 Agent 保留方法，聊天不能授权且重复确认不再次调用 Provider；未选转账不安装工具。

## 实际运行

JDK 21 / Maven 3.9.16；`mvn -B -ntp verify`，14 模块成功。新增测试所在类 5 项全部通过。
`/home/ubuntu/finance-history-venv/bin/python engineering/verify_contracts.py`：18 Schema、98 项 Fixture/资产/状态检查通过，另有共享显示/观测案例通过。
系统 Python 缺 referencing，改用既有验证虚拟环境；初次目录摘要检查捕获未同步引用，更新后通过，没有删除断言。

这些是离线/受控 Provider 验证；本批未运行真实模型、银行办理或页面验收，没有部署。

## 尚需完成，不能宣布整体上线

1. Builder 现有创建目录选择、组合、安装 Lock 接线；不能简单放宽只读 allowlist。
2. 现有持续 Host 的动态 TASK 授权、银行强认证、持久化会话/任务及恢复接线。
3. 现有对话界面中的任务摘要与可信确认组件；不添加独立导航页，不复制试点表单应用。
4. 受控银行完整链路、撤权、重复确认、参数变化、重启查证和原只读组合回归；通过后发布新生成 Agent。

独立试点历史证据继续保留，但不作为上述接线已经完成的证明。旧 Agent 不自动升级，Python TASK 未开放。
