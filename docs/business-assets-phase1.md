# 业务资产第一阶段合同（候选）

只登记已存在的三个只读单元：余额、财富事实、收支组合（两个工具）。local-read-only
是既有客户模拟宿主的使用约定，不是权限来源。余额必须用 account-balance-readonly，
不以含 TASK 的 retail-banking-basics 代替。

本次为相关 Domain/Capability/Provider 登记 local-read-only 兼容性，依据是现有显式
capabilityBindings 与七组合；不改变 Profile 之外的授权检查，不扩大其他宿主的通用 resolver。
财富与余额组合可绑定两个参考 Provider，但每个 capability 必须有唯一显式绑定。

finance-method-derivation.v0.1 是候选的不可变方法派生来源合同：
basePackages 记录能力包依赖，copiedFrom 记录复制的方法或宿主模板；二者不是一个概念。
记录实际内容摘要、源位置、版本、目录 revision、目标 scope 和替换方式。
它不包含执行代码、客户授权、质量 PASS 或自动更新指令。

Builder 在保存/试跑/发布/新构建准入核对当前基础引用。已有私有 release.v0.1 不补写
来源、不重算 digest；新绑定版本为 release.v0.2。旧制品按自身 Lock 运行，不自动升级。
修改基础版本时从业务目录创建新草稿并重跑，不继承原验证结论。

Schema 能证明结构，不证明宿主已核实身份，也不代表 Python 方法试跑已实现。
当前方法试跑为 Java 受控执行，专业质量和真人验收单列。
