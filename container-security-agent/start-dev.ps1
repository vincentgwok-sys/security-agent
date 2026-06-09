$ErrorActionPreference = "Stop"
$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ProjectDir

$env:JAVA_HOME = "D:\app\jdk"
$env:MAVEN_HOME = "D:\app\apache-maven-3.9.9"
$env:PATH = "$env:JAVA_HOME\bin;$env:MAVEN_HOME\bin;$env:PATH"

$backendPort = 8080
$frontendPort = 5173

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Container Security Agent - Dev Mode" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# [1/4] Kill old processes
Write-Host "[1/4] Stopping old processes..." -ForegroundColor Yellow
$oldProcs = Get-NetTCPConnection -LocalPort $backendPort -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique
foreach ($procId in $oldProcs) {
    Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
    Write-Host "  Stopped backend PID: $procId"
}
$oldProcs = Get-NetTCPConnection -LocalPort $frontendPort -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique
foreach ($procId in $oldProcs) {
    Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
    Write-Host "  Stopped frontend PID: $procId"
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

# [4/4] Start backend
Write-Host "[4/4] Starting backend..." -ForegroundColor Yellow
$backendJob = Start-Job -Name "cs-backend" -ScriptBlock {
    param($pd, $jh, $mh)
    $env:JAVA_HOME = $jh
    $env:PATH = "$jh\bin;$mh\bin;$env:PATH"
    Set-Location $pd
    mvn spring-boot:run -q
} -ArgumentList $ProjectDir, $env:JAVA_HOME, $env:MAVEN_HOME

Write-Host "  Waiting for backend..." -NoNewline
$ready = $false
for ($i = 1; $i -le 30; $i++) {
    try {
        $null = Invoke-WebRequest -Uri "http://localhost:8080/api/skills" -UseBasicParsing -TimeoutSec 2
        $ready = $true
        break
    }
    catch {
        Start-Sleep -Seconds 2
        Write-Host "." -NoNewline
    }
}
Write-Host ""
if (-not $ready) {
    Write-Host "  Backend start timeout!" -ForegroundColor Red
    exit 1
}
Write-Host "  Backend: http://localhost:8080" -ForegroundColor Green

# Verify
try {
    $skills = Invoke-RestMethod -Uri "http://localhost:8080/api/skills" -UseBasicParsing
    Write-Host "  Skills: $($skills.Count)" -ForegroundColor Green
    $rules = Invoke-RestMethod -Uri "http://localhost:8080/api/rules" -UseBasicParsing
    Write-Host "  Rules: $($rules.rules.Count)" -ForegroundColor Green
} catch {
    Write-Host "  API verify failed" -ForegroundColor Red
}

# Start frontend
Write-Host "  Starting frontend..." -ForegroundColor Yellow
$frontendJob = Start-Job -Name "cs-frontend" -ScriptBlock {
    param($pd)
    Set-Location "$pd\frontend"
    cmd /c "npm run dev 2>nul"
} -ArgumentList $ProjectDir

Write-Host "  Waiting for frontend..." -NoNewline
$ready = $false
for ($i = 1; $i -le 15; $i++) {
    try {
        $null = Invoke-WebRequest -Uri "http://localhost:5173/" -UseBasicParsing -TimeoutSec 2
        $ready = $true
        break
    }
    catch {
        Start-Sleep -Seconds 1
        Write-Host "." -NoNewline
    }
}
Write-Host ""
if ($ready) {
    Write-Host "  Frontend: http://localhost:5173" -ForegroundColor Green
} else {
    Write-Host "  Frontend start timeout!" -ForegroundColor Red
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Ready!" -ForegroundColor Cyan
Write-Host ""
Write-Host "  Frontend:  http://localhost:5173" -ForegroundColor White
Write-Host "  Backend:   http://localhost:8080" -ForegroundColor White
Write-Host ""
Write-Host "  Stop:      .\stop-dev.ps1" -ForegroundColor Gray
Write-Host "  Logs:      Get-Job -Name cs-backend | Receive-Job" -ForegroundColor Gray
Write-Host "========================================" -ForegroundColor Cyan
