# Automated Backup Script for Visual Assistant Knowledge & NotebookLM Credentials
param(
    [int]$RetainDays = 7
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$backendRoot = Join-Path $repoRoot 'backend'
$dataRoot = Join-Path $backendRoot '.data'
$backupRoot = Join-Path $dataRoot 'backups'

$timestamp = Get-Date -Format 'yyyyMMdd_HHmmss'
$targetBackupDir = Join-Path $backupRoot "backup_$timestamp"

New-Item -ItemType Directory -Path $targetBackupDir -Force | Out-Null
Write-Host "Creating backup in: $targetBackupDir" -ForegroundColor Cyan

# 1. Back up Knowledge SQLite Database safely
$dbPath = Join-Path $dataRoot 'knowledge\library.sqlite3'
if (Test-Path -LiteralPath $dbPath) {
    $dbBackupDest = Join-Path $targetBackupDir 'library.sqlite3'
    # Check if sqlite3 executable is available to do a clean online backup
    $sqliteExe = Get-Command sqlite3.exe -ErrorAction SilentlyContinue
    if ($sqliteExe) {
        & $sqliteExe $dbPath ".backup `"$dbBackupDest`""
        Write-Host "  -> library.sqlite3 backed up via sqlite3 online backup." -ForegroundColor Green
    } else {
        Copy-Item -LiteralPath $dbPath -Destination $dbBackupDest -Force
        Write-Host "  -> library.sqlite3 backed up via file copy." -ForegroundColor Green
    }
}

# 2. Back up NotebookLM Tokens & State
$nlmDir = Join-Path $dataRoot 'notebooklm'
if (Test-Path -LiteralPath $nlmDir) {
    $nlmBackupDest = Join-Path $targetBackupDir 'notebooklm'
    New-Item -ItemType Directory -Path $nlmBackupDest -Force | Out-Null
    
    foreach ($file in @('master_token.json', 'storage_state.json', 'context.json')) {
        $sourceFile = Join-Path $nlmDir $file
        if (Test-Path -LiteralPath $sourceFile) {
            Copy-Item -LiteralPath $sourceFile -Destination (Join-Path $nlmBackupDest $file) -Force
            Write-Host "  -> $file backed up." -ForegroundColor Green
        }
    }
}

# 3. Back up backend .env
$envPath = Join-Path $backendRoot '.env'
if (Test-Path -LiteralPath $envPath) {
    Copy-Item -LiteralPath $envPath -Destination (Join-Path $targetBackupDir '.env') -Force
    Write-Host "  -> backend/.env backed up." -ForegroundColor Green
}

# 4. Prune older backups
$cutoff = (Get-Date).AddDays(-$RetainDays)
Get-ChildItem -Path $backupRoot -Directory -Filter "backup_*" | Where-Object { $_.CreationTime -lt $cutoff } | ForEach-Object {
    Write-Host "  -> Pruning expired backup: $($_.Name)" -ForegroundColor DarkGray
    Remove-Item -LiteralPath $_.FullName -Recurse -Force
}

Write-Host "Backup completed successfully!" -ForegroundColor Green
