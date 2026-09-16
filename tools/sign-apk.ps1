# Sign an APK with the standard Android debug key.
#
# Why this file exists: `assembleRelease` produces an *unsigned* APK, which
# cannot be installed by tapping it. The regular debug keystore
# (%USERPROFILE%\.android\debug.keystore, alias `androiddebugkey`, password
# `android`) is enough to hand a small, shippable build to a test device without
# setting up a release keystore.
#
# Usage:
#   .\tools\sign-apk.ps1
#
# NOTE: pure ASCII on purpose (PowerShell 5.1 reads BOM-less .ps1 as the system
# ANSI codepage; see the project ERROR.md E1 / memory/11).

[CmdletBinding()]
param(
    [string]$In,
    [string]$Out
)

$ErrorActionPreference = 'Stop'

# Resolve the project root from this script's own location. $PSScriptRoot is not
# always populated when invoked with `powershell -File`, so derive it defensively.
$scriptPath  = $MyInvocation.MyCommand.Path
$scriptDir   = Split-Path -Parent $scriptPath
$projectRoot = (Resolve-Path (Join-Path $scriptDir '..')).Path

if (-not $In) {
    $In = Join-Path $projectRoot 'app\build\outputs\apk\release\app-release-unsigned.apk'
}
if (-not $Out) {
    $Out = Join-Path $projectRoot 'app\build\outputs\apk\release\keyforge-probe-release.apk'
}

$sdk       = 'D:\Program\Android\SDK'
$buildTool = Join-Path $sdk 'build-tools\36.1.0'
$zipalign  = Join-Path $buildTool 'zipalign.exe'
$apksigner = Join-Path $buildTool 'apksigner.bat'
$keystore  = Join-Path $env:USERPROFILE '.android\debug.keystore'
$jdkHome   = 'C:\Program Files\Microsoft\jdk-21.0.8.9-hotspot'

foreach ($tool in @($zipalign, $apksigner, $keystore)) {
    if (-not (Test-Path $tool)) { throw "missing required file: $tool" }
}
if (-not (Test-Path $In)) { throw "input APK not found: $In (run assembleRelease first)" }

$env:JAVA_HOME = $jdkHome

$aligned = Join-Path (Split-Path -Parent $Out) 'keyforge-probe-release-aligned.apk'
foreach ($f in @($aligned, $Out)) { if (Test-Path $f) { Remove-Item $f -Force } }

Write-Host "zipalign: $In"
& $zipalign -p -f 4 $In $aligned
if ($LASTEXITCODE -ne 0) { throw "zipalign failed with exit code $LASTEXITCODE" }

Write-Host "apksigner: $Out"
& $apksigner sign --ks $keystore --ks-pass pass:android --key-pass pass:android --out $Out $aligned
if ($LASTEXITCODE -ne 0) { throw "apksigner failed with exit code $LASTEXITCODE" }

Remove-Item $aligned -Force
$size = [math]::Round((Get-Item $Out).Length / 1MB, 2)
Write-Host "signed APK: $Out ($size MB)"

# Verify the signature we just produced instead of trusting the exit code.
& $apksigner verify --print-certs $Out
if ($LASTEXITCODE -ne 0) { throw "apksigner verify failed with exit code $LASTEXITCODE" }
