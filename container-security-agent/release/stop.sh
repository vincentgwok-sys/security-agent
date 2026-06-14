#!/usr/bin/env bash
# Container Security Agent — 停止脚本
# 按端口查找并停止正在运行的 Agent 进程
if [ -z "${BASH_VERSION:-}" ]; then
    exec bash "$0" "$@"
fi
set -euo pipefail

PORT="${1:-8080}"

# 查找占用端口的 PID
find_pid() {
    # 优先用 lsof
    if command -v lsof &>/dev/null; then
        lsof -ti:"$PORT" 2>/dev/null || true
    # 容器环境常用 fuser
    elif command -v fuser &>/dev/null; then
        fuser "$PORT/tcp" 2>/dev/null | tr -d ' ' || true
    # 通用方式：ss + awk
    elif command -v ss &>/dev/null; then
        ss -tlnp 2>/dev/null | grep ":$PORT " | grep -oP 'pid=\K[0-9]+' || true
    # 最后的兜底：netstat
    elif command -v netstat &>/dev/null; then
        netstat -tlnp 2>/dev/null | grep ":$PORT " | awk '{print $NF}' | grep -oP '[0-9]+' || true
    else
        echo "[WARN] 无法定位进程，请手动检查端口 $PORT"
        return 1
    fi
}

PID=$(find_pid) || {
    echo "[INFO] 端口 $PORT 上没有运行中的 Agent"
    exit 0
}

if [ -z "$PID" ]; then
    echo "[INFO] 端口 $PORT 上没有运行中的 Agent"
    exit 0
fi

echo "[INFO] 正在停止端口 $PORT 上的进程 (PID: $PID)..."

# 先尝试优雅终止
kill "$PID" 2>/dev/null || true

# 等待最多 5 秒
for i in 1 2 3 4 5; do
    if kill -0 "$PID" 2>/dev/null; then
        sleep 1
    else
        echo "[INFO] Agent 已停止 (PID: $PID)"
        exit 0
    fi
done

# 仍未退出，强制终止
echo "[INFO] 进程未响应，强制终止 (SIGKILL)..."
kill -9 "$PID" 2>/dev/null || true
sleep 1

if kill -0 "$PID" 2>/dev/null; then
    echo "[ERROR] 无法停止进程 PID: $PID"
    exit 1
fi

echo "[INFO] Agent 已停止 (PID: $PID)"
