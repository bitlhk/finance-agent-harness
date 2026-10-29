#!/bin/bash

# 设置 Conda 环境路径（根据你的 Linux 环境修改）
CONDA_PYTHON="/opt/miniconda3/envs/finance-guardrail/bin/python"

# 如果上方路径不正确，可以通过环境变量传入：
# CONDA_PYTHON=/xxx/bin/python ./start.sh
if [ -n "$CONDA_PYTHON" ]; then
    PYTHON_CMD="$CONDA_PYTHON"
else
    PYTHON_CMD="/opt/miniconda3/envs/finance-guardrail/bin/python"
fi

# 切换到脚本所在目录
cd "$(dirname "$0")"

# 后台启动服务，日志输出到 logs/server.log
mkdir -p logs
nohup "$PYTHON_CMD" server.py > logs/server.log 2>&1 &

echo "服务已后台启动 (PID: $!)"
echo "日志: tail -f logs/server.log"
