# One-time automated bootstrap of the dedicated 24/7 Server computer.
param(
    [string]$GoogleAccount = "earphonese12@gmail.com"
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "   Visual Assistant 24/7 Server Initial Setup" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# 1. Python environment validation & venv setup
$pythonCmd = Get-Command "python.exe" -ErrorAction SilentlyContinue
if (-not $pythonCmd) {
    $pythonCmd = Get-Command "py.exe" -ErrorAction SilentlyContinue
}
if (-not $pythonCmd) {
    Write-Host "[ERROR] Python was not found in PATH." -ForegroundColor Red
    Write-Host "Please install Python 3.11 - 3.13 via winget or python.org (ensure 'Add Python to PATH' is checked):" -ForegroundColor Yellow
    Write-Host "   winget install --id Python.Python.3.13 -e --source winget" -ForegroundColor White
    exit 1
}

$pythonExe = Join-Path $repoRoot ".venv\Scripts\python.exe"
if (-not (Test-Path $pythonExe)) {
    Write-Host "`n[1/5] Creating Python virtual environment in .venv..." -ForegroundColor Cyan
    & $pythonCmd.Source -m venv .venv
}
Write-Host "  -> Python environment: $pythonExe" -ForegroundColor Green

# 2. Install dependencies
Write-Host "`n[2/5] Installing backend dependencies (FastAPI, uvicorn, gpsoauth, etc.)..." -ForegroundColor Cyan
& $pythonExe -m pip install --use-deprecated=legacy-certs -q -r backend\requirements.txt
Write-Host "  -> Dependencies installed." -ForegroundColor Green

# 3. Configure backend\.env with Bearer Token if needed
Write-Host "`n[3/5] Checking backend configuration (backend\.env)..." -ForegroundColor Cyan
$envPath = Join-Path $repoRoot "backend\.env"
if (-not (Test-Path $envPath)) {
    $newToken = [System.Guid]::NewGuid().ToString('N')
    $envContent = @"
API_BEARER_TOKEN=$newToken
ASSISTANT_PROVIDER=openai
OPENAI_MODEL=gpt-6.1-terra
MOSS_MODEL=moss-live-1.0
"@
    Set-Content -Path $envPath -Value $envContent -Encoding utf8
    Write-Host "  -> Created new backend\.env with API_BEARER_TOKEN=$newToken" -ForegroundColor Green
} else {
    $existing = Get-Content $envPath -Raw
    if ($existing -notmatch "API_BEARER_TOKEN=") {
        $newToken = [System.Guid]::NewGuid().ToString('N')
        Add-Content -Path $envPath -Value "`nAPI_BEARER_TOKEN=$newToken" -Encoding utf8
        Write-Host "  -> Generated and added API_BEARER_TOKEN=$newToken to backend\.env" -ForegroundColor Green
    } else {
        Write-Host "  -> Existing backend\.env has API_BEARER_TOKEN configured." -ForegroundColor Green
    }
}

# 4. Configure always-on Windows service, power settings, and firewall
Write-Host "`n[4/5] Running Windows always-on service configuration..." -ForegroundColor Cyan
& (Join-Path $PSScriptRoot "setup_always_on_service.ps1")

# 5. NotebookLM master-token bootstrap
Write-Host "`n[5/5] NotebookLM Unattended Master-Token Bootstrap..." -ForegroundColor Cyan
$notebooklmExe = Join-Path $repoRoot ".venv\Scripts\notebooklm.exe"
$masterTokenFile = Join-Path $repoRoot "backend\.data\notebooklm\master_token.json"
if (Test-Path $masterTokenFile) {
    Write-Host "  -> Master token already exists at $masterTokenFile. Skipping browser login." -ForegroundColor Green
} else {
    Write-Host "  -> A browser window will open now to sign in to Google once with: $GoogleAccount" -ForegroundColor Yellow
    Write-Host "     Once you complete sign-in, the master token is saved for unattended 24/7 access." -ForegroundColor Yellow
    & $notebooklmExe login --master-token --account $GoogleAccount
}

Write-Host "`n==========================================================" -ForegroundColor Green
Write-Host "   Setup Complete! The server is ready and running 24/7." -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Green
