# keyforge dev helper: device check / install / quick launch / probe log capture.
#
# Why this file exists: during on-device debugging the same five adb commands
# (devices -> install -> am start -> logcat) get retyped dozens of times, and the
# logcat invocation is easy to get wrong. One switch per task instead.
#
# Usage (from the project root):
#   powershell -File tools\dev.ps1 -Check            # device state + driver hints
#   powershell -File tools\dev.ps1 -Install          # install the release APK (-r)
#   powershell -File tools\dev.ps1 -Launch           # start the probe activity
#   powershell -File tools\dev.ps1 -Log              # live probe log (KeyForgeProbe)
#   powershell -File tools\dev.ps1 -Log -Fresh       # clear logcat first, then follow
#   powershell -File tools\dev.ps1 -All              # install + launch + fresh log
#   powershell -File tools\dev.ps1 -Uninstall        # remove both variants
#
# NOTE: pure ASCII on purpose. PowerShell 5.1 reads a BOM-less .ps1 using the
# system ANSI codepage; CJK inside the file can break parsing (see
# DEVELOPMENT.md / ERROR.md E1 and workspace memory/11). Keep comments English.

[CmdletBinding()]
param(
    [switch]$Check,
    [switch]$Install,
    [switch]$Launch,
    [switch]$Log,
    [switch]$Fresh,
    [switch]$All,
    [switch]$Uninstall,
    [switch]$DebugVariant,
    [int]$LogSeconds = 0
)

$ErrorActionPreference = 'Stop'

$scriptPath = $MyInvocation.MyCommand.Path
$scriptDir  = Split-Path -Parent $scriptPath
$projectRoot = (Resolve-Path (Join-Path $scriptDir '..')).Path

$sdk = 'D:\Program\Android\SDK'
$adb = Join-Path $sdk 'platform-tools\adb.exe'
if (-not (Test-Path $adb)) { throw "adb not found at $adb" }

$logTag  = 'KeyForgeProbe'
$pkgDbg  = 'com.dboycht.keyforge.debug'
$apkRel  = Join-Path $projectRoot 'app\build\outputs\apk\release\keyforge-probe-release.apk'
$apkDbg  = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'

# Which variant to drive. The debug build is the one that keeps android.util.Log:
# the release build runs R8 with isMinifyEnabled=true, which strips Log calls
# (Android's own guidance for log disclosure), so live logcat is only useful on
# the debug variant. Launching works for both (release id has no suffix).
if ($DebugVariant) {
    $pkg = $pkgDbg
    $apk = $apkDbg
} else {
    $pkg = 'com.dboycht.keyforge'
    $apk = $apkRel
}
$activity = "$pkg/.probe.ProbeActivity"
$activityAlt = 'com.dboycht.keyforge/.probe.ProbeActivity'

function Get-DeviceState {
    $lines = & $adb devices 2>$null | Select-Object -Skip 1 | Where-Object { $_.Trim() -ne '' }
    if (-not $lines) { return @() }
    return $lines | ForEach-Object {
        $parts = ($_ -split '\s+') | Where-Object { $_ -ne '' }
        [pscustomobject]@{ Serial = $parts[0]; State = $parts[1] }
    }
}

function Assert-Authorized {
    $devs = Get-DeviceState
    if (-not $devs) {
        throw "No device attached. Plug the phone in (data cable) and enable USB debugging."
    }
    $bad = $devs | Where-Object { $_.State -ne 'device' }
    if ($bad) {
        $s = ($bad | ForEach-Object { "$($_.Serial)=$($_.State)" }) -join ', '
        throw "Device not ready: $s. If it says 'unauthorized', accept the 'Allow USB debugging?' dialog on the phone (tick 'Always allow')."
    }
    return $devs
}

function Show-Check {
    Write-Host '=== adb version ==='
    & $adb version
    Write-Host ''
    Write-Host '=== devices ==='
    $devs = Get-DeviceState
    if (-not $devs) {
        Write-Host '  (none) - phone not attached or USB debugging off'
    } else {
        $devs | Format-Table -AutoSize
        foreach ($d in $devs) {
            if ($d.State -eq 'unauthorized') {
                Write-Host "  [$($d.Serial)] UNAUTHORIZED -> accept the dialog on the phone screen."
            }
        }
    }
    Write-Host ''
    Write-Host '=== android usb device (Windows side) ==='
    $usb = Get-PnpDevice -PresentOnly -ErrorAction SilentlyContinue |
        Where-Object { $_.InstanceId -match 'VID_22D9|VID_2A70|VID_18D1|VID_2717|VID_0BB4|VID_04E8' }
    if ($usb) { $usb | Select-Object Status, FriendlyName, InstanceId | Format-Table -AutoSize }
    else { Write-Host '  (no Android vendor id on the USB bus)' }

    $devs = Get-DeviceState
    if ($devs -and ($devs | Where-Object { $_.State -eq 'device' })) {
        Write-Host '=== device info ==='
        & $adb shell getprop ro.product.model
        & $adb shell getprop ro.build.version.release
        & $adb shell getprop ro.build.version.sdk
    }
}

function Install-Apk {
    $null = Assert-Authorized
    $path = $apk
    if (-not (Test-Path $path)) {
        $hint = if ($DebugVariant) { '.\gradlew.bat assembleDebug' } else { 'build + sign the release APK' }
        throw "APK not found: $path`nBuild it first ($hint)."
    }
    Write-Host "Installing $path"
    & $adb install -r $path
    if ($LASTEXITCODE -ne 0) { throw "adb install failed (exit $LASTEXITCODE)" }
    Write-Host "Installed. Package: $pkg"
}

function Start-Probe {
    $null = Assert-Authorized
    Write-Host "Launching $activity"
    & $adb shell am start -n $activity
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Direct launch failed; falling back to monkey."
        & $adb shell monkey -p $pkg -c android.intent.category.LAUNCHER 1
    }
}

function Show-Log {
    $null = Assert-Authorized
    $devPid = (& $adb shell pidof $pkg 2>$null | Out-String).Trim()
    if ($Fresh) {
        Write-Host 'Clearing logcat buffer...'
        & $adb logcat -c
    }
    Write-Host "Following logcat for $pkg (tag=$logTag, pid=$devPid); Ctrl+C to stop"
    Write-Host 'NOTE: the release build runs R8 which strips Log calls - use -DebugVariant to see logs.'
    Write-Host ('-' * 60)
    if ($LogSeconds -gt 0) {
        $job = Start-Job -ScriptBlock {
            param($adbPath, $tag, $devPid)
            if ($devPid) { & $adbPath logcat -v time --pid $devPid }
            else { & $adbPath logcat -v time -s $tag }
        } -ArgumentList $adb, $logTag, $devPid
        Start-Sleep -Seconds $LogSeconds
        Receive-Job $job
        Stop-Job $job -ErrorAction SilentlyContinue
        Remove-Job $job -Force -ErrorAction SilentlyContinue
    } else {
        & $adb logcat -v time -s $logTag
    }
}

function Uninstall-Both {
    $null = Assert-Authorized
    foreach ($p in @('com.dboycht.keyforge', $pkgDbg)) {
        Write-Host "Uninstalling $p"
        & $adb uninstall $p
    }
}

$didSomething = $false
if ($All) {
    $didSomething = $true
    Install-Apk
    Start-Probe
    Show-Log
}
if ($Check)     { $didSomething = $true; Show-Check }
if ($Install)   { $didSomething = $true; Install-Apk }
if ($Launch)    { $didSomething = $true; Start-Probe }
if ($Log)       { $didSomething = $true; Show-Log }
if ($Uninstall) { $didSomething = $true; Uninstall-Both }

if (-not $didSomething) {
    Write-Host 'keyforge dev helper - pick one switch:'
    Write-Host '  -Check      device state and driver hints'
    Write-Host '  -Install    install the signed release APK (-r)'
    Write-Host '  -Launch     start the probe activity'
    Write-Host '  -Log        follow the probe logcat tag (add -Fresh to clear first)'
    Write-Host '  -All        install + launch + fresh log'
    Write-Host '  -Uninstall  remove both app variants'
}
