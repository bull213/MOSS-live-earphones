$ErrorActionPreference = 'SilentlyContinue'

# Find and stop Python processes running uvicorn on port 8000
$conns = Get-NetTCPConnection -LocalPort 8000 -ErrorAction SilentlyContinue
foreach ($conn in $conns) {
    if ($conn.OwningProcess -gt 0) {
        & taskkill /PID $conn.OwningProcess /T /F 2>$null
        Stop-Process -Id $conn.OwningProcess -Force -ErrorAction SilentlyContinue
    }
}

Write-Host "Backend stopped." -ForegroundColor Yellow
