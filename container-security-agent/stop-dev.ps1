Write-Host "Stopping dev environment..." -ForegroundColor Yellow

# Kill by PID file
foreach ($f in @("$PSScriptRoot\.backend.pid", "$PSScriptRoot\.frontend.pid")) {
    if (Test-Path $f) {
        $id = Get-Content $f
        Stop-Process -Id $id -Force -ErrorAction SilentlyContinue
        Remove-Item $f -ErrorAction SilentlyContinue
        Write-Host "  Stopped PID $id"
    }
}

# Fallback: kill by port
$procs = Get-NetTCPConnection -LocalPort 8080, 5173 -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique
foreach ($procId in $procs) {
    Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
    Write-Host "  Stopped port process PID $procId"
}

Write-Host "Done" -ForegroundColor Green
