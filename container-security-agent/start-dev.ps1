$ErrorActionPreference = "Stop"
$ProjectDir = $PSScriptRoot
Set-Location $ProjectDir

$JAVA_HOME = "D:\app\jdk"
$MAVEN_HOME = "D:\app\apache-maven-3.9.9"
$env:JAVA_HOME = $JAVA_HOME
$env:PATH = "$JAVA_HOME\bin;$MAVEN_HOME\bin;$env:PATH"

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Container Security Agent - Dev Mode" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# ── [1/4] Kill old processes ──
Write-Host "[1/4] Stopping old processes..." -ForegroundColor Yellow
foreach ($port in @(8080, 5173)) {
    $conns = Get-NetTCPConnection -LocalPort $port -ErrorAction SilentlyContinue
    if ($conns) {
        $procs = $conns | Select-Object -ExpandProperty OwningProcess -Unique
        foreach ($id in $procs) {
            Stop-Process -Id $id -Force -ErrorAction SilentlyContinue
            Write-Host "  Stopped PID $id (port $port)"
        }
    }
}
cmd /c 'for /f "tokens=5" %a in (''netstat -ano ^| findstr ":8080 "'') do taskkill /F /PID %a 2>nul' 2>$null
cmd /c 'for /f "tokens=5" %a in (''netstat -ano ^| findstr ":5173 "'') do taskkill /F /PID %a 2>nul' 2>$null
Start-Sleep -Seconds 2

# ── [2/4] Package backend (compile + jar) ──
Write-Host "[2/4] Building backend..." -ForegroundColor Yellow
mvn package -DskipTests -q
if ($LASTEXITCODE -ne 0) {
    Write-Host "  BUILD FAILED" -ForegroundColor Red
    exit 1
}
Write-Host "  OK" -ForegroundColor Green

# ── [3/4] Build frontend ──
Write-Host "[3/4] Building frontend..." -ForegroundColor Yellow
Push-Location "$ProjectDir\frontend"
cmd /c "npm run build 2>nul"
Pop-Location
Write-Host "  OK" -ForegroundColor Green

# ── [4/4] Start services ──
Write-Host "[4/4] Starting services..." -ForegroundColor Yellow

# Start backend jar directly (no Maven lifecycle overhead, starts in ~3s)
Write-Host "  Starting backend..."
$jarPath = "$ProjectDir\target\container-security-agent-1.0.0-SNAPSHOT.jar"
$proc = Start-Process -FilePath "$JAVA_HOME\bin\java.exe" `
    -ArgumentList "-jar `"$jarPath`"" `
    -WindowStyle Hidden -PassThru

# Wait for backend (jar starts much faster: ~5s max)
Write-Host "  Waiting for backend" -NoNewline
$ready = $false
for ($i = 1; $i -le 20; $i++) {
    try {
        $null = Invoke-WebRequest -Uri "http://localhost:8080/api/skills" -UseBasicParsing -TimeoutSec 2
        $ready = $true
        break
    }
    catch { Start-Sleep -Seconds 1; Write-Host "." -NoNewline }
}
Write-Host ""
if (-not $ready) {
    Write-Host "  Backend start failed! Is port 8080 free?" -ForegroundColor Red
    Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
    exit 1
}
Write-Host "  Backend:  http://localhost:8080" -ForegroundColor Green

# Verify
try {
    $skills = Invoke-RestMethod -Uri "http://localhost:8080/api/skills" -UseBasicParsing
    Write-Host "  Skills:   $($skills.Count)" -ForegroundColor Green
    $rules = Invoke-RestMethod -Uri "http://localhost:8080/api/rules" -UseBasicParsing
    Write-Host "  Rules:    $($rules.rules.Count)" -ForegroundColor Green
} catch { Write-Host "  Verify skipped" -ForegroundColor Yellow }

# Start frontend
Write-Host "  Starting frontend..."
$fproc = Start-Process cmd -ArgumentList "/c cd /d $ProjectDir\frontend && npm run dev" `
    -WindowStyle Hidden -PassThru

Write-Host "  Waiting for frontend" -NoNewline
$ready = $false
for ($i = 1; $i -le 15; $i++) {
    try {
        $null = Invoke-WebRequest -Uri "http://localhost:5173/" -UseBasicParsing -TimeoutSec 2
        $ready = $true
        break
    }
    catch { Start-Sleep -Seconds 1; Write-Host "." -NoNewline }
}
Write-Host ""
if ($ready) {
    Write-Host "  Frontend: http://localhost:5173" -ForegroundColor Green
} else {
    Write-Host "  Frontend start failed!" -ForegroundColor Red
}

# Save PIDs
"$($proc.Id)" | Out-File "$ProjectDir\.backend.pid"
"$($fproc.Id)" | Out-File "$ProjectDir\.frontend.pid"

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Ready!" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Frontend: http://localhost:5173"
Write-Host "  Backend:  http://localhost:8080"
Write-Host "  Stop:     .\stop-dev.ps1"
Write-Host "========================================" -ForegroundColor Cyan
