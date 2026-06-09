Write-Host "Stopping dev environment..." -ForegroundColor Yellow

Get-Job -Name "cs-backend", "cs-frontend" -ErrorAction SilentlyContinue | Stop-Job -PassThru | Remove-Job
Write-Host "  Jobs removed"

$procIds = Get-NetTCPConnection -LocalPort 8080, 5173 -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique
foreach ($procId in $procIds) {
    Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
    Write-Host "  Stopped PID $procId"
}

Write-Host "Done" -ForegroundColor Green
