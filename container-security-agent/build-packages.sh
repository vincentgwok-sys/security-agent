#!/bin/bash
# ============================================================
# Container Security Agent — 发布打包脚本 (Unix)
# 用法: ./build-packages.sh
# 输出: release/container-security-agent-backend-<ver>.tar.gz
#       release/container-security-agent-frontend-<ver>.tar.gz
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# ── 1. 提取版本号 ──
echo "==> 读取 pom.xml 版本号..."
RAW_VERSION=$(grep -A1 '<artifactId>container-security-agent</artifactId>' pom.xml | tail -1 | sed 's/.*<version>\(.*\)<\/version>.*/\1/')
VERSION="${RAW_VERSION//-SNAPSHOT/}"
echo "    版本: $VERSION"

# ── 2. 清理 ──
echo "==> 清理 release/ 目录..."
rm -rf release
mkdir -p release

# ── 3. 构建前端 ──
echo "==> 构建前端..."
cd frontend
if [ ! -d "node_modules" ]; then
  echo "    npm install..."
  npm install --silent
fi
npm run build
cd "$SCRIPT_DIR"
echo "    前端构建完成"

# ── 4. 构建后端 ──
echo "==> 构建后端 (mvn clean package -DskipTests)..."
mvn clean package -DskipTests -q
echo "    后端构建完成"

# ── 5. 打包后端 ──
echo "==> 打包后端..."
BACKEND_DIR="release/container-security-agent-backend-${VERSION}"
mkdir -p "$BACKEND_DIR"

cp "target/container-security-agent-${RAW_VERSION}.jar" \
   "$BACKEND_DIR/container-security-agent-${VERSION}.jar"
cp src/main/resources/application-example.yml "$BACKEND_DIR/"
cp -r skills "$BACKEND_DIR/"
cp -r rules "$BACKEND_DIR/"

# ── 生成后端 start.sh ──
cat > "$BACKEND_DIR/start.sh" << 'STARTEOF'
#!/usr/bin/env bash
# Container Security Agent — 后端启动脚本
# 如果非 bash 环境（如 sh/dash），自动重新调用 bash 执行
if [ -z "${BASH_VERSION:-}" ]; then
    exec bash "$0" "$@"
fi
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# ── 默认值 ──
USER_JDK=""
USER_API_KEY=""
USER_PORT=""

# ── 参数解析 ──
show_help() {
    echo "用法: ./start.sh [选项]"
    echo ""
    echo "选项:"
    echo "  --jdk <path>      指定 Java 可执行文件路径（最高优先级）"
    echo "  --api-key <key>   指定 AI API Key，通过 JVM 系统属性传入"
    echo "  --port <port>     指定服务端口，默认 8080"
    echo "  --help, -h        显示此帮助信息"
    echo ""
    echo "JDK 查找优先级: --jdk > JAVA_HOME 环境变量 > .java_home 文件 > PATH"
    echo ""
    echo "示例:"
    echo "  # 容器中运行"
    echo "  ./start.sh --api-key sk-xxx --jdk /opt/jdk-21/bin/java"
    echo ""
    echo "  # 使用自定义端口"
    echo "  ./start.sh --port 9090 --api-key sk-xxx"
}
while [[ $# -gt 0 ]]; do
    case "$1" in
        --jdk)       USER_JDK="$2"; shift 2 ;;
        --api-key)   USER_API_KEY="$2"; shift 2 ;;
        --port)      USER_PORT="$2"; shift 2 ;;
        --help|-h)   show_help; exit 0 ;;
        *)           echo "未知参数: $1"; show_help; exit 1 ;;
    esac
done

echo "============================================"
echo " Container Security Agent (后端)"
echo "============================================"

# ── 定位 Java ──
# 优先级: --jdk 参数 > JAVA_HOME > .java_home 文件 > PATH
find_java() {
    # 0) CLI --jdk 参数（最高优先级）
    if [ -n "$USER_JDK" ]; then
        if [ ! -e "$USER_JDK" ]; then
            echo "[ERROR] --jdk 指定的路径不存在: $USER_JDK" >&2
            return 1
        fi
        if [ ! -x "$USER_JDK" ]; then
            echo "[ERROR] --jdk 指定的路径不可执行: $USER_JDK" >&2
            return 1
        fi
        echo "$USER_JDK"
        echo "[INFO] 使用 --jdk 参数: $USER_JDK" >&2
        return 0
    fi

    # 1) 检查 JAVA_HOME 环境变量
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
        echo "$JAVA_HOME/bin/java"
        echo "[INFO] 使用 JAVA_HOME: $JAVA_HOME" >&2
        return 0
    fi

    # 2) 检查本地配置文件 .java_home
    if [ -f "$SCRIPT_DIR/.java_home" ]; then
        local custom_home
        custom_home=$(head -1 "$SCRIPT_DIR/.java_home" | tr -d '\r' | xargs)
        if [ -n "$custom_home" ] && [ -x "$custom_home/bin/java" ]; then
            echo "$custom_home/bin/java"
            echo "[INFO] 使用 .java_home: $custom_home" >&2
            return 0
        fi
        echo "[WARN] .java_home 存在但路径无效，尝试下一级…" >&2
    fi

    # 3) 兜底：PATH 中的 java
    local path_java
    path_java=$(command -v java 2>/dev/null || true)
    if [ -n "$path_java" ]; then
        echo "$path_java"
        echo "[INFO] 使用 PATH 中的 java: $path_java" >&2
        return 0
    fi

    return 1
}

JAVA_BIN=$(find_java) || {
    echo ""
    echo "[ERROR] 未找到 Java (JDK 21+)"
    echo ""
    echo "请通过以下任一方式指定 Java 路径："
    echo "  方式一（CLI 参数）：./start.sh --jdk /path/to/jdk-21/bin/java"
    echo "  方式二（环境变量）：export JAVA_HOME=/path/to/jdk-21"
    echo "  方式三（本地文件）：echo /path/to/jdk-21 > .java_home"
    echo ""
    exit 1
}

# 创建运行时目录
mkdir -p "$SCRIPT_DIR/logs" "$SCRIPT_DIR/reports"

# 首次运行：复制配置模板
if [ ! -f "$SCRIPT_DIR/application.yml" ]; then
  echo "[INFO] 首次运行，从 application-example.yml 复制配置模板..."
  cp "$SCRIPT_DIR/application-example.yml" "$SCRIPT_DIR/application.yml"
  echo "[INFO] >>> 请编辑 application.yml 填入你的 API Key，然后重新运行 start.sh <<<"
  exit 0
fi

# ── 构建 JVM 参数 ──
JAVA_OPTS=""
if [ -n "$USER_API_KEY" ]; then
    JAVA_OPTS="$JAVA_OPTS -Dspring.ai.openai.api-key=$USER_API_KEY"
fi
if [ -n "$USER_PORT" ]; then
    JAVA_OPTS="$JAVA_OPTS -Dserver.port=$USER_PORT"
fi

echo "启动服务: http://localhost:${USER_PORT:-8080}"
echo "日志目录: $SCRIPT_DIR/logs/"
echo "报告目录: $SCRIPT_DIR/reports/"
echo "按 Ctrl+C 停止服务"
echo ""

exec "$JAVA_BIN" $JAVA_OPTS -jar "$SCRIPT_DIR/container-security-agent-__VERSION__.jar"
STARTEOF

# 替换版本号占位符
sed -i "s/__VERSION__/${VERSION}/g" "$BACKEND_DIR/start.sh"
chmod +x "$BACKEND_DIR/start.sh"

# ── 生成后端 start.bat ──
cat > "$BACKEND_DIR/start.bat" << 'STARTBAT'
@echo off
setlocal enabledelayedexpansion
set "SCRIPT_DIR=%~dp0"

echo ============================================
echo  Container Security Agent (后端^)
echo ============================================

:: ── 定位 Java ──
:: 优先级: JAVA_HOME > .java_home 文件 > PATH 中的 java
set "JAVA_BIN="

:: 1) 检查 JAVA_HOME 环境变量
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" (
        set "JAVA_BIN=%JAVA_HOME%\bin\java.exe"
        echo [INFO] 使用 JAVA_HOME: %JAVA_HOME%
    )
)

:: 2) 检查本地配置文件 .java_home
if not defined JAVA_BIN (
    if exist "%SCRIPT_DIR%.java_home" (
        for /f "usebackq delims=" %%a in ("%SCRIPT_DIR%.java_home") do (
            if not defined JAVA_BIN (
                set "CUSTOM_HOME=%%a"
                set "CUSTOM_HOME=!CUSTOM_HOME: =!"
                if exist "!CUSTOM_HOME!\bin\java.exe" (
                    set "JAVA_BIN=!CUSTOM_HOME!\bin\java.exe"
                    echo [INFO] 使用 .java_home: !CUSTOM_HOME!
                )
            )
        )
    )
)

:: 3) 兜底：PATH 中的 java
if not defined JAVA_BIN (
    where /q java && set "JAVA_BIN=java"
)

:: 最终检查
if not defined JAVA_BIN (
    echo.
    echo [ERROR] 未找到 Java (JDK 21+)
    echo.
    echo 请通过以下任一方式指定 Java 路径：
    echo   方式一（环境变量）：set JAVA_HOME=C:\path\to\jdk-21
    echo   方式二（本地文件）：echo C:\path\to\jdk-21 ^> .java_home
    echo.
    echo 当前 .java_home 文件位置: %SCRIPT_DIR%.java_home
    pause
    exit /b 1
)

:: 创建运行时目录
if not exist "%SCRIPT_DIR%logs" mkdir "%SCRIPT_DIR%logs"
if not exist "%SCRIPT_DIR%reports" mkdir "%SCRIPT_DIR%reports"

:: 首次运行：复制配置模板
if not exist "%SCRIPT_DIR%application.yml" (
    echo [INFO] 首次运行，从 application-example.yml 复制配置模板...
    copy "%SCRIPT_DIR%application-example.yml" "%SCRIPT_DIR%application.yml"
    echo [INFO] >>> 请编辑 application.yml 填入你的 API Key，然后重新运行 start.bat <<<
    pause
    exit /b 0
)

echo 启动服务: http://localhost:8080
echo 日志目录: %SCRIPT_DIR%logs\
echo 报告目录: %SCRIPT_DIR%reports\
echo 按 Ctrl+C 停止服务
echo.

"%JAVA_BIN%" -jar "%SCRIPT_DIR%container-security-agent-__VERSION__.jar"
pause
STARTBAT

sed -i "s/__VERSION__/${VERSION}/g" "$BACKEND_DIR/start.bat"

# ── 生成后端 README.txt ──
cat > "$BACKEND_DIR/README.txt" << READMEEOF
============================================================
 Container Security Agent 后端 v__VERSION__
============================================================

环境要求
--------
  • JDK 21 或更高版本
    脚本按以下优先级查找 Java：
      1) JAVA_HOME 环境变量
      2) .java_home 本地配置文件（位于本目录）
      3) PATH 中的 java
    多 JDK 环境可创建 .java_home 文件或将 JAVA_HOME 指向 JDK 21 +

快速开始
--------
  1. 首次运行：双击 start.bat (Windows) 或执行 ./start.sh (Linux/Mac)
     脚本会自动从 application-example.yml 复制配置文件。
  2. 编辑 application.yml，填入你的 API Key。
  3. 再次运行启动脚本。
  4. 浏览器打开 http://localhost:8080 即可使用。

目录说明
--------
  application-example.yml  配置模板（参考用，不要直接修改）
  application.yml          你的实际配置（首次运行自动生成）
  skills/                  安全检测技能定义（JSON）
  rules/                   命令规则定义（JSON）
  logs/                    运行日志（自动生成）
  reports/                 检测报告（自动生成）

修改端口
--------
  编辑 application.yml，修改 server.port 的值。
  也可以设置环境变量 SERVER_PORT=9090。

常见问题
--------
  Q: 启动报错 "Port 8080 already in use"
  A: 修改端口，或先关闭占用 8080 端口的程序。

  Q: 如何更新 API Key？
  A: 编辑 application.yml 中 spring.ai.openai.api-key 的值。
READMEEOF

sed -i "s/__VERSION__/${VERSION}/g" "$BACKEND_DIR/README.txt"

# ── 打包 tar.gz ──
echo "    创建 backend tar.gz..."
cd release
tar czf "container-security-agent-backend-${VERSION}.tar.gz" "container-security-agent-backend-${VERSION}"
rm -rf "container-security-agent-backend-${VERSION}"
cd "$SCRIPT_DIR"
echo "    后端打包完成"

# ── 6. 打包前端 ──
echo "==> 打包前端..."
FRONTEND_DIR="release/container-security-agent-frontend-${VERSION}"
mkdir -p "$FRONTEND_DIR"

cp -r frontend/dist/* "$FRONTEND_DIR/"

# ── 生成前端 start.sh ──
cat > "$FRONTEND_DIR/start.sh" << 'STARTEOF'
#!/usr/bin/env bash
# Container Security Agent — 前端启动脚本（静态文件服务）
if [ -z "${BASH_VERSION:-}" ]; then
    exec bash "$0" "$@"
fi
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "============================================"
echo " Container Security Agent (前端)"
echo "============================================"

# 检查 Python
PYTHON=""
for py in python3 python; do
  if command -v "$py" &>/dev/null; then
    PYTHON="$py"
    break
  fi
done

if [ -z "$PYTHON" ]; then
  echo "[ERROR] 未找到 Python 3。请安装 Python 3 并加入 PATH。"
  echo "        也可以使用任意 HTTP 服务器（nginx、Apache 等）托管此目录。"
  exit 1
fi

echo "启动前端: http://localhost:5173"
echo "注意: 前端需要后端在 http://localhost:8080 运行"
echo "      如果前后端不同源，请配置反向代理或 CORS"
echo "按 Ctrl+C 停止服务"
echo ""

cd "$SCRIPT_DIR" && "$PYTHON" -m http.server 5173
STARTEOF

chmod +x "$FRONTEND_DIR/start.sh"

# ── 生成前端 start.bat ──
cat > "$FRONTEND_DIR/start.bat" << 'STARTEOF'
@echo off
setlocal
set "SCRIPT_DIR=%~dp0"

echo ============================================
echo  Container Security Agent (前端^)
echo ============================================

:: 检查 Python
where python >nul 2>&1
if %ERRORLEVEL% EQU 0 (
    set PYTHON=python
) else (
    where python3 >nul 2>&1
    if %ERRORLEVEL% EQU 0 (
        set PYTHON=python3
    )
)

if not defined PYTHON (
    echo [ERROR] 未找到 Python 3。请安装 Python 3 并加入 PATH。
    echo         也可以使用任意 HTTP 服务器（nginx、Apache 等）托管此目录。
    pause
    exit /b 1
)

echo 启动前端: http://localhost:5173
echo 注意: 前端需要后端在 http://localhost:8080 运行
echo       如果前后端不同源，请配置反向代理或 CORS
echo 按 Ctrl+C 停止服务
echo.

cd /d "%SCRIPT_DIR%" && %PYTHON% -m http.server 5173
pause
STARTEOF

# ── 生成前端 README.txt ──
cat > "$FRONTEND_DIR/README.txt" << 'READMEEOF'
============================================================
 Container Security Agent 前端 v__VERSION__
============================================================

环境要求
--------
  • Python 3（用于内置 HTTP 服务器）
    或任意 HTTP 服务器（nginx、Apache、Node.js serve 等）

快速开始
--------
  1. 确保后端已启动在 http://localhost:8080
  2. 双击 start.bat (Windows) 或执行 ./start.sh (Linux/Mac)
  3. 浏览器打开 http://localhost:5173

独立部署
--------
  如果后端不在 localhost:8080，或需要部署到生产环境：

  方案 A：使用后端自带的前端
    后端 JAR 已内嵌前端文件，直接访问 http://localhost:8080 即可。
    无需单独使用此前端包。

  方案 B：配置反向代理（nginx 示例）
    location / {
        root /path/to/frontend-files;
        try_files $uri $uri/ /index.html;
    }
    location /api/ {
        proxy_pass http://backend:8080;
    }

  方案 C：修改 Vite 构建配置
    编辑 frontend/vite.config.js 中的 server.proxy.target，
    重新 npm run build 后部署。

目录说明
--------
  index.html    入口页面
  assets/       JS 和 CSS 静态资源（Vite 构建产物）
READMEEOF

sed -i "s/__VERSION__/${VERSION}/g" "$FRONTEND_DIR/README.txt"

# ── 打包 tar.gz ──
echo "    创建 frontend tar.gz..."
cd release
tar czf "container-security-agent-frontend-${VERSION}.tar.gz" "container-security-agent-frontend-${VERSION}"
rm -rf "container-security-agent-frontend-${VERSION}"
cd "$SCRIPT_DIR"
echo "    前端打包完成"

# ── 6.5 生成 run.sh ──
echo "==> 生成 run.sh 一键启动脚本..."
cat > "release/run.sh" << 'RUNEOF'
#!/usr/bin/env bash
# Container Security Agent — 容器一键启动脚本
# 如果非 bash 环境（如 sh/dash），自动重新调用 bash 执行
if [ -z "${BASH_VERSION:-}" ]; then
    exec bash "$0" "$@"
fi
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BACKEND_DIR="$SCRIPT_DIR/container-security-agent-backend-__VERSION__"
cd "$BACKEND_DIR"
exec ./start.sh "$@"
RUNEOF
sed -i "s/__VERSION__/${VERSION}/g" release/run.sh
chmod +x release/run.sh
echo "    run.sh 生成完成"

# ── 7. 完成 ──
echo ""
echo "============================================"
echo "  打包完成!"
echo "============================================"
echo "  release/container-security-agent-backend-${VERSION}.tar.gz"
echo "  release/container-security-agent-frontend-${VERSION}.tar.gz"
echo ""
ls -lh release/*.tar.gz
