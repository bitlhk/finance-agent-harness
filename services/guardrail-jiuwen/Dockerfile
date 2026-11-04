# guardrail-jiuwen Dockerfile
FROM python:3.12-slim

WORKDIR /app

# 安装系统依赖（spaCy 需要）
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    && rm -rf /var/lib/apt/lists/*

# 复制依赖文件并安装
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# 复制项目代码
COPY adapters/ adapters/
COPY policy/ policy/
COPY upstream/ upstream/
COPY pyproject.toml .
COPY .env.example .env.example

# 暴露端口
EXPOSE 8000

# 环境变量默认值
ENV GUARDRAIL_POLICY_DIR=/app/policy
ENV GUARDRAIL_PORT=8000
ENV GUARDRAIL_AUTO_RELOAD=true
ENV GUARDRAIL_ENABLE_PII=true
ENV GUARDRAIL_ENABLE_LLM_DEFENSE=true
ENV GUARDRAIL_ENABLE_CONTENT_FILTER=false
ENV GUARDRAIL_API_KEY=your-secret-key-here

# 启动命令
CMD ["python", "-m", "uvicorn", "upstream.server:app", "--host", "0.0.0.0", "--port", "8000"]
