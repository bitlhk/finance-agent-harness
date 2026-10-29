# 完成第一个 Agent

**交付目标：** 一份有方法和案例、可构建、可核对调用证据、停止后可查历史的月度收支Agent；手机与ATM模拟端复用同一资产。不要求先学完全部架构。

严格分两轮：第一轮先登记案例、执行原版预检并保存基线，再构建运行原版；第二轮才修改方法正文、比较影响，交维护者安装独立候选，作者验收候选后申请正式发布。**线上工作台不会读取作者的本地diff。第二轮不是“上传任意Skill即可替换受保护方法”的承诺。**

## 取得材料与环境

此指南是公开静态文档，**两个代码仓均为受限仓库**，没有可核实的公开源码下载或统一申请工单地址。页面不能代你授予访问权。可从工作台进入“业务资产”查看允许访问的文件；已有账号不等于源码权限或银行查询授权。

向给你本指南的团队提交以下请求，收到交付物后再开始，不发送密码或令牌：

```text
任务：首次合成只读收支Agent，Java手机与ATM浏览器模拟。
需要：固定Harness与Builder开发包/仓库访问；独立开发工作区；
已配置的合成连接与模型引用；所需cashflow:read实授与到期说明；
当前版本对应的资格来源和受管准入交接方式；构建/运行名额。
用途：一次2026-09查询，以及一处隔离方法正文修改和定向验收。
请给出实际交付联系人或既有工单入口，不要提供共享凭据。
```

**维护者交付清单：** H `4771cfe5de94757d099daa44bbd41d3177311740`、B `0a38ec183c832eab847e60158e06271b14e25d6b`的干净源码或等价开发包与摘要；依赖安装说明；可用合成连接；普通工作台入口；本任务可用资格及维护者审核路径。缺任一项，记录“材料/环境受阻”，不能当新人已独立接入通过。

| 路线 | 环境和位置 | 不要混用 |
| --- | --- | --- |
| 方法作者，运行已装配资产 | 浏览器、工作台账号、已配置连接及实授 | 不需要本机装Java；先确认运行能力，不把目录可见当授权 |
| 方法源码练习／Java Provider | Harness根为`HARNESS_DIR`；JDK21（根pom）、Maven；Builder项目根为`BUILDER_DIR` | Builder根不是整个monorepo根 |
| Builder预检 | Python `>=3.11.4,<3.14`，按`pyproject.toml`安装的隔离环境，包含同仓`base`依赖 | 本教程Java Runtime不代表Python Host已完成同等安全验证 |

`BUILDER_DIR`是开发包内`community/finance/builder/finance-solution-builder`；`../base/src`相对于它。后文`python`必须是这套隔离环境的解释器，不依赖系统Python碰巧可用。`TUTORIAL_DIR`为你新建的绝对路径空目录，不指向线上或他人目录。变量均为需自行设置的占位符，不是现成下载地址。

```bash
# 工作目录：任意；先用自己的实际路径设置HARNESS_DIR、BUILDER_DIR、TUTORIAL_DIR
git -C "$HARNESS_DIR" rev-parse HEAD
git -C "$BUILDER_DIR" rev-parse HEAD
java -version
mvn -version
python --version
```

**通过：** 提交匹配上述基线，源码未混入他人修改；版本满足要求。**排障：** 缺仓库、依赖或资格材料返回维护者，不复制线上秘密、不关闭准入。

## 1. 冻结任务与验收

**输入：** “查询2026-09收入、支出与净额，注明币种、统计范围和历史覆盖缺口。”只读本人合成数据，不转账、不做投资判断，不推断完整月份无交易。

**操作：** 在自己的任务记录写明终端、固定月份、预期及失败处理；先用同一现有方法完成两端，再改方法。没有可信日期时不使用“上月”替代固定月份。

**预期／通过：** 有事实时金额和币种一致；缺少覆盖说明时明确未知，不能把未知当零。资料访问受阻要显示授权或来源原因。两端产生独立Agent/Lock/request，引用同一资产。

**排障：** 0CNY在既有合成样本出现过，不是此后所有环境的固定答案。检查来源事实而非照抄示例金额。

## 2. 选择已有资产，标出缺口

**输入：** 第1步任务。**操作：** 工作台“业务资产”查看收支组件的“组成与依赖／文件与修改”，再去“创建Agent”选择账户与收支中对应收支模块，保持余额/财富等无关能力不选。

| 必需零件 | 真实仓内位置（点击查看交付定位） | 本任务改不改 |
| --- | --- | --- |
| 方法 | [cashflow-review/SKILL.md](source:skill)及[方法pack.yaml](source:method) | 初次不改；隔离练习只改正文 |
| 能力 | [cashflow-readonly/pack.yaml](source:capability) | 不改；两工具声明不等于每次都调用 |
| 数据合同 | [cashflow-facts.v0.1.schema.json](source:contract) | 不改 |
| 装配及守卫 | [assembly.v0.1.json](source:assembly)、[query-guard.v1.json](source:guard) | 不关闭、不自行绕过 |
| Provider | [NonAiBankingFactoryV01.java](source:provider) | 复用合成来源，不另写客户查询 |

**预期／通过：** 收支汇总`finance.transaction-summary.query`、分类`finance.spending-analysis.query`的安装范围明确；此任务只需要汇总实际执行。Provider为`non-ai-banking-reference`，方法为`cashflow-customer-self-service@0.1.3`。能力需求`cashflow:read`不是权限授予。

**缺零件怎么办：** 缺数据来源转[Provider开发](first-agent.md#roles)；缺Runtime适配转Host任务。不可把新接口塞进Skill文字并声称已经接通。

## 3. 第一轮：登记案例，不修改方法

**输入：** 原方法及下面三个案例，存入自己的验收记录。此表是人工验收用例，不会因为写进Markdown自动成为运行测试。

[下载案例记录文件](first-agent-cases.json)，放入`TUTORIAL_DIR/first-agent-cases.json`。这是`MANUAL_ACCEPTANCE_RECORD`，不是Eval导入格式；第一轮记录原版结果，第二轮另存一份记录，不覆盖首败。此时不要改SKILL；先完成第4步基线保存与原版运行。

| 案例 | 输入／故障条件 | 固定通过条件 |
| --- | --- | --- |
| 正常 | 第1步固定月份问题 | 工具事实+币种+覆盖缺口；请求记录可对应 |
| 缺期间 | “查询收支”，没有其他可信期间上下文 | 澄清期间；合法实授的工具缺参保留NEED_USER与missingInputs；不默认月份 |
| 覆盖未知 | 独立fixture查询2026-08，来源文件和配置如下 | coverage保留范围，gaps包含PERIOD_COVERAGE_NOT_VERIFIED，展示数值但不宣称完整月份 |

**覆盖未知的固定来源：** Harness `conformance/fixtures/cashflow/source/summary.json`和`summary-policy.json`。前者period为`2026-08`，收入`10000.00`、支出`1250.50`、净额`8749.50`；后者evidenceRef为`D05-003-SUMMARY-GOVERNANCE`。不能拿2026-09查询这份fixture，也不能将旧`v0.1/valid/summary.json`当成本次运行响应。

**维护者开跑前准备：** 在隔离测试进程使用Builder `src/finance_solution_builder/method_trials.py:fixture_module(project,harness)`加载上述source目录的`summary/summary-policy/analysis/analysis-policy.json`；它返回`engineering/verify_local_service.py`的helper。现有接线为`fixture = helper.Endpoints(); fixture.cashflow = helper.cashflow_data`，仅回环随机端口。该fixture实际响应`/api/v1/finance/transaction-summary/readiness`和`/api/v1/finance/transaction-summary?period=2026-08`，不是可直接导入工作台的包。

维护者必须先交付配置回执：固定H/B提交、四个fixture文件SHA256、隔离fixture进程和回环地址、候选连接引用确实指向该地址、合成主体及`cashflow:read`实授范围/到期、模型模式（CONTROLLED_TEST或已批准模型）、对应候选入口及清理责任；不交付秘密。现有隔离流程未完成此接线时，本案例标记`BLOCKED_FIXTURE_BINDING`，不能借线上来源冒充fixture验收。本轮教程修订不启动fixture或模型。

**结果验收：** 当前Provider的`CashflowMapper.map`输出`coverage=OWN_BANK_ACCOUNT_STATEMENT`、`gaps`包含`PERIOD_COVERAGE_NOT_VERIFIED`；`CashflowConsumer.describe`应为PARTIAL。coverage表示查询范围，不表示历史完整性。记录映射后的工具结果、request ID和配置回执；原始source JSON本身不含这些映射字段。固定模型fixture只能证明接线与事实，不能证明自然语言遵循新方法。

## 4. 第一轮：保存原版基线，再构建运行

**输入：** 尚未修改的固定Harness源码`HARNESS_DIR`、Builder项目`BUILDER_DIR`、独立空目录`TUTORIAL_DIR`。以下命令均在作者本地开发环境执行。

**本轮页面位置：** 原版普通工作台，仅用于原版构建运行。选择OpenJiuwen Java `0.1.15`、已配置且获准模型和合成只读连接，分别选择手机与ATM模拟端。先执行下面两段命令，再去页面构建。不要把运行地址或凭据贴进Skill。

**源码预检输入（不构建、不联网业务）：** 以下调用现有`service.solution_for`生成真实装配JSON；它是兼容预检输入，不是完整模型/终端/服务启动配置。两个终端的实际配置仍由上述页面生成并锁定。

```bash
# 工作目录：Builder项目根；变量须已设置为实际绝对路径
cd "$BUILDER_DIR"
export PYTHONPATH="src:../base/src"
python - "$TUTORIAL_DIR" <<'PY'
import json, sys
from pathlib import Path
from finance_solution_builder.service import solution_for
out = Path(sys.argv[1])
if not out.is_absolute():
    raise SystemExit("TUTORIAL_DIR must be absolute")
out.mkdir(parents=True, exist_ok=True)
solution = solution_for(
    ["finance.transaction-summary.query", "finance.spending-analysis.query"],
    "guide-cashflow")
with (out / "solution.json").open("x") as stream:
    json.dump(solution, stream, indent=2)
print("solution.json created; no build or business call")
PY
```

**预期／通过：** 新文件包含两项能力、原方法和Provider引用；重复运行会因文件已存在停止，不覆盖旧输入。**排障：** 导入错误检查Python隔离环境、`base`依赖和工作目录；不要改业务代码“补齐”教程环境。

**保存修改前基线（必须在改SKILL之前）：** 执行真实兼容CLI，提取外层输出中的`compatibility`对象，以独占创建方式写入`previous.json`。已有文件则停止；不通过重定向覆盖旧基线。

```bash
cd "$BUILDER_DIR"
PYTHONPATH=src:../base/src python - "$HARNESS_DIR" "$BUILDER_DIR" "$TUTORIAL_DIR" <<'BASELINE'
import json, subprocess, sys
from pathlib import Path
harness, project, out = map(Path, sys.argv[1:])
assert all(p.is_absolute() for p in (harness, project, out))
previous = out / "previous.json"
if previous.exists():
    raise SystemExit("previous.json exists; keep original baseline")
result = subprocess.run([sys.executable, "-m", "finance_solution_builder.cashflow_compatibility",
    "--harness", str(harness), "--project", str(project),
    "--solution", str(out / "solution.json")], cwd=project,
    check=True, capture_output=True, text=True)
current = json.loads(result.stdout)["compatibility"]
assert current["status"] == "COMPATIBLE" and current["required"] is True
with previous.open("x", encoding="utf-8") as stream:
    json.dump(current, stream, ensure_ascii=False, indent=2)
print(json.dumps({"saved": str(previous), "bindingDigest": current["bindingDigest"]}))
BASELINE
```

**预期／通过：** 输出saved与真实bindingDigest；previous.json顶层含status/files/asset，不含外层compatibility。它仅是预检基线，不是安装资格。现在在原版工作台构建，看五阶段准入，按第6步运行原版并登记原版Lock/request和首次结果，再停止自己的原版实例；未通过则保留并排障，不进入第二轮。原版页面绑定的H/B需与材料一致，不宣称读取过本地目录。

## 5. 第二轮：修改、比较、交接候选安装

**作者本地修改：** 从固定Harness基线建立自己的独立源码副本，记为绝对路径`CANDIDATE_HARNESS`；不修改第一轮HARNESS_DIR。仅改`domain-packs/cashflow/customer-self-service/skills/cashflow-review/SKILL.md`正文，加入`最终回答分为“收支事实”和“覆盖与待核实”两段；每项数值保留来源、币种和月份。`，保留原授权与缺项规则。提交自己的变更并记录SHA；不改Provider、Host、guard或两个终端文件。

同ID/version只作**未发布对照**，以新Git SHA和正文摘要区分，不能覆盖已发布内容。通用私有方法派生不保证通过当前固定绑定，可能被`CASHFLOW_COMPATIBILITY_INVALID`拒绝；本教程走独立源码候选，不绕过该检查。

**作者本地比较（Builder项目根）：**

```bash
cd "$BUILDER_DIR"
PYTHONPATH=src:../base/src python -m finance_solution_builder.cashflow_compatibility \
  --harness "$CANDIDATE_HARNESS" --project "$BUILDER_DIR" \
  --solution "$TUTORIAL_DIR/solution.json" --previous "$TUTORIAL_DIR/previous.json" \
  --changed-path domain-packs/cashflow/customer-self-service/skills/cashflow-review/SKILL.md
```

**预期：** COMPATIBLE且impact=AFFECTED，changedDependencies含该SKILL，consumers为MOBILE_WEB/ATM_WEB，qualificationReuse=REVIEW_REQUIRED。未知路径为UNKNOWN_REQUIRES_REVIEW且退出码2。保留完整输出；此时没有新Agent/Lock/request，不能要求作者先提交它们。

**作者交给维护者的第一份交付：** 基线H/B、作者候选Git SHA与diff、previous.json、修改后预检输出、案例及原版观察、候选需要的连接/实授/模型模式。不要交秘密；尚不存在的候选Lock/request明确写“构建后产生”。

**维护者接纳与构建（独立环境）：** 审阅后将同一提交接纳为自己的独立候选副本`CANDIDATE_HARNESS`，记录绝对路径和clean SHA，不能只把作者diff放到目录旁。准备新`CANDIDATE_DATA`，沿已有合成连接、模型和资格配置启动独立本地工作台：

```bash
# 维护者的Builder项目根；所有变量为该机实际路径，端口必须空闲
cd "$BUILDER_DIR"
PYTHONPATH=src:../base/src python -m finance_solution_builder.workbench \
  --harness-repo "$CANDIDATE_HARNESS" --port 18158 --data-dir "$CANDIDATE_DATA"
```

该入口实际只监听维护者机器回环地址，Jobs把这个harness路径传入SolutionBuilder，页面构建从这里取资产；不是作者本机路径、不是原线上工作台，也不会把本地修改自动同步到服务器。作者若不在同一机器，维护者必须提供既有获授权访问方式；没有则`BLOCKED_CANDIDATE_ACCESS`，不能把回环地址当云端入口。

**维护者准入任务：** 先按[候选资格准备与服务环境门禁](source:release-guide)的Isolated candidate preparation及Service environment gate核对本次收支组合，按[本地连接配置](source:local-guide)准备隔离环境；该历史文档后面的八轮业务验收不是本教程要求。配置真实`FH_OBSERVATION_QUALIFICATION_FILE/SHA256`及适用的release gate来源绑定，摘要变化先判断覆盖，不重标旧资格或关闭门禁。缺资格/连接/访问条件则分别记录BLOCKED_QUALIFICATION/CONNECTION/ACCESS，不能假装已完成安装。

维护者在这个候选工作台分别选择手机/ATM收支组合并完成真实受管构建准入，**随后**回传候选可访问入口、实际源H/B提交、方法文件摘要、两个Agent ID/终端、构建回执与Lock/资格摘要、连接/fixture配置回执。作者核对来源提交和实际安装文件摘要一致后才进入第6步。新摘要的验收在这次新构建后进行，不用预检digest冒充新Lock。

## 6. 一次查询，核对事实与可信证据

**入口与顺序：** 第一轮从原版工作台按第4步运行；第二轮只能在第5步维护者回传并核对来源的候选入口运行，不返回原线上页面验证localdiff。取得候选Lock/准入回执后，启动自己可操作的候选Agent并开始服务，发送固定问题。普通合成来源用2026-09；第3步指定fixture用2026-08，不混用。首次失败留request ID/阶段，不重试筛成功；无候选访问权则保持BLOCKED。

**预期输出形态：** 收入、支出、净额及CNY；来源返回0时可显示0，覆盖未知必须保留。隔离修改版还需看到“收支事实／覆盖与待核实”两段，数值仍与工具一致。

| 运行记录中查什么 | 阶段三带来的可操作收益 | 不能误读为 |
| --- | --- | --- |
| hostRequestId、buildId、instanceId、serviceLockDigest及主体/对象摘要 | 知道哪次可信实例代表哪个授权主体，查哪个对象 | 把SSO owner当银行客户；hash可反查姓名 |
| period与本次输入一致（普通来源2026-09／指定fixture2026-08）、parametersDigest | 修改期间要重新判断，旧ALLOW不是新查询许可证 | 模型可以改可信上下文 |
| ENTRY／RETURN与effectiveExpiresAt | 查询前核实、回来再次核实；撤权后未释放结果阻断 | 已发送请求可以撤回；原子撤权 |
| PROJECTION、CONTRACT_FIELDS_ONLY、resultDigest | 只把合规字段和对应结果交给模型，便于核对错对象/错期间 | SHA是签名或WORM |
| authorizationBasis、bankPolicyVersion | 明示本地合成策略+银行会话身份；银行版本缺失写UNKNOWN | 本地policyDigest是银行授权快照 |
| 审计失败与providerDispatch | 前审计失败不发查询；后失败不释放且不假称没查过 | 故障介质保证写入；SDK调用等于网络次数 |

**通过：** 事实、月份、来源和当前请求一致；正常路径三阶段ALLOW，结果只在投影通过后释放；模型正文可读或明确采集权限/缺口。不把工具HTTP200代替整条业务完成。缺参数、格式错误、授权过期、来源异常、审计故障分别看原因，不统称无权限。

**已有证据边界：** 阶段三普通SSO两端各一次查询成功，正常停止后历史可读；撤权、审计故障等负例由冻结合成fixture验证，不要求新人更改线上授权去重做攻击实验。静态文档检查也不算又执行了一次业务。

## 7. 发布版本、停止、历史和维护

**输入：** 第6步候选业务验收结果、候选Lock/request、作者改动及首败。第5步是候选安装交接，本步才是验收后的正式发布申请，不倒置依赖。

**交付维护者：** 业务目标与边界、Skill及案例diff、H/B与方法版本、兼容/影响结果、测试命令及原始结果、两端Lock与request ID、未覆盖项。新Provider还需工厂注册、依赖与权限要求；新Host需认证/生命周期/观测协议证明。通过既定审阅与受管准入后再进入目录复用，不手改线上目录或资格。

**发布责任：** 维护者冻结制品/资格/配置、检查排空与空间、保留当前可执行回滚后受控发布。普通作者不因会编辑Skill就获得发布权限。旧Agent不自动升级；新版本构建新Agent，旧记录不重写。

**页面验证：** 停止自己的实例，等待状态STOPPED，再刷新历史，按原平台历史权限查看同一request与模型正文。查询授权决定是否可获取/接收新数据；历史查看权决定是否可读已保存内容。停止Agent不撤回人员权限，查询撤权也不自动删除历史权；这是本合成试点政策，不是生产银行默认。

**通过：** 历史读取没有新业务查询或模型生成，权限检查单独说明；列表敏感字段也受权限约束。页面0调用只是读取路径口径，非独立全网计数。没有正文查看权应明确拒绝，不暗中建立新银行会话。保存旧证据与当前任务归属一致。

**排障：** 容量徽标短暂滞后先核对卡片和刷新；停止失败保留operationId，不强杀或停他人。会话结束错误和受管实例停止是不同步骤，分别记录。没有可信恢复证据的删除不列为教程默认动作。

## 缺零件时：Provider与Host开发

**方法作者：** 主线仅方法正文和案例；不能凭Skill声明新权限或接口。[旧方法UI参考](developer-guide.md#section-45)保留，但先检查本能力固定绑定限制。

**Java Provider开发者：** 先确认现有合同能否表达新来源，不能表达才评审能力版本。改`providers/non-ai-banking`的独立适配实现或新增对应模块；复用`FinanceProvider`、`ManagedProviderFactoryV01`，提供`META-INF/services`注册、来源身份映射及错误语义。参考[现有工厂](source:provider)与[测试](source:provider-test)。在Harness根运行已存在的参考测试：

```bash
cd "$HARNESS_DIR"
mvn -B -ntp -pl providers/non-ai-banking -am \
  -Dtest=CashflowProviderTest,NonAiBankingFactoryTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

预期目标两测试类实际执行、失败/错误0，核对该模块`target/surefire-reports`；依赖模块无匹配不算失败，但目标未执行不能算通过。新增实现要加自己的测试类及命令，不只跑旧类。交付源码、合同/来源映射、工厂注册、构建依赖和负例给维护者，受管目录/安装资格接纳后才能作为新零件复用。页面不执行上传的任意Provider代码。

**Runtime／Host接入者：** 参考[LocalAgentHost](source:host)、[ReadOnlyProviders](source:wiring)、[BankAccess](source:bank)、[原生Runtime接线](source:runtime)。入口是Builder的`templates/local-readonly-service`及`component_plan.py`、`managed_runtime_support.py`，不是新增通用Router。可从独立本地工作台生成现有Host，按原`workbench` CLI运行（工作目录Builder根）：

```bash
cd "$BUILDER_DIR"
PYTHONPATH=src:../base/src python -m finance_solution_builder.workbench \
  --harness-repo "$HARNESS_DIR" --port 18158 --data-dir "$TUTORIAL_DIR/studio"
```

这会启动本地开发工作台，不是自动配置银行/模型或公开SSO。端口须空闲；运行前需要维护者给出该路线的依赖和合成环境配置，禁止借线上秘密。从页面生成Host后按Lock核对其启动入口，不编一个通用`java -jar`绕过安装。

新Host必须交付：可信上下文来源、实际Provider链、模型前检查、会话与迟到结果处理、排空协议、观测/快照及当前Collector资格。先做受影响入口定向回归；没有证据的Runtime标未验证，不能仅增加下拉选项。参考[原架构、API及版本历史](developer-guide.md)。

## 交付是否完成

记录实际改文件、重复代码、人工步骤、求助次数、首次失败、修改到验证时间与缓存/磁盘条件。两端原本已共享，不能制造旧重复实现或编造提效比例。

完成标准：取得材料后能按顺序走到事实与证据、能提交一个真实方法改动和案例、可定位准入交接人、停止后按权读历史。**公开指南目前不能独自解决私仓授权和维护者接纳渠道；这两项仍需团队提供，未参与开发者的独立接入尚待验收。** 不把文档预览通过等同新人或业务验收通过。
