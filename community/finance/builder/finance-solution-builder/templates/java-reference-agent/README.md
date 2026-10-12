# @@DISPLAY_NAME@@

该 Candidate 由 Finance Solution Builder 从 `solution.yaml` 自动装配。

## 启动

```bash
java -jar target/@@SOLUTION_ID@@-all.jar
```

- `GET /health`：候选服务就绪状态；
- `GET /api/v1/previsit?customerRef=...`：调用真实 Employee Agent Wealth Provider；
- 未注入 EA 环境变量时，服务仍可启动，但访前查询明确返回配置缺失，不伪造业务数据。

部署前复制 `.env.example` 并由 Secret Manager 注入真实值。任何密钥都不应进入锁文件、报告或代码仓。
