# 内容过滤配置加密

`policy/content_filter_config.json` 保留文件名，但现在仅包含 AES-256-GCM 密文封装
（格式版本、随机 nonce、密文及认证标签），不再包含明文锚点或类别。
加载器只接受加密格式，不自动回退读取明文。解密只在内存中进行。

## 密钥

默认位置是当前运行用户的 `~/.config/guardrail-jiuwen/content-filter.key`。
本机为 `C:\Users\maste\.config\guardrail-jiuwen\content-filter.key`；目录权限限制为
当前用户与 SYSTEM。文件是 32 字节随机密钥，不是密码字符串。

可通过环境变量 `GUARDRAIL_CONTENT_FILTER_KEY_FILE` 指定其他位置。
该变量仅保存路径。不要将密钥复制进项目、镜像、日志或提交到版本控制。
部署前应通过独立的秘密管理渠道备份和分发密钥；丢失密钥将无法恢复配置。

Docker 部署需将密钥文件只读挂载至例如 `/run/secrets/content-filter.key`，并设置
`GUARDRAIL_CONTENT_FILTER_KEY_FILE=/run/secrets/content-filter.key`。
现有 Dockerfile 会复制密文配置，但不会复制用户目录中的密钥。
以其他操作系统用户运行服务时也需要显式提供可读取的密钥路径。

## 后续编辑

在项目之外的受控位置维护明文 JSON，然后运行：

```powershell
python -m adapters.guardrail_runtime.policy_crypto C:\secure-edit\content_filter_config.json policy\content_filter_config.json
```

命令使用现有密钥，生成新 nonce，验证解密结果与输入逐字节相同后原子替换目标；
临时文件也只有密文。它不会删除外部编辑源文件，请按自身保留策略管理。
不要对已加密的文件重复执行原地加密。

加载器支持密文文件修改及原子替换事件。密钥缺失、错误、密文损坏会拒绝加载：
启动时报错；重载时报错并保留已有策略，不提交部分更新。
注意：现有引擎的 Embedding 配置/缓存只在初始化时构建，修改锚点或阈值后仍应
重启服务使引擎采用新配置；本次只改加密存储与策略读取，没有重构引擎热更新。

## 保护范围

本次仅加密这一份内容过滤配置。其他规则、提示词、upstream、测试、文档或历史
副本中可能仍包含类似词句，没有批量清除。加密避免仅扫描项目文件直接读出该配置，
不能防止有权读取密钥和进程内存的人解密，也不会改变外部 Embedding API 的数据发送行为。
