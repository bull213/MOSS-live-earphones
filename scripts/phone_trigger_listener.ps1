param(
    [int]$Port = 8008
)

$repoRoot = Split-Path -Parent $PSScriptRoot
$cmdScript = Join-Path $repoRoot 'Start MOSS Live.cmd'

function Test-BackendReady {
    try {
        $null = Invoke-RestMethod -Uri 'http://127.0.0.1:8000/health' -TimeoutSec 1 -ErrorAction Stop
        return $true
    } catch {
        return $false
    }
}

Write-Host "MOSS Live Phone Trigger Listener active on UDP port $Port..." -ForegroundColor Cyan
Write-Host "Waiting for app launch signal from phone..." -ForegroundColor DarkGray

$endpoint = New-Object System.Net.IPEndPoint ([System.Net.IPAddress]::Any, $Port)
$udpClient = New-Object System.Net.Sockets.UdpClient $Port

try {
    while ($true) {
        $bytes = $udpClient.Receive([ref]$endpoint)
        $message = [System.Text.Encoding]::UTF8.GetString($bytes)
        Write-Host "[TRIGGER] Signal received from $($endpoint.Address): $message" -ForegroundColor Yellow

        if ($message -like "*WAKE_MOSS_LIVE*") {
            if (-not (Test-BackendReady)) {
                Write-Host "--> Launching Start MOSS Live.cmd on PC..." -ForegroundColor Green
                Start-Process -FilePath "cmd.exe" -ArgumentList "/c `"$cmdScript`"" -WorkingDirectory $repoRoot
            } else {
                Write-Host "--> Backend is already running." -ForegroundColor Green
            }

            # Reply back to phone with ACK
            $reply = [System.Text.Encoding]::UTF8.GetBytes("MOSS_LIVE_ACK:8000")
            $null = $udpClient.Send($reply, $reply.Length, $endpoint)
        }
    }
} finally {
    $udpClient.Close()
}
