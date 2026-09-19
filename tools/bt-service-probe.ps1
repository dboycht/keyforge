# Ask Windows what it knows about a paired Bluetooth device's services.
#
# Why this file exists: when an Android phone offers itself as a Bluetooth HID
# keyboard and the host refuses the connection, the first question is "does the
# host even have a HID service record for this device?". Windows exposes that
# through the WinRT Bluetooth APIs; this wrapper turns it into one command.
#
# Usage (from anywhere):
#   powershell -File tools\bt-service-probe.ps1                 # auto-pick the phone
#   powershell -File tools\bt-service-probe.ps1 -Name K3        # match by name
#   powershell -File tools\bt-service-probe.ps1 -Address A8:88:CE:CB:B7:4E
#
# Exit code: 0 = a HID service (0x1124 / 0x1812) is present, 1 = absent, 2+ = error.
# NOTE: pure ASCII on purpose (project rules: PowerShell 5.1 reads BOM-less .ps1
# as the system ANSI codepage).

[CmdletBinding()]
param(
    [string]$Name,
    [string]$Address
)

$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$probeDir = Join-Path $scriptDir 'btprobe'

if (-not (Test-Path (Join-Path $probeDir 'BtProbe.csproj'))) {
    throw "probe project not found at $probeDir (expected BtProbe.csproj + Program.cs)"
}

$env:BT_PROBE_NAME = $Name
$env:BT_PROBE_ADDRESS = $Address

Push-Location $probeDir
try {
    & dotnet run -c Release 2>&1
    $code = $LASTEXITCODE
} finally {
    Pop-Location
}

exit $code
