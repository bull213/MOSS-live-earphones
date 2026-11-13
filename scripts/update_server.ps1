# Update 24/7 Server: pulls latest git commits, updates requirements, and restarts the backend service.
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "   Visual Assistant 24/7 Server Update" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# 1. Pull latest git commits
Write-Host "`n[1/3] Fetching latest changes from Git..." -ForegroundColor Cyan
try {
    git pull
} catch {
    Write-Host "  -> Warning: git pull reported an error: $($_.Exception.Message)" -ForegroundColor Yellow
}

# 2. Check and install any dependency updates
Write-Host "`n[2/3] Checking backend dependencies..." -ForegroundColor Cyan
$pythonExe = Join-Path $repoRoot ".venv\Scripts\python.exe"
if (Test-Path $pythonExe) {
    & $pythonExe -m pip install --use-deprecated=legacy-certs -q -r backend\requirements.txt
    Write-Host "  -> Dependencies up to date." -ForegroundColor Green
} else {
    Write-Host "  -> Warning: Python virtual environment not found at $pythonExe" -ForegroundColor Yellow
}

# 3. Restart the background service
Write-Host "`n[3/3] Restarting Visual Assistant background service..." -ForegroundColor Cyan
$taskName = "VisualAssistantBackend"
try {
    Stop-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 1
    Start-ScheduledTask -TaskName $taskName
    Start-Sleep -Seconds 2
    Write-Host "  -> Service restarted." -ForegroundColor Green
} catch {
    Write-Host "  -> Note: Scheduled task '$taskName' not registered or failed to start: $($_.Exception.Message)" -ForegroundColor Yellow
}

# 4. Verify /health
try {
    $health = Invoke-RestMethod -Uri "http://127.0.0.1:8000/health" -TimeoutSec 5 -ErrorAction Stop
    if ($health.status -eq "ok") {
        Write-Host "`n[SUCCESS] Server is healthy and running!" -ForegroundColor Green
    } else {
        Write-Host "`n[WARNING] Server responded but status was: $($health.status)" -ForegroundColor Yellow
    }
} catch {
    Write-Host "`n[NOTE] http://127.0.0.1:8000/health is starting up..." -ForegroundColor Yellow
}
