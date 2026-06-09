$ErrorActionPreference = "Stop"
$ProjectDir = $PSScriptRoot
Set-Location $ProjectDir

$env:JAVA_HOME = "D:\app\jdk"
$env:PATH = "$env:JAVA_HOME\bin;D:\app\apache-maven-3.9.9\bin;$env:PATH"

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Container Security Agent - Dev Mode" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# [1/4] Kill old processes
Write-Host "[1/4] Stopping old processes..." -ForegroundColor Yellow
$procs = Get-NetTCPConnection -LocalPort 8080, 5173 -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique
foreach ($procId in $procs) {
    Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
    Write-Host "  Stopped PID $procId"
}
Start-Sleep -Seconds 2

# [2/4] Compile backend
Write-Host "[2/4] Compiling backend..." -ForegroundColor Yellow
mvn compile -q
if ($LASTEXITCODE -ne 0) {
    Write-Host "  BUILD FAILED" -ForegroundColor Red
    exit 1
}
Write-Host "  OK" -ForegroundColor Green

# [3/4] Build frontend
Write-Host "[3/4] Building frontend..." -ForegroundColor Yellow
Push-Location "$ProjectDir\frontend"
cmd /c "npm run build 2>nul"
Pop-Location
Write-Host "  OK" -ForegroundColor Green

# [4/4] Start services
Write-Host "[4/4] Starting services..." -ForegroundColor Yellow

# Start backend in a new hidden window (CMD)
Write-Host "  Starting backend..."
$backendCmd = "set JAVA_HOME=$env:JAVA_HOME && set PATH=$env:JAVA_HOME\bin;D:\app\apache-maven-3.9.9\bin;%PATH% && cd /d $ProjectDir && mvn spring-boot:run -q"
$backendProc = Start-Process cmd -ArgumentList "/c $backendCmd" -WindowStyle Hidden -PassThru

# Wait for backend
Write-Host "  Waiting for backend" -NoNewline
$ready = $false
for ($i = 1; $i -le 40; $i++) {
    try {
        $null = Invoke-WebRequest -Uri "http://localhost:8080/api/skills" -UseBasicParsing -TimeoutSec 2
        $ready = $true
        break
    }
    catch { Start-Sleep -Seconds 2; Write-Host "." -NoNewline }
}
Write-Host ""
if (-not $ready) {
    Write-Host "  Backend start timeout!" -ForegroundColor Red
    Stop-Process -Id $backendProc.Id -Force -ErrorAction SilentlyContinue
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

# Start frontend in a new hidden window
Write-Host "  Starting frontend..."
$frontendCmd = "cd /d $ProjectDir\frontend && npm run dev"
$frontendProc = Start-Process cmd -ArgumentList "/c $frontendCmd" -WindowStyle Hidden -PassThru

# Wait for frontend
Write-Host "  Waiting for frontend" -NoNewline
$ready = $false
for ($i = 1; $i -le 20; $i++) {
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
    Write-Host "  Frontend start timeout!" -ForegroundColor Red
}

# Store PIDs for stop script
"$($backendProc.Id)" | Out-File -FilePath "$ProjectDir\.backend.pid" -Encoding ascii
"$($frontendProc.Id)" | Out-File -FilePath "$ProjectDir\.frontend.pid" -Encoding ascii

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Ready!" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Frontend: http://localhost:5173"
Write-Host "  Backend:  http://localhost:8080"
Write-Host "  Stop:     .\stop-dev.ps1"
Write-Host "========================================" -ForegroundColor Cyan
