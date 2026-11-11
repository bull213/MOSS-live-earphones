param(
    [switch]$Wireless,
    [switch]$NoAdb
)

$ErrorActionPreference = 'Stop'

$repoRoot = $PSScriptRoot
$backendRoot = Join-Path $repoRoot 'backend'
$pythonExe = Join-Path $repoRoot '.venv\Scripts\python.exe'
$backendEnv = Join-Path $backendRoot '.env'
$appId = 'com.visualassistant.app'

function Test-BackendReady {
    try {
        $null = Invoke-RestMethod -Uri 'http://127.0.0.1:8000/health' -TimeoutSec 2
        return $true
    } catch {
        return $false
    }
}

# 1. Discover local network (Wi-Fi) IP addresses
$lanIps = @(Get-NetIPAddress -AddressFamily IPv4 -PrefixOrigin Dhcp,Manual -ErrorAction SilentlyContinue |
    Where-Object { $_.IPAddress -notlike "127.*" -and $_.IPAddress -notlike "169.254.*" } |
    Select-Object -ExpandProperty IPAddress)

$primaryIp = if ($lanIps.Count -gt 0) { $lanIps[0] } else { "127.0.0.1" }

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "                MOSS Live Server Controller" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "Computer Wi-Fi IP: $primaryIp" -ForegroundColor Yellow
Write-Host "Port: 8000" -ForegroundColor Yellow
Write-Host "----------------------------------------------------------" -ForegroundColor DarkGray
Write-Host "Mode 1 (USB Cable): Server = 127.0.0.1:8000 (Default)" -ForegroundColor White
Write-Host "Mode 2 (Phone Mobile Hotspot - Cable Free):" -ForegroundColor Green
Write-Host "   1. Turn on Mobile Hotspot on your phone." -ForegroundColor White
Write-Host "   2. Connect this computer's Wi-Fi to your phone's hotspot." -ForegroundColor White
Write-Host "   3. On phone in Settings -> tap 'Auto-Detect on Hotspot'" -ForegroundColor Green
Write-Host "      (or enter: ${primaryIp}:8000)" -ForegroundColor Green
Write-Host "   4. Tap Start MOSS Live!" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# 2. Ensure Backend is Running on 0.0.0.0:8000
if (-not (Test-BackendReady)) {
    if (-not (Test-Path -LiteralPath $backendEnv)) {
        throw 'backend\.env is missing. Configure the MOSS API key on the computer before starting MOSS Live.'
    }
    if (-not (Test-Path -LiteralPath $pythonExe)) {
        throw 'The project Python environment is missing. Follow the backend Windows setup in backend\README.md.'
    }

    Write-Host "Starting backend server on 0.0.0.0:8000..." -ForegroundColor Cyan
    Start-Process -FilePath $pythonExe `
        -ArgumentList @('-m', 'uvicorn', 'app.main:app', '--host', '0.0.0.0', '--port', '8000') `
        -WorkingDirectory $backendRoot `
        -WindowStyle Hidden | Out-Null

    $deadline = (Get-Date).AddSeconds(20)
    while ((Get-Date) -lt $deadline -and -not (Test-BackendReady)) {
        Start-Sleep -Milliseconds 500
    }
    if (-not (Test-BackendReady)) {
        throw 'The backend did not become healthy within 20 seconds. Check the Python environment and backend configuration.'
    }
}

Write-Host "Backend is healthy and listening on 0.0.0.0:8000 (all network interfaces)." -ForegroundColor Green

if ($NoAdb) {
    Write-Host "`nNoAdb switch passed. Skipping ADB checks." -ForegroundColor Yellow
    Write-Host "Open the app on your phone and tap Start MOSS Live." -ForegroundColor Cyan
    exit 0
}

# 3. Optional ADB Reverse Port Forwarding (if phone is connected)
try {
    $adbCandidates = @()
    if ($env:ANDROID_SDK_ROOT) { $adbCandidates += Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe' }
    if ($env:ANDROID_HOME) { $adbCandidates += Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe' }
    if ($env:LOCALAPPDATA) { $adbCandidates += Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe' }
    $bundledAdb = Join-Path $repoRoot 'android\.tools\sdk\platform-tools\adb.exe'
    if (Test-Path -LiteralPath $bundledAdb) { $adbCandidates += $bundledAdb }
    $adbCommand = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($adbCommand) { $adbCandidates += $adbCommand.Source }

    $adbPath = $adbCandidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    if (-not $adbPath) {
        Write-Host "Note: ADB not found. If running cable-free over Wi-Fi, this is normal." -ForegroundColor DarkGray
        Write-Host "Connection ready. Tap Start MOSS Live on the phone." -ForegroundColor Cyan
        exit 0
    }

    $deviceOutput = @(& $adbPath devices)
    if ($LASTEXITCODE -ne 0) {
        & $adbPath kill-server | Out-Null
        Start-Sleep -Milliseconds 500
        $deviceOutput = @(& $adbPath devices)
    }

    $rawLines = $deviceOutput | Select-Object -Skip 1 | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    $authorizedDevices = @()
    foreach ($line in $rawLines) {
        if ($line -match '^\s*(\S+)\s+device(?:\s|$)') {
            $authorizedDevices += $Matches[1]
        }
    }

    if ($authorizedDevices.Count -eq 0) {
        # Attempt Wireless ADB over the Wi-Fi gateway (e.g. phone Mobile Hotspot)
        $wifiGateway = (Get-NetRoute -DestinationPrefix '0.0.0.0/0' -InterfaceAlias 'Wi-Fi*' -ErrorAction SilentlyContinue).NextHop | Select-Object -First 1
        if ($wifiGateway -and $wifiGateway -notlike "127.*") {
            & $adbPath connect "${wifiGateway}:5555" 2>$null | Out-Null
            $wirelessCheck = @(& $adbPath devices) | Select-Object -Skip 1 | Where-Object { $_ -match '^\s*(\S+)\s+device' }
            if ($wirelessCheck.Count -gt 0) {
                $wSerial = $Matches[1]
                & $adbPath -s $wSerial reverse tcp:8000 tcp:8000 | Out-Null
                $fwd = (& $adbPath -s $wSerial reverse --list) -join "; "
                Write-Host "[AUTOMATIC SETUP COMPLETE] Wireless ADB Connected ($wSerial)." -ForegroundColor Green
                Write-Host "Reverse port forward active: $fwd" -ForegroundColor Green
                & $adbPath -s $wSerial shell am start -n "$appId/.MainActivity" | Out-Null
                Write-Host "The app is open on your phone. Tap Start MOSS Live to begin!" -ForegroundColor Cyan
                exit 0
            }
        }

        Write-Host "`n[AUTOMATIC SETUP COMPLETE] Cable-Free Mode Ready." -ForegroundColor Green
        Write-Host "--> Backend listening on 0.0.0.0:8000 (Computer IP: $primaryIp)." -ForegroundColor Yellow
        Write-Host "--> The app will auto-discover this computer when you tap Start MOSS Live!`n" -ForegroundColor Cyan
        exit 0
    }

    $serial = $authorizedDevices[0]
    & $adbPath -s $serial reverse tcp:8000 tcp:8000 | Out-Null
    $fwd = (& $adbPath -s $serial reverse --list) -join "; "
    Write-Host "[AUTOMATIC SETUP COMPLETE] USB Cable Mode Ready." -ForegroundColor Green
    Write-Host "Reverse port forward active: $fwd" -ForegroundColor Green
    & $adbPath -s $serial shell am start -n "$appId/.MainActivity" | Out-Null
    Write-Host "The app is open on your phone. Tap Start MOSS Live to begin!" -ForegroundColor Cyan
    exit 0
} catch {
    Write-Host "ADB note: $($_.Exception.Message)" -ForegroundColor DarkGray
    Write-Host "Direct Wi-Fi mode is active. Tap Start MOSS Live on the phone." -ForegroundColor Cyan
    exit 0
}
