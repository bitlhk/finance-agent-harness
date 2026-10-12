# 生成 Agent 的真实三端页面 V0.1

本轮修改 `browser/experience.js` 和 `examples/agent-experience/`，不是替换Builder的device-preview示意图。
手机、电脑、ATM使用同一份真实对话、AG-UI客户端、来源卡片和体验网关，没有新增第二个金融宿主或Runtime。

## 终端表现

| 终端 | 已实现的页面行为 | 保留边界 |
| --- | --- | --- |
| 手机 | 桌面显示414px手机机身，内部是实际可交互页面；小屏/触控手机去框铺满，独立消息滚动，输入区按visualViewport可见高度适配 | 不修改设备缩放；软键盘缩小视口测试不等于真人手机验收；语音仍使用既有服务端配置 |
| 电脑 | 占用完整浏览器窗口，不强制16:9；宽屏并排来源区，小窗口默认折叠，用户显式打开后可关闭并返回原焦点 | 16:9和16:10均为测试尺寸；不增加桌面操作系统打包 |
| ATM | 屏幕边框、大触控入口、首页与结束服务；可主动全屏进入无框界面，内部工具和会话继续工作 | 仅浏览器模拟，不新增卡槽、出钞、PIN等硬件控件；首页只切换视图，结束服务才清除会话 |

全屏由用户点击触发；浏览器拒绝全屏时只提示，不终止业务会话。浏览器不支持全屏API则不显示按钮。
进入/退出全屏只更新窗口控件，不重新运行工具或清空会话。撤权、取消、结束与模型边界复用原有后端检查。
密钥仍在管理/宿主侧，终端只显示已绑定模型；本轮未新增终端模型配置权限。

## 制品与兼容

`npm run build:ui` 生成实际 `ui/app.js`，样式与HTML由原 `service.py` 复制到新Candidate。
原 `service-lock.json` 的 `interface.resources` 记录UI文件摘要；可携带制品仍通过 `run_ui.py` 独立运行。
没有修改Selection、Profile、Capability及授权合同，也不改写旧Agent的UI或Lock。旧Agent需显式构建新版本才获得新版页面。

```bash
npm run build:ui
PYTHONPATH=src:../base/src python engineering/build_terminal_candidates.py \
  --harness <固定Harness工作副本> --output <全新输出目录> --names mobile desktop atm
python engineering/verify_terminal_service.py <输出目录>/mobile/candidate <验证目录>/mobile
python engineering/verify_terminal_service.py <输出目录>/desktop/candidate <验证目录>/desktop
python engineering/verify_terminal_service.py <输出目录>/atm/candidate <验证目录>/atm
```

依赖和独立启动/停止方式沿用[多终端入口](terminal-ui-v1.md)。用例运行真实生成的Java Host和体验网关，
模型与银行为显式受控HTTP端点。测试端点的长回答是排版Fixture，不是实际模型质量证据。

## 验收项

- 核对所有锁定UI文件摘要，原生查询返回精确余额、来源及快照。
- 320/390手机、1366x768、1920x1080及1440x900：页面不溢出、内部滚动、控件可见。
- 小窗口来源面板不默认遮挡；折叠与重开可操作、焦点可回。
- 主动进入和退出全屏，全屏内仍有实际银行读取和模型/工具调用。
- ATM首页可继续查询；结束后前端事实和服务端会话均清除，重新开始为空会话。
- 源调用途中撤权与取消仍阻断下一次模型请求；全部模拟银行业务写入为零。
- 保留Builder预览的独立回归，不用预览截图替代上述实际Candidate验收。

真实模型、完整模拟器、实际手机软键盘/语音、真实客户授权、真人独立复跑仍各自验收，不由本次受控页面测试代替。

2026-09-10实现验证：Builder/Base单测502通过、2跳过；三种新生成制品的受控验收分别58/61/60项通过；
Builder既有预览188项通过（1440/390/320）；没有新增业务工具。实际截图同时包含长回答与正常事实卡。
首次全量生成执行Maven；仅UI间距收尾后，显式复用已核验二进制重新装配，原有模板/Harness/JAR摘要检查仍执行。
需要使用同样的加速路径时，可为构建脚本显式传 `--reuse-binaries-from <已验证Candidate>`；不满足复用校验时失败，不跳过。
