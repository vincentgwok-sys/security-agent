#!/usr/bin/env bash
# Container Security Agent — 容器一键启动脚本
# 如果非 bash 环境（如 sh/dash），自动重新调用 bash 执行
if [ -z "${BASH_VERSION:-}" ]; then
    exec bash "$0" "$@"
fi
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BACKEND_DIR="$SCRIPT_DIR/container-security-agent-backend-1.0.0"
BACKEND_TAR="$SCRIPT_DIR/container-security-agent-backend-1.0.0.tar.gz"

# 自动解压（如果尚未解压）
if [ ! -d "$BACKEND_DIR" ]; then
    if [ ! -f "$BACKEND_TAR" ]; then
        echo "[ERROR] 未找到后端包: $BACKEND_TAR"
        exit 1
    fi
    echo "[INFO] 首次运行，正在解压 $BACKEND_TAR ..."
    tar xzf "$BACKEND_TAR" -C "$SCRIPT_DIR"
    echo "[INFO] 解压完成"
fi

cd "$BACKEND_DIR"
exec ./start.sh "$@"
