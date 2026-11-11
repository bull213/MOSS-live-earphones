param(
    [Parameter(Mandatory = $true)] [string]$NdkPath
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$androidRoot = Split-Path -Parent $PSScriptRoot
$source = Join-Path $androidRoot 'third_party/moonshine-jni'
$work = Join-Path $androidRoot 'build/moonshine-native-rebuild'
$aar = Join-Path $work 'moonshine-voice-0.1.6.aar'
$clang = Join-Path $NdkPath 'toolchains/llvm/prebuilt/windows-x86_64/bin/clang++.exe'
if (-not (Test-Path -LiteralPath $clang)) { throw 'NDK clang++ not found; pass the Android NDK r28c directory.' }
New-Item -ItemType Directory -Force -Path $work | Out-Null
if (-not (Test-Path -LiteralPath $aar)) {
    Invoke-WebRequest -UseBasicParsing -Uri 'https://repo.maven.apache.org/maven2/ai/moonshine/moonshine-voice/0.1.6/moonshine-voice-0.1.6.aar' -OutFile $aar
}
$expected = '65282ac1a1381ee99ecf1190f9059c9ef41684e8afb3fea90db8ca150865b52d'
if ((Get-FileHash -Algorithm SHA256 -LiteralPath $aar).Hash -ne $expected) { throw 'Moonshine AAR checksum mismatch.' }
& tar.exe -xf $aar -C $work jni
if ($LASTEXITCODE -ne 0) { throw 'Could not extract Moonshine native libraries.' }

$targets = @(
    @{ abi = 'arm64-v8a'; triple = 'aarch64-linux-android31' },
    @{ abi = 'armeabi-v7a'; triple = 'armv7a-linux-androideabi31' },
    @{ abi = 'x86_64'; triple = 'x86_64-linux-android31' }
)
foreach ($target in $targets) {
    $libDir = Join-Path $work "jni/$($target.abi)"
    $outputDir = Join-Path $androidRoot "app/src/main/jniLibs/$($target.abi)"
    New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
    & $clang "--target=$($target.triple)" -std=c++17 -O2 -fPIC -shared -static-libstdc++ -DANDROID=1 `
        '-Wl,-z,max-page-size=16384' '-Wl,-soname,libmoonshine-jni.so' "-I$source" `
        (Join-Path $source 'moonshine-jni.cpp') "-L$libDir" -lmoonshine -lonnxruntime -llog -landroid `
        -o (Join-Path $outputDir 'libmoonshine-jni.so')
    if ($LASTEXITCODE -ne 0) { throw "JNI build failed for $($target.abi)." }
    Write-Host "Built patched Moonshine JNI for $($target.abi)."
}
