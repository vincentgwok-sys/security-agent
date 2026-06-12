@echo off
setlocal enabledelayedexpansion
:: ============================================================
:: Container Security Agent — 发布打包脚本 (Windows)
:: 用法: build-packages.bat
:: 输出: release\container-security-agent-backend-<ver>.tar.gz
::       release\container-security-agent-frontend-<ver>.tar.gz
:: ============================================================

set "SCRIPT_DIR=%~dp0"
cd /d "%SCRIPT_DIR%"

:: ── 1. 提取版本号 ──
echo ==> 读取 pom.xml 版本号...
set COUNT=0
for /f "tokens=2 delims=<>" %%a in ('findstr /C:"<version>" pom.xml') do (
    set /a COUNT+=1
    if !COUNT!==2 set "RAW_VERSION=%%a" && goto :version_found
)
:version_found
:: 去掉 -SNAPSHOT
set "VERSION=%RAW_VERSION:-SNAPSHOT=%"
echo     版本: %VERSION%

:: ── 2. 清理 ──
echo ==> 清理 release\ 目录...
if exist release rmdir /s /q release
mkdir release

:: ── 3. 构建前端 ──
echo ==> 构建前端...
cd frontend
if not exist node_modules (
    echo     npm install...
    call npm install --silent
)
call npm run build
cd "%SCRIPT_DIR%"
echo     前端构建完成

:: ── 4. 构建后端 ──
echo ==> 构建后端 (mvn clean package -DskipTests)...
call mvn clean package -DskipTests -q
echo     后端构建完成

:: ── 5. 打包后端 ──
echo ==> 打包后端...
set "BACKEND_DIR=release\container-security-agent-backend-%VERSION%"
mkdir "%BACKEND_DIR%"

:: 复制 JAR
copy "target\container-security-agent-%RAW_VERSION%.jar" "%BACKEND_DIR%\container-security-agent-%VERSION%.jar" >nul

:: 复制配置文件
copy "src\main\resources\application-example.yml" "%BACKEND_DIR%\" >nul

:: 复制 skills 和 rules
xcopy "skills" "%BACKEND_DIR%\skills\" /E /I /Q >nul
xcopy "rules" "%BACKEND_DIR%\rules\" /E /I /Q >nul

:: ── 生成后端 start.bat ──
(
echo @echo off
echo setlocal enabledelayedexpansion
echo set "SCRIPT_DIR=%%~dp0"
echo.
echo echo ============================================
echo echo  Container Security Agent ^(后端^)
echo echo ============================================
echo.
echo :: ── 定位 Java ──
echo :: 优先级: JAVA_HOME ^> .java_home 文件 ^> PATH 中的 java
echo set "JAVA_BIN="
echo.
echo :: 1^) 检查 JAVA_HOME 环境变量
echo if defined JAVA_HOME ^(
echo     if exist "%%JAVA_HOME%%\bin\java.exe" ^(
echo         set "JAVA_BIN=%%JAVA_HOME%%\bin\java.exe"
echo         echo [INFO] 使用 JAVA_HOME: %%JAVA_HOME%%
echo     ^)
echo ^)
echo.
echo :: 2^) 检查本地配置文件 .java_home
echo if not defined JAVA_BIN ^(
echo     if exist "%%SCRIPT_DIR%%.java_home" ^(
echo         for /f "usebackq delims=" %%%%a in ^("^%%SCRIPT_DIR%%.java_home"^) do ^(
echo             if not defined JAVA_BIN ^(
echo                 set "CUSTOM_HOME=%%%%a"
echo                 set "CUSTOM_HOME=!CUSTOM_HOME: =!"
echo                 if exist "!CUSTOM_HOME!\bin\java.exe" ^(
echo                     set "JAVA_BIN=!CUSTOM_HOME!\bin\java.exe"
echo                     echo [INFO] 使用 .java_home: !CUSTOM_HOME!
echo                 ^)
echo             ^)
echo         ^)
echo     ^)
echo ^)
echo.
echo :: 3^) 如果前两步都走到这里并且 JAVA_BIN 仍为空，尝试 PATH
echo if not defined JAVA_BIN ^(
echo     where /q java ^&^& set "JAVA_BIN=java"
echo ^)
echo.
echo if not defined JAVA_BIN ^(
echo     echo [WARN] .java_home 存在但路径无效，尝试下一级…
echo ^)
echo.
echo :: 最终检查
echo if not defined JAVA_BIN ^(
echo     echo.
echo     echo [ERROR] 未找到 Java ^(JDK 21+^)
echo     echo.
echo     echo 请通过以下任一方式指定 Java 路径：
echo     echo   方式一（环境变量）：set JAVA_HOME=C:\path\to\jdk-21
echo     echo   方式二（本地文件）：echo C:\path\to\jdk-21 ^> .java_home
echo     echo.
echo     echo 当前 .java_home 文件位置: %%SCRIPT_DIR%%.java_home
echo     pause
echo     exit /b 1
echo ^)
echo.
echo :: 创建运行时目录
echo if not exist "%%SCRIPT_DIR%%logs" mkdir "%%SCRIPT_DIR%%logs"
echo if not exist "%%SCRIPT_DIR%%reports" mkdir "%%SCRIPT_DIR%%reports"
echo.
echo :: 首次运行：复制配置模板
echo if not exist "%%SCRIPT_DIR%%application.yml" ^(
echo     echo [INFO] 首次运行，从 application-example.yml 复制配置模板...
echo     copy "%%SCRIPT_DIR%%application-example.yml" "%%SCRIPT_DIR%%application.yml" ^>nul
echo     echo [INFO] ^>^^>^^> 请编辑 application.yml 填入你的 API Key，然后重新运行 start.bat ^<^^<^^<
echo     pause
echo     exit /b 0
echo ^)
echo.
echo echo 启动服务: http://localhost:8080
echo echo 日志目录: %%SCRIPT_DIR%%logs\
echo echo 报告目录: %%SCRIPT_DIR%%reports\
echo echo 按 Ctrl+C 停止服务
echo echo.
echo.
echo "%%JAVA_BIN%%" -jar "%%SCRIPT_DIR%%container-security-agent-%VERSION%.jar"
echo pause
) > "%BACKEND_DIR%\start.bat"

:: ── 生成后端 start.sh (PowerShell 写入 Unix 换行符) ──
powershell -Command "$lf=\"`n\"; $s='#!/usr/bin/env bash'+$lf+'if [ -z \"${BASH_VERSION:-}\" ]; then exec bash \"$0\" \"$@\"; fi'+$lf+'set -euo pipefail'+$lf+'SCRIPT_DIR=\"$(cd \"$(dirname \"$0\")\" && pwd)\"'+$lf+''+$lf+'USER_JDK=\"\"'+$lf+'USER_API_KEY=\"\"'+$lf+'USER_PORT=\"\"'+$lf+''+$lf+'show_help() {'+$lf+'  echo \"用法: ./start.sh [选项]\"'+$lf+'  echo \"\"'+$lf+'  echo \"选项:\"'+$lf+'  echo \"  --jdk <path>      指定 Java 可执行文件路径\"'+$lf+'  echo \"  --api-key <key>   指定 AI API Key\"'+$lf+'  echo \"  --port <port>     指定服务端口，默认 8080\"'+$lf+'  echo \"  --help, -h        显示帮助\"'+$lf+'}'+$lf+'while [[ $# -gt 0 ]]; do'+$lf+'  case \"$1\" in'+$lf+'    --jdk)       USER_JDK=\"$2\"; shift 2 ;;'+$lf+'    --api-key)   USER_API_KEY=\"$2\"; shift 2 ;;'+$lf+'    --port)      USER_PORT=\"$2\"; shift 2 ;;'+$lf+'    --help|-h)   show_help; exit 0 ;;'+$lf+'    *)           echo \"未知参数: $1\"; show_help; exit 1 ;;'+$lf+'  esac'+$lf+'done'+$lf+''+$lf+'echo \"============================================\"'+$lf+'echo \" Container Security Agent (后端)\"'+$lf+'echo \"============================================\"'+$lf+''+$lf+'# 定位 Java — 优先级: --jdk > JAVA_HOME > .java_home > PATH'+$lf+'find_java() {'+$lf+'  if [ -n \"$USER_JDK\" ]; then'+$lf+'    if [ ! -e \"$USER_JDK\" ]; then echo \"[ERROR] --jdk 路径不存在: $USER_JDK\" >&2; return 1; fi'+$lf+'    if [ ! -x \"$USER_JDK\" ]; then echo \"[ERROR] --jdk 路径不可执行: $USER_JDK\" >&2; return 1; fi'+$lf+'    echo \"$USER_JDK\"'+$lf+'    echo \"[INFO] 使用 --jdk 参数: $USER_JDK\" >&2'+$lf+'    return 0'+$lf+'  fi'+$lf+'  if [ -n \"${JAVA_HOME:-}\" ] && [ -x \"$JAVA_HOME/bin/java\" ]; then'+$lf+'    echo \"$JAVA_HOME/bin/java\"'+$lf+'    echo \"[INFO] 使用 JAVA_HOME: $JAVA_HOME\" >&2'+$lf+'    return 0'+$lf+'  fi'+$lf+'  if [ -f \"$SCRIPT_DIR/.java_home\" ]; then'+$lf+'    local h; h=$(head -1 \"$SCRIPT_DIR/.java_home\" | tr -d \"\r\" | xargs)'+$lf+'    if [ -n \"$h\" ] && [ -x \"$h/bin/java\" ]; then'+$lf+'      echo \"$h/bin/java\"'+$lf+'      echo \"[INFO] 使用 .java_home: $h\" >&2'+$lf+'      return 0'+$lf+'    fi'+$lf+'    echo \"[WARN] .java_home 存在但路径无效\" >&2'+$lf+'  fi'+$lf+'  local pj; pj=$(command -v java 2>/dev/null || true)'+$lf+'  if [ -n \"$pj\" ]; then'+$lf+'    echo \"$pj\"'+$lf+'    echo \"[INFO] 使用 PATH 中的 java: $pj\" >&2'+$lf+'    return 0'+$lf+'  fi'+$lf+'  return 1'+$lf+'}'+$lf+''+$lf+'JAVA_BIN=$(find_java) || {'+$lf+'  echo \"\"'+$lf+'  echo \"[ERROR] 未找到 Java (JDK 21+)\"'+$lf+'  echo \"\"'+$lf+'  echo \"请通过以下任一方式指定 Java 路径：\"'+$lf+'  echo \"  方式一（CLI 参数）：./start.sh --jdk /path/to/jdk-21/bin/java\"'+$lf+'  echo \"  方式二（环境变量）：export JAVA_HOME=/path/to/jdk-21\"'+$lf+'  echo \"  方式三（本地文件）：echo /path/to/jdk-21 > .java_home\"'+$lf+'  exit 1'+$lf+'}'+$lf+''+$lf+'mkdir -p \"$SCRIPT_DIR/logs\" \"$SCRIPT_DIR/reports\"'+$lf+''+$lf+'if [ ! -f \"$SCRIPT_DIR/application.yml\" ]; then'+$lf+'  echo \"[INFO] 首次运行，从 application-example.yml 复制配置模板...\"'+$lf+'  cp \"$SCRIPT_DIR/application-example.yml\" \"$SCRIPT_DIR/application.yml\"'+$lf+'  echo \"[INFO] >>> 请编辑 application.yml 填入你的 API Key，然后重新运行 start.sh <<<\"'+$lf+'  exit 0'+$lf+'fi'+$lf+''+$lf+'JAVA_OPTS=\"\"'+$lf+'if [ -n \"$USER_API_KEY\" ]; then JAVA_OPTS=\"$JAVA_OPTS -Dspring.ai.openai.api-key=$USER_API_KEY\"; fi'+$lf+'if [ -n \"$USER_PORT\" ]; then JAVA_OPTS=\"$JAVA_OPTS -Dserver.port=$USER_PORT\"; fi'+$lf+''+$lf+'echo \"启动服务: http://localhost:${USER_PORT:-8080}\"'+$lf+'echo \"日志目录: $SCRIPT_DIR/logs/\"'+$lf+'echo \"报告目录: $SCRIPT_DIR/reports/\"'+$lf+'echo \"按 Ctrl+C 停止服务\"'+$lf+'echo \"\"'+$lf+''+$lf+'exec \"$JAVA_BIN\" $JAVA_OPTS -jar \"$SCRIPT_DIR/container-security-agent-%VERSION%.jar\"'; [System.IO.File]::WriteAllText('%BACKEND_DIR%\start.sh', $s)"

:: ── 生成后端 README.txt ──
(
echo ============================================================
echo  Container Security Agent 后端 v%VERSION%
echo ============================================================
echo.
echo 环境要求
echo --------
echo   • JDK 21 或更高版本
echo     脚本按以下优先级查找 Java：
echo       1^) JAVA_HOME 环境变量
echo       2^) .java_home 本地配置文件（位于本目录）
echo       3^) PATH 中的 java
echo     多 JDK 环境可创建 .java_home 文件或将 JAVA_HOME 指向 JDK 21 ^+
echo.
echo 快速开始
echo --------
echo   1. 首次运行：双击 start.bat (Windows) 或执行 ./start.sh (Linux/Mac)
echo      脚本会自动从 application-example.yml 复制配置文件。
echo   2. 编辑 application.yml，填入你的 API Key。
echo   3. 再次运行启动脚本。
echo   4. 浏览器打开 http://localhost:8080 即可使用。
echo.
echo 目录说明
echo --------
echo   application-example.yml  配置模板（参考用，不要直接修改）
echo   application.yml          你的实际配置（首次运行自动生成）
echo   skills/                  安全检测技能定义（JSON）
echo   rules/                   命令规则定义（JSON）
echo   logs/                    运行日志（自动生成）
echo   reports/                 检测报告（自动生成）
echo.
echo 修改端口
echo --------
echo   编辑 application.yml，修改 server.port 的值。
echo   也可以设置环境变量 SERVER_PORT=9090。
echo.
echo 常见问题
echo --------
echo   Q: 启动报错 "Port 8080 already in use"
echo   A: 修改端口，或先关闭占用 8080 端口的程序。
echo.
echo   Q: 如何更新 API Key？
echo   A: 编辑 application.yml 中 spring.ai.openai.api-key 的值。
) > "%BACKEND_DIR%\README.txt"

:: ── 打包 tar.gz ──
echo     创建 backend tar.gz...
cd release
tar -czf "container-security-agent-backend-%VERSION%.tar.gz" "container-security-agent-backend-%VERSION%"
rmdir /s /q "%BACKEND_DIR%"
cd "%SCRIPT_DIR%"
echo     后端打包完成

:: ── 6. 打包前端 ──
echo ==> 打包前端...
set "FRONTEND_DIR=release\container-security-agent-frontend-%VERSION%"
mkdir "%FRONTEND_DIR%"

:: 复制前端构建产物
xcopy "frontend\dist\*" "%FRONTEND_DIR%\" /E /Q >nul

:: ── 生成前端 start.bat ──
(
echo @echo off
echo setlocal
echo set "SCRIPT_DIR=%%~dp0"
echo.
echo echo ============================================
echo echo  Container Security Agent ^(前端^)
echo echo ============================================
echo.
echo :: 检查 Python
echo set "PYTHON="
echo where python ^>nul 2^>^&1 ^&^& set "PYTHON=python"
echo if not defined PYTHON where python3 ^>nul 2^>^&1 ^&^& set "PYTHON=python3"
echo if not defined PYTHON ^(
echo     echo [ERROR] 未找到 Python 3。请安装 Python 3 并加入 PATH。
echo     echo         也可以使用任意 HTTP 服务器（nginx、Apache 等）托管此目录。
echo     pause
echo     exit /b 1
echo ^)
echo.
echo echo 启动前端: http://localhost:5173
echo echo 注意: 前端需要后端在 http://localhost:8080 运行
echo echo       如果前后端不同源，请配置反向代理或 CORS
echo echo 按 Ctrl+C 停止服务
echo echo.
echo.
echo cd /d "%%SCRIPT_DIR%%" ^&^& %%PYTHON%% -m http.server 5173
echo pause
) > "%FRONTEND_DIR%\start.bat"

:: ── 生成前端 start.sh (PowerShell 写入 Unix 换行符) ──
powershell -Command "$lf=\"`n\"; $s='#!/usr/bin/env bash'+$lf+'if [ -z \"${BASH_VERSION:-}\" ]; then exec bash \"$0\" \"$@\"; fi'+$lf+'set -euo pipefail'+$lf+'SCRIPT_DIR=\"$(cd \"$(dirname \"$0\")\" && pwd)\"'+$lf+''+$lf+'echo \"============================================\"'+$lf+'echo \" Container Security Agent (前端)\"'+$lf+'echo \"============================================\"'+$lf+''+$lf+'PYTHON=\"\"'+$lf+'for py in python3 python; do'+$lf+'  if command -v \"$py\" &>/dev/null; then'+$lf+'    PYTHON=\"$py\"'+$lf+'    break'+$lf+'  fi'+$lf+'done'+$lf+''+$lf+'if [ -z \"$PYTHON\" ]; then'+$lf+'  echo \"[ERROR] 未找到 Python 3。请安装 Python 3 并加入 PATH。\"'+$lf+'  echo \"        也可以使用任意 HTTP 服务器（nginx、Apache 等）托管此目录。\"'+$lf+'  exit 1'+$lf+'fi'+$lf+''+$lf+'echo \"启动前端: http://localhost:5173\"'+$lf+'echo \"注意: 前端需要后端在 http://localhost:8080 运行\"'+$lf+'echo \"      如果前后端不同源，请配置反向代理或 CORS\"'+$lf+'echo \"按 Ctrl+C 停止服务\"'+$lf+'echo \"\"'+$lf+''+$lf+'cd \"$SCRIPT_DIR\" && \"$PYTHON\" -m http.server 5173'; [System.IO.File]::WriteAllText('%FRONTEND_DIR%\start.sh', $s)"

:: ── 生成前端 README.txt ──
(
echo ============================================================
echo  Container Security Agent 前端 v%VERSION%
echo ============================================================
echo.
echo 环境要求
echo --------
echo   • Python 3（用于内置 HTTP 服务器）
echo     或任意 HTTP 服务器（nginx、Apache、Node.js serve 等）
echo.
echo 快速开始
echo --------
echo   1. 确保后端已启动在 http://localhost:8080
echo   2. 双击 start.bat (Windows) 或执行 ./start.sh (Linux/Mac)
echo   3. 浏览器打开 http://localhost:5173
echo.
echo 独立部署
echo --------
echo   如果后端不在 localhost:8080，或需要部署到生产环境：
echo.
echo   方案 A：使用后端自带的前端
echo     后端 JAR 已内嵌前端文件，直接访问 http://localhost:8080 即可。
echo     无需单独使用此前端包。
echo.
echo   方案 B：配置反向代理（nginx 示例）
echo     location / {
echo         root /path/to/frontend-files;
echo         try_files $uri $uri/ /index.html;
echo     }
echo     location /api/ {
echo         proxy_pass http://backend:8080;
echo     }
echo.
echo   方案 C：修改 Vite 构建配置
echo     编辑 frontend/vite.config.js 中的 server.proxy.target，
echo     重新 npm run build 后部署。
echo.
echo 目录说明
echo --------
echo   index.html    入口页面
echo   assets/       JS 和 CSS 静态资源（Vite 构建产物）
) > "%FRONTEND_DIR%\README.txt"

:: ── 打包 tar.gz ──
echo     创建 frontend tar.gz...
cd release
tar -czf "container-security-agent-frontend-%VERSION%.tar.gz" "container-security-agent-frontend-%VERSION%"
rmdir /s /q "%FRONTEND_DIR%"
echo     前端打包完成

:: ── 6.5 生成 run.sh ──
echo ==> 生成 run.sh 一键启动脚本...
powershell -Command "$lf=\"`n\"; $s='#!/usr/bin/env bash'+$lf+'if [ -z \"${BASH_VERSION:-}\" ]; then exec bash \"$0\" \"$@\"; fi'+$lf+'set -euo pipefail'+$lf+'SCRIPT_DIR=\"$(cd \"$(dirname \"$0\")\" && pwd)\"'+$lf+'BACKEND_DIR=\"$SCRIPT_DIR/container-security-agent-backend-%VERSION%\"'+$lf+'cd \"$BACKEND_DIR\"'+$lf+'exec ./start.sh \"$@\"'; [System.IO.File]::WriteAllText('release\run.sh', $s)"

:: ── 7. 完成 ──
echo.
echo ============================================
echo   打包完成!
echo ============================================
echo   release\container-security-agent-backend-%VERSION%.tar.gz
echo   release\container-security-agent-frontend-%VERSION%.tar.gz
echo   release\run.sh
echo.
dir release\*.tar.gz release\run.sh
