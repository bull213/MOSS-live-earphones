# Setup Always-On Server Service, Power Management, and Tailscale Inbound Firewall Rules
param(
    [switch]$Uninstall
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$backendRoot = Join-Path $repoRoot 'backend'
$pythonExe = Join-Path $repoRoot '.venv\Scripts\python.exe'
$taskName = "VisualAssistantBackend"
$firewallRuleName = "Visual Assistant Backend (Tailscale & Local)"

# 1. Ensure Administrative Elevation
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Host "[ELEVATION] Requesting administrator privileges..." -ForegroundColor Yellow
    $argList = "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`""
    if ($Uninstall) { $argList += " -Uninstall" }
    Start-Process powershell.exe -ArgumentList $argList -Verb RunAs
    exit 0
}

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "   Visual Assistant Always-On Host Service Configuration" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# Handle Uninstallation
if ($Uninstall) {
    Write-Host "`n[UNINSTALL] Removing scheduled task '$taskName'..." -ForegroundColor Yellow
    Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue
    
    Write-Host "[UNINSTALL] Removing firewall rule '$firewallRuleName'..." -ForegroundColor Yellow
    Remove-NetFirewallRule -DisplayName $firewallRuleName -ErrorAction SilentlyContinue

    Write-Host "[OK] Always-on service and firewall rules removed cleanly." -ForegroundColor Green
    exit 0
}

# 2. Configure Windows Power Settings (Disable Sleep & Hibernate on AC Power)
Write-Host "`n[1/4] Configuring Windows AC Power Management..." -ForegroundColor Cyan
try {
    powercfg /change standby-timeout-ac 0
    powercfg /change hibernate-timeout-ac 0
    powercfg /setacvalueindex SCHEME_CURRENT SUB_NONE CONSOLELOCK 0
    Write-Host "  -> Sleep and hibernation on AC power permanently disabled." -ForegroundColor Green
} catch {
    Write-Host "  -> Warning: Failed to set power settings: $($_.Exception.Message)" -ForegroundColor Yellow
}

# 3. Configure Tailscale and Local Firewall Rules for Port 8000
Write-Host "`n[2/4] Configuring Windows Firewall for Port 8000..." -ForegroundColor Cyan
try {
    # Remove older or generic rules to prevent conflicts
    Remove-NetFirewallRule -DisplayName $firewallRuleName -ErrorAction SilentlyContinue

    # Allow inbound TCP 8000 on Tailscale interface alias and Private networks (CGNAT 100.64.0.0/10 & Local Subnets)
    New-NetFirewallRule -DisplayName $firewallRuleName `
        -Description "Permits inbound HTTP/WebSocket traffic to Visual Assistant backend over Tailscale and local subnets." `
        -Direction Inbound `
        -LocalPort 8000 `
        -Protocol TCP `
        -Action Allow `
        -RemoteAddress @("127.0.0.1", "100.64.0.0/10", "192.168.0.0/16", "10.0.0.0/8", "172.16.0.0/12") `
        -Profile Any | Out-Null
    
    Write-Host "  -> Inbound TCP 8000 allowed for Tailscale (100.64.0.0/10), Loopback, and Private LAN." -ForegroundColor Green
} catch {
    Write-Host "  -> Failed to configure firewall rule: $($_.Exception.Message)" -ForegroundColor Red
}

# 4. Check Tailscale Installation
Write-Host "`n[3/4] Checking Tailscale Status..." -ForegroundColor Cyan
$tailscaleCmd = Get-Command tailscale.exe -ErrorAction SilentlyContinue
if (-not $tailscaleCmd) {
    $tsPaths = @(
        (Join-Path $env:ProgramFiles "Tailscale\tailscale.exe"),
        (Join-Path ${env:ProgramFiles(x86)} "Tailscale\tailscale.exe")
    )
    $foundTs = $tsPaths | Where-Object { Test-Path $_ } | Select-Object -First 1
    if ($foundTs) {
        $tailscaleCmd = $foundTs
    }
}

if ($tailscaleCmd) {
    Write-Host "  -> Tailscale detected at: $tailscaleCmd" -ForegroundColor Green
    try {
        $status = & $tailscaleCmd status --json | ConvertFrom-Json
        $tailscaleIp = $status.Self.TailscaleIPs[0]
        $magicDns = $status.Self.DNSName.TrimEnd('.')
        Write-Host "  -> Node IP: $tailscaleIp" -ForegroundColor Yellow
        Write-Host "  -> MagicDNS Name: $magicDns" -ForegroundColor Yellow
    } catch {
        Write-Host "  -> Tailscale is installed but may not be logged in yet." -ForegroundColor Yellow
    }
} else {
    Write-Host "  -> Tailscale is not currently installed." -ForegroundColor Yellow
    Write-Host "     To install Tailscale, run: winget install Tailscale.Tailscale" -ForegroundColor DarkGray
    Write-Host "     Or download from: https://tailscale.com/download/windows" -ForegroundColor DarkGray
}

# 5. Create Windows Scheduled Task (Runs At Startup as SYSTEM without Login)
Write-Host "`n[4/4] Creating Scheduled Task for Backend Autostart..." -ForegroundColor Cyan
if (-not (Test-Path -LiteralPath $pythonExe)) {
    throw "Project Python executable not found at: $pythonExe"
}

$taskAction = New-ScheduledTaskAction `
    -Execute $pythonExe `
    -Argument "-m uvicorn app.main:app --host 0.0.0.0 --port 8000" `
    -WorkingDirectory $backendRoot

# Trigger at system boot (independent of user logon)
$taskTrigger = New-ScheduledTaskTrigger -AtStartup

# Settings: Auto-restart on failure, do not stop if running long, run on AC/battery
$taskSettings = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries `
    -RestartCount 3 `
    -RestartInterval (New-TimeSpan -Minutes 1) `
    -ExecutionTimeLimit (New-TimeSpan -Days 0) `
    -StartWhenAvailable

# Run under SYSTEM account with highest privileges
$taskPrincipal = New-ScheduledTaskPrincipal `
    -UserId "NT AUTHORITY\SYSTEM" `
    -LogonType ServiceAccount `
    -RunLevel Highest

# Unregister previous task if exists
Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue

# Register the new task
$registeredTask = Register-ScheduledTask `
    -TaskName $taskName `
    -Action $taskAction `
    -Trigger $taskTrigger `
    -Settings $taskSettings `
    -Principal $taskPrincipal `
    -Description "Always-on service for AI Visual Assistant backend (Uvicorn port 8000)."

Write-Host "  -> Scheduled Task '$taskName' registered successfully." -ForegroundColor Green
Write-Host "  -> Trigger: At system startup (runs before user logon)." -ForegroundColor Green
Write-Host "  -> User: NT AUTHORITY\SYSTEM (High privileges)." -ForegroundColor Green

Write-Host "`n==========================================================" -ForegroundColor Cyan
Write-Host "Always-On Host Setup Complete!" -ForegroundColor Green
Write-Host "To start the task now without rebooting, run:" -ForegroundColor White
Write-Host "  Start-ScheduledTask -TaskName `"$taskName`"" -ForegroundColor Yellow
Write-Host "To check task state:" -ForegroundColor White
Write-Host "  Get-ScheduledTask -TaskName `"$taskName`"" -ForegroundColor Yellow
Write-Host "==========================================================" -ForegroundColor Cyan
