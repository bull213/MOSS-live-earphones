# setup_remote_tunnel.ps1 - Automated Cloudflare Remote Cellular Tunnel for AI Visual Assistant
param(
    [string]$TunnelToken = ""
)

$ErrorActionPreference = 'Continue'
$repoRoot = Split-Path -Parent $PSScriptRoot
$backendRoot = Join-Path $repoRoot 'backend'
$backendEnv = Join-Path $backendRoot '.env'
$binDir = Join-Path $PSScriptRoot 'bin'
$logDir = Join-Path $PSScriptRoot 'logs'

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "     Setting Up Remote Tunnel for Cellular Mobile Data    " -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host ""

# 1. Ensure backend/.env exists and has an API_BEARER_TOKEN
if (-not (Test-Path -LiteralPath $backendEnv)) {
    New-Item -ItemType File -Path $backendEnv -Force | Out-Null
}

$envContent = Get-Content -LiteralPath $backendEnv -Raw -ErrorAction SilentlyContinue
if (-not $envContent) { $envContent = "" }

$tokenMatch = [regex]::Match($envContent, '(?m)^API_BEARER_TOKEN=(.+)$')
if ($tokenMatch.Success -and $tokenMatch.Groups[1].Value.Trim()) {
    $bearerToken = $tokenMatch.Groups[1].Value.Trim()
    Write-Host "[OK] Using existing API_BEARER_TOKEN from backend/.env" -ForegroundColor Green
} else {
    $part1 = [System.Guid]::NewGuid().ToString("N")
    $part2 = [System.Guid]::NewGuid().ToString("N")
    $bearerToken = "$part1$part2"
    Add-Content -LiteralPath $backendEnv -Value "`nAPI_BEARER_TOKEN=$bearerToken"
    Write-Host "[OK] Generated new secure 256-bit API_BEARER_TOKEN in backend/.env" -ForegroundColor Green
}

# 2. Locate or download cloudflared.exe
$cloudflaredExe = $null

# Check system PATH
$pathCmd = Get-Command "cloudflared.exe" -ErrorAction SilentlyContinue
if ($pathCmd) {
    $cloudflaredExe = $pathCmd.Source
}

# Check C:\cloudflared\cloudflared.exe
if (-not $cloudflaredExe -and (Test-Path "C:\cloudflared\cloudflared.exe")) {
    $cloudflaredExe = "C:\cloudflared\cloudflared.exe"
}

# Check scripts\bin\cloudflared.exe
$localBin = Join-Path $binDir "cloudflared.exe"
if (-not $cloudflaredExe -and (Test-Path $localBin)) {
    $cloudflaredExe = $localBin
}

# Download if missing
if (-not $cloudflaredExe) {
    if (-not (Test-Path $binDir)) {
        New-Item -ItemType Directory -Path $binDir -Force | Out-Null
    }
    Write-Host "[INFO] cloudflared.exe not found. Downloading Cloudflare Tunnel binary..." -ForegroundColor Yellow
    $downloadUrl = "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe"
    try {
        $ProgressPreference = 'SilentlyContinue'
        Invoke-WebRequest -Uri $downloadUrl -OutFile $localBin -UseBasicParsing -TimeoutSec 60
        $cloudflaredExe = $localBin
        Write-Host "[OK] Downloaded cloudflared to: $localBin" -ForegroundColor Green
    } catch {
        Write-Host "[ERROR] Failed to download cloudflared: $_" -ForegroundColor Red
        Write-Host "Please download cloudflared-windows-amd64.exe manually, rename to cloudflared.exe, and put it in C:\cloudflared\ or scripts\bin\" -ForegroundColor Yellow
        exit 1
    }
} else {
    Write-Host "[OK] Located cloudflared at: $cloudflaredExe" -ForegroundColor Green
}

# 3. Verify backend server is running on http://127.0.0.1:8000
function Test-Backend {
    try {
        $res = Invoke-RestMethod -Uri "http://127.0.0.1:8000/health" -TimeoutSec 2 -ErrorAction Stop
        return $true
    } catch {
        return $false
    }
}

if (-not (Test-Backend)) {
    Write-Host "[INFO] Local backend not running on port 8000. Starting backend..." -ForegroundColor Yellow
    $runBackend = Join-Path $PSScriptRoot "run_backend.ps1"
    if (Test-Path $runBackend) {
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $runBackend
        Start-Sleep -Seconds 2
    }
}

if (Test-Backend) {
    Write-Host "[OK] Local backend is healthy on http://127.0.0.1:8000/health" -ForegroundColor Green
} else {
    Write-Host "[WARN] Local backend not responding on port 8000. Launching tunnel anyway..." -ForegroundColor Yellow
}

# 4. Start Cloudflare Tunnel
if (-not (Test-Path $logDir)) {
    New-Item -ItemType Directory -Path $logDir -Force | Out-Null
}
$tunnelOutLog = Join-Path $logDir "tunnel_stdout.log"
$tunnelErrLog = Join-Path $logDir "tunnel_stderr.log"
if (Test-Path $tunnelOutLog) { Remove-Item $tunnelOutLog -Force -ErrorAction SilentlyContinue }
if (Test-Path $tunnelErrLog) { Remove-Item $tunnelErrLog -Force -ErrorAction SilentlyContinue }

Write-Host ""
Write-Host "Starting Cloudflare tunnel to http://127.0.0.1:8000..." -ForegroundColor Cyan

if ($TunnelToken) {
    # Named tunnel with token
    Write-Host "[INFO] Starting named tunnel with provided token..." -ForegroundColor Green
    & $cloudflaredExe tunnel run --token $TunnelToken
    exit 0
}

# Quick tunnel: start process and extract trycloudflare.com URL
$process = Start-Process -FilePath $cloudflaredExe `
    -ArgumentList @("tunnel", "--url", "http://127.0.0.1:8000") `
    -RedirectStandardError $tunnelErrLog `
    -RedirectStandardOutput $tunnelOutLog `
    -PassThru

Write-Host "Waiting for Cloudflare tunnel URL..." -ForegroundColor Gray

$extractedUrl = $null
$deadline = (Get-Date).AddSeconds(25)
while ((Get-Date) -lt $deadline -and -not $extractedUrl) {
    Start-Sleep -Milliseconds 500
    foreach ($logFile in @($tunnelErrLog, $tunnelOutLog)) {
        if (Test-Path $logFile) {
            $logContent = Get-Content -LiteralPath $logFile -Raw -ErrorAction SilentlyContinue
            if ($logContent) {
                $match = [regex]::Match($logContent, 'https://[a-zA-Z0-9-]+\.trycloudflare\.com')
                if ($match.Success) {
                    $extractedUrl = $match.Value
                    break
                }
            }
        }
    }
}

if ($extractedUrl) {
    Write-Host ""
    Write-Host "========================================================================" -ForegroundColor Green
    Write-Host "               REMOTE MOBILE CELLULAR TUNNEL IS ACTIVE!                " -ForegroundColor Green
    Write-Host "========================================================================" -ForegroundColor Green
    Write-Host ""
    Write-Host " Enter these settings in your Visual Assistant Android App:" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "   Server Address:  " -NoNewline; Write-Host $extractedUrl -ForegroundColor Yellow
    Write-Host "   Bearer Token:    " -NoNewline; Write-Host $bearerToken -ForegroundColor Yellow
    Write-Host ""
    Write-Host " Instructions on your phone:" -ForegroundColor White
    Write-Host "   1. Open Visual Assistant App on your phone." -ForegroundColor Gray
    Write-Host "   2. Open Sidebar -> Settings (Gear icon)." -ForegroundColor Gray
    Write-Host "   3. In 'Server Address', enter: " -NoNewline; Write-Host $extractedUrl -ForegroundColor Yellow
    Write-Host "   4. In 'Bearer Token', enter:   " -NoNewline; Write-Host $bearerToken -ForegroundColor Yellow
    Write-Host "   5. Tap 'Save', then tap 'Test'. You will see: 'HTTP 200 OK'!" -ForegroundColor Green
    Write-Host ""
    Write-Host "========================================================================" -ForegroundColor Green
    Write-Host " [NOTE] Keep this terminal window open while using mobile data outside." -ForegroundColor DarkYellow
    Write-Host " Press Ctrl+C in this window when you wish to stop the remote tunnel." -ForegroundColor DarkYellow
    Write-Host "========================================================================" -ForegroundColor Green
    Write-Host ""

    # Optional: If phone is connected via ADB, offer to auto-push
    $adbCmd = Get-Command "adb.exe" -ErrorAction SilentlyContinue
    if (-not $adbCmd) {
        $sdkAdb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
        if (Test-Path $sdkAdb) { $adbCmd = $sdkAdb }
    }
    if ($adbCmd) {
        $devices = & $adbCmd devices
        if ($devices -match '(?m)^[a-zA-Z0-9_-]+\s+device$') {
            Write-Host "[ADB] Connected phone detected over USB!" -ForegroundColor Cyan
            Write-Host "Auto-configuring phone app settings directly via ADB..." -ForegroundColor Cyan
            try {
                $prefXml = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>`n<map>`n    <string name=`"pref_backend_host`">$extractedUrl</string>`n    <string name=`"pref_backend_token`">$bearerToken</string>`n</map>"
                $tempPref = Join-Path $env:TEMP "backend_config.xml"
                Set-Content -Path $tempPref -Value $prefXml -Encoding utf8
                & $adbCmd push $tempPref /data/local/tmp/backend_config.xml 2>$null | Out-Null
                & $adbCmd shell "run-as com.visualassistant.app cp /data/local/tmp/backend_config.xml /data/data/com.visualassistant.app/shared_prefs/backend_config.xml" 2>$null | Out-Null
                Write-Host "[OK] Settings successfully pushed directly into phone app!" -ForegroundColor Green
                Write-Host "     Phone is now configured to use: $extractedUrl" -ForegroundColor Green
            } catch {
                Write-Host "[INFO] Please enter settings manually on your phone." -ForegroundColor Gray
            }
        }
    }

    # Keep script alive while tunnel process runs
    Wait-Process -Id $process.Id
} else {
    Write-Host "[ERROR] Could not extract Cloudflare tunnel URL within 25 seconds." -ForegroundColor Red
    Write-Host "Recent log output (stderr):" -ForegroundColor Gray
    Get-Content -LiteralPath $tunnelErrLog -Tail 15 -ErrorAction SilentlyContinue
    Write-Host "Recent log output (stdout):" -ForegroundColor Gray
    Get-Content -LiteralPath $tunnelOutLog -Tail 15 -ErrorAction SilentlyContinue
    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    exit 1
}
