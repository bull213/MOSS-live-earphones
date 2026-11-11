$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$backendRoot = Join-Path $repoRoot 'backend'
$pythonExe = Join-Path $repoRoot '.venv\Scripts\python.exe'
$backendEnv = Join-Path $backendRoot '.env'

function Test-BackendReady {
    try {
        $null = Invoke-RestMethod -Uri 'http://127.0.0.1:8000/health' -TimeoutSec 2
        return $true
    } catch {
        return $false
    }
}

if (Test-BackendReady) {
    exit 0
}

if (-not (Test-Path -LiteralPath $backendEnv) -or -not (Test-Path -LiteralPath $pythonExe)) {
    exit 1
}

Start-Process -FilePath $pythonExe `
    -ArgumentList @('-m', 'uvicorn', 'app.main:app', '--host', '0.0.0.0', '--port', '8000') `
    -WorkingDirectory $backendRoot `
    -WindowStyle Hidden

$deadline = (Get-Date).AddSeconds(15)
while ((Get-Date) -lt $deadline -and -not (Test-BackendReady)) {
    Start-Sleep -Milliseconds 500
}
