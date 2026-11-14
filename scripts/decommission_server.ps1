# decommission_server.ps1 - Cleanly remove all Visual Assistant services, tasks, firewall rules, and files
param(
    [switch]$Force,
    [switch]$DeleteFiles
)

$ErrorActionPreference = 'Continue'
$repoRoot = Split-Path -Parent $PSScriptRoot

# 1. Elevate to Administrator if not already elevated
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Host "[ELEVATION] Requesting administrator privileges to clean up system services and firewall..." -ForegroundColor Yellow
    $argList = "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`""
    if ($Force) { $argList += " -Force" }
    if ($DeleteFiles) { $argList += " -DeleteFiles" }
    Start-Process powershell.exe -ArgumentList $argList -Verb RunAs
    exit 0
}

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "     Visual Assistant Server Decommission & Teardown     " -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host ""

# Safety Warning about .env
$envPath = Join-Path $repoRoot "backend\.env"
if (Test-Path $envPath) {
    Write-Host "[NOTICE] Found backend\.env (contains your private API keys)." -ForegroundColor Yellow
    Write-Host "         Ensure you have backed up this file if you need these keys on your new PC!" -ForegroundColor Yellow
    Write-Host ""
}

if (-not $Force) {
    $confirm = Read-Host "Are you sure you want to decommission and remove all services from this PC? (y/N)"
    if ($confirm -ne 'y' -and $confirm -ne 'Y') {
        Write-Host "Aborted by user." -ForegroundColor Red
        exit 0
    }
}

# 2. Terminate running processes
Write-Host "`n[1/5] Stopping any running backend and tunnel processes..." -ForegroundColor Cyan

# Kill processes on port 8000
try {
    $portProcesses = Get-NetTCPConnection -LocalPort 8000 -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique
    foreach ($pidToKill in $portProcesses) {
        if ($pidToKill -and $pidToKill -ne 0) {
            Write-Host "  -> Terminating process $pidToKill listening on port 8000..." -ForegroundColor Gray
            Stop-Process -Id $pidToKill -Force -ErrorAction SilentlyContinue
        }
    }
} catch {}

# Stop cloudflared
Get-Process cloudflared -ErrorAction SilentlyContinue | ForEach-Object {
    Write-Host "  -> Stopping cloudflared.exe (PID $($_.Id))..." -ForegroundColor Gray
    Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue
}

Write-Host "  -> All active processes terminated." -ForegroundColor Green

# 3. Unregister Windows Scheduled Tasks
Write-Host "`n[2/5] Removing Windows Scheduled Tasks..." -ForegroundColor Cyan
$tasksToRemove = @("VisualAssistantBackend", "VisualAssistantPhoneListener")
foreach ($t in $tasksToRemove) {
    $existing = Get-ScheduledTask -TaskName $t -ErrorAction SilentlyContinue
    if ($existing) {
        Unregister-ScheduledTask -TaskName $t -Confirm:$false -ErrorAction SilentlyContinue
        Write-Host "  -> Removed Scheduled Task: $t" -ForegroundColor Green
    } else {
        Write-Host "  -> Scheduled Task not found (already clean): $t" -ForegroundColor DarkGray
    }
}

# 4. Remove Windows Firewall Rules
Write-Host "`n[3/5] Removing Windows Firewall Rules..." -ForegroundColor Cyan
$rulesToRemove = @(
    "Visual Assistant Backend (Tailscale & Local)",
    "VisualAssistant_FastAPI_8000",
    "VisualAssistant_UDP_8008"
)
foreach ($r in $rulesToRemove) {
    $existing = Get-NetFirewallRule -DisplayName $r -ErrorAction SilentlyContinue
    if ($existing) {
        Remove-NetFirewallRule -DisplayName $r -ErrorAction SilentlyContinue
        Write-Host "  -> Removed Firewall Rule: $r" -ForegroundColor Green
    } else {
        Write-Host "  -> Firewall Rule not found: $r" -ForegroundColor DarkGray
    }
}

# 5. Remove Startup Folder Shortcuts
Write-Host "`n[4/5] Removing Startup Folder shortcuts..." -ForegroundColor Cyan
$startupFolders = @(
    [Environment]::GetFolderPath('Startup'),
    [Environment]::GetFolderPath('CommonStartup')
)
$shortcuts = @("Start MOSS Backend.lnk", "MOSS Live Phone Trigger Listener.lnk")
foreach ($sf in $startupFolders) {
    if (Test-Path $sf) {
        foreach ($sc in $shortcuts) {
            $fullSc = Join-Path $sf $sc
            if (Test-Path $fullSc) {
                Remove-Item -Path $fullSc -Force -ErrorAction SilentlyContinue
                Write-Host "  -> Removed startup shortcut: $sc from $sf" -ForegroundColor Green
            }
        }
    }
}

# Remove git safe.directory entry if present
try {
    $gitCmd = Get-Command git.exe -ErrorAction SilentlyContinue
    if ($gitCmd) {
        & git config --global --unset-all safe.directory "$($repoRoot.Replace('\', '/'))" 2>$null
        & git config --global --unset-all safe.directory "C:/Lior/VisualAssistant" 2>$null
        Write-Host "  -> Cleaned git safe.directory config." -ForegroundColor Green
    }
} catch {}

# 6. Delete repository files (Optional / Confirmed)
Write-Host "`n[5/5] File Cleanup..." -ForegroundColor Cyan
if ($DeleteFiles -or $Force) {
    $deleteChoice = 'y'
} else {
    Write-Host "Repository root is at: $repoRoot" -ForegroundColor Yellow
    $deleteChoice = Read-Host "Do you want to permanently delete the entire repository folder ($repoRoot)? (y/N)"
}

if ($deleteChoice -eq 'y' -or $deleteChoice -eq 'Y') {
    Write-Host "  -> Deleting $repoRoot in 3 seconds..." -ForegroundColor Red
    Start-Sleep -Seconds 3
    
    # Run a detached cmd process to delete the folder after this script exits
    $cleanCmd = "timeout /t 2 /nobreak >nul & rd /s /q `"$repoRoot`""
    Start-Process -FilePath "cmd.exe" -ArgumentList "/c $cleanCmd" -WindowStyle Hidden
    Write-Host "  -> Deletion process initiated. The directory will be removed." -ForegroundColor Green
} else {
    Write-Host "  -> Skipped folder deletion. You can manually delete: $repoRoot" -ForegroundColor DarkGray
}

Write-Host ""
Write-Host "==========================================================" -ForegroundColor Green
Write-Host "   Decommission Complete! All services and rules removed. " -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Green
