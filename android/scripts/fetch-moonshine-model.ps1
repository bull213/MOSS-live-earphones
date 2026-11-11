Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$androidRoot = Split-Path -Parent $PSScriptRoot
$manifest = Get-Content -Raw -LiteralPath (Join-Path $androidRoot 'moonshine-model-manifest.json') | ConvertFrom-Json
$target = Join-Path $androidRoot 'app/src/main/assets/moonshine/small-streaming-en'
New-Item -ItemType Directory -Force -Path $target | Out-Null

foreach ($entry in $manifest.files) {
    $name = [string]$entry.name
    if ($name -notmatch '^[A-Za-z0-9_.-]+$') { throw "Invalid model filename: $name" }
    $destination = Join-Path $target $name
    if ((Test-Path -LiteralPath $destination) -and
        (Get-Item -LiteralPath $destination).Length -eq [long]$entry.bytes -and
        (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -eq $entry.sha256) {
        Write-Host "$name is already verified."
        continue
    }
    $partial = "$destination.part"
    try {
        Write-Host "Downloading $name..."
        Invoke-WebRequest -UseBasicParsing -Uri ("$($manifest.baseUrl)/$name") -OutFile $partial
        if ((Get-Item -LiteralPath $partial).Length -ne [long]$entry.bytes -or
            (Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash -ne $entry.sha256) {
            throw "Model checksum or size mismatch: $name"
        }
        Move-Item -LiteralPath $partial -Destination $destination -Force
    } finally {
        if (Test-Path -LiteralPath $partial) { Remove-Item -LiteralPath $partial -Force }
    }
}
Write-Host 'Moonshine Small Streaming is ready to bundle.'
