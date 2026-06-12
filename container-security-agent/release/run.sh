#!/usr/bin/env bash
# Container Security Agent — 容器一键启动脚本
# 如果非 bash 环境（如 sh/dash），自动重新调用 bash 执行
if [ -z "${BASH_VERSION:-}" ]; then
    exec bash "$0" "$@"
fi
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BACKEND_DIR="$SCRIPT_DIR/container-security-agent-backend-1.0.0"
cd "$BACKEND_DIR"
exec ./start.sh "$@"
