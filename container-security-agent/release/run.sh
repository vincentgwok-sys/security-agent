#!/usr/bin/env bash
# Container Security Agent — 容器一键启动脚本
# 用法: ./run.sh [--api-key sk-xxx] [--jdk /path/to/java] [--port 8080]
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BACKEND_DIR="$SCRIPT_DIR/container-security-agent-backend-1.0.0"
cd "$BACKEND_DIR"
exec ./start.sh "$@"
