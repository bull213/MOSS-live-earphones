# Setup Windows Firewall and Network Profile for MOSS Live Mobile Hotspot
$ErrorActionPreference = 'Continue'

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "     Configuring Firewall for MOSS Live Hotspot" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# 1. Allow Port 8000 (TCP) for Backend (All Profiles: Public, Private, Domain)
$rule8000 = Get-NetFirewallRule -DisplayName "MOSS Live Backend Port 8000" -ErrorAction SilentlyContinue
if (-not $rule8000) {
    New-NetFirewallRule -DisplayName "MOSS Live Backend Port 8000" `
        -Direction Inbound `
        -LocalPort 8000 `
        -Protocol TCP `
        -Action Allow `
        -Profile Any | Out-Null
    Write-Host "[OK] Firewall rule created: Inbound TCP 8000 (Allowed on Any Profile)" -ForegroundColor Green
} else {
    Write-Host "[OK] Firewall rule for TCP 8000 already exists." -ForegroundColor Green
}

# 2. Allow Port 8008 (UDP) for App Wake Trigger (All Profiles)
$rule8008 = Get-NetFirewallRule -DisplayName "MOSS Live Trigger Port 8008" -ErrorAction SilentlyContinue
if (-not $rule8008) {
    New-NetFirewallRule -DisplayName "MOSS Live Trigger Port 8008" `
        -Direction Inbound `
        -LocalPort 8008 `
        -Protocol UDP `
        -Action Allow `
        -Profile Any | Out-Null
    Write-Host "[OK] Firewall rule created: Inbound UDP 8008 (Allowed on Any Profile)" -ForegroundColor Green
} else {
    Write-Host "[OK] Firewall rule for UDP 8008 already exists." -ForegroundColor Green
}

# 3. Allow Python Executable Directly
$repoRoot = Split-Path -Parent $PSScriptRoot
$pythonExe = Join-Path $repoRoot '.venv\Scripts\python.exe'
if (Test-Path -LiteralPath $pythonExe) {
    $pythonRule = Get-NetFirewallRule -DisplayName "MOSS Live Python Backend" -ErrorAction SilentlyContinue
    if (-not $pythonRule) {
        New-NetFirewallRule -DisplayName "MOSS Live Python Backend" `
            -Direction Inbound `
            -Program $pythonExe `
            -Action Allow `
            -Profile Any | Out-Null
        Write-Host "[OK] Firewall rule created for Python executable: $pythonExe" -ForegroundColor Green
    }
}

# 4. Set Hotspot network category to Private
Get-NetConnectionProfile | Where-Object { $_.Name -like "*Galaxy*" -or $_.Name -like "*lior*" } | ForEach-Object {
    Set-NetConnectionProfile -Name $_.Name -NetworkCategory Private -ErrorAction SilentlyContinue
    Write-Host "[OK] Network '$($_.Name)' set to Private." -ForegroundColor Green
}

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "Firewall configuration complete! Press any key to close..." -ForegroundColor Green
Start-Sleep -Seconds 2
