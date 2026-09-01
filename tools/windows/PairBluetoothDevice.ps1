# Attempts programmatic Windows-initiated Bluetooth pairing without the Settings UI.
# Run with Windows PowerShell 5.1:
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\PairBluetoothDevice.ps1 -Address AA:BB:CC:DD:EE:FF -Info
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\PairBluetoothDevice.ps1 -Address AA:BB:CC:DD:EE:FF -Accept
param(
    [Parameter(Mandatory = $true)]
    [string]$Address,
    [switch]$Info,
    [switch]$Accept,
    [switch]$UseDefaultPairing,
    [ValidateSet("ConfirmOnly", "DisplayPin", "ProvidePin", "ConfirmPinMatch", "All")]
    [string]$PairingKind = "ConfirmOnly",
    [int]$TimeoutSeconds = 90
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Runtime.WindowsRuntime

$null = [Windows.Devices.Bluetooth.BluetoothDevice, Windows.Devices.Bluetooth, ContentType = WindowsRuntime]
$null = [Windows.Devices.Enumeration.DeviceInformation, Windows.Devices.Enumeration, ContentType = WindowsRuntime]

function ConvertTo-WindowsBluetoothAddress([string]$Text) {
    $normalized = ($Text -replace '[^0-9A-Fa-f]', '').ToUpperInvariant()
    if ($normalized.Length -ne 12) {
        throw "Bluetooth address must contain exactly 12 hex digits: $Text"
    }
    # WinRT BluetoothDevice.FromBluetoothAddressAsync uses the address as a
    # straight 48-bit hex value (AA:BB:CC:DD:EE:FF -> 0xAA:BB:CC:DD:EE:FF_HEX).
    return [Convert]::ToUInt64($normalized, 16)
}

$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() |
    Where-Object {
        $_.Name -eq 'AsTask' -and
        $_.IsGenericMethodDefinition -and
        $_.GetParameters().Count -eq 1 -and
        $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
    } | Select-Object -First 1)

function Await-WinRtOperation($Operation, [Type]$ResultType) {
    $netTask = $asTaskGeneric.MakeGenericMethod($ResultType).Invoke($null, @($Operation))
    if (-not $netTask.Wait($TimeoutSeconds * 1000)) {
        throw "WinRT operation timed out after $TimeoutSeconds seconds."
    }
    return $netTask.Result
}

$bluetoothAddress = ConvertTo-WindowsBluetoothAddress $Address
$device = Await-WinRtOperation ([Windows.Devices.Bluetooth.BluetoothDevice]::FromBluetoothAddressAsync($bluetoothAddress)) ([Windows.Devices.Bluetooth.BluetoothDevice])

$pairing = $device.DeviceInformation.Pairing
$deviceInfo = [pscustomobject]@{
    Name = $device.Name
    Address = $device.BluetoothAddress
    Id = $device.DeviceInformation.Id
    CanPair = $pairing.CanPair
    IsPaired = $pairing.IsPaired
    ProtectionLevel = $pairing.ProtectionLevel
}
$deviceInfo | Format-List

if ($Info) {
    $pairing.Custom | Get-Member | Format-Table Name, MemberType -AutoSize
    $pairing.Custom | Get-Member PairAsync | Format-List Name, Definition
    return
}
if (-not $pairing.CanPair) { throw "Windows reports that this device cannot be paired." }
if ($pairing.IsPaired) { Write-Host "Device is already paired." -ForegroundColor Green; return }

$eventLog = New-Object 'System.Collections.Concurrent.ConcurrentQueue[string]'
$eventHandler = $null
$eventCallback = $null
if ($Accept) {
    Add-Type -Path (Join-Path $PSScriptRoot "..\..\build\MorphPairingHandler.dll")
    $eventCallback = [Action[string]] {
        param($line)
        $eventLog.Enqueue($line)
        Write-Host $line -ForegroundColor Cyan
    }
    $eventHandler = New-Object MorphHID.Windows.PairingHandler($eventCallback)
    $eventHandler.Attach($pairing.Custom)
}

try {
    $kinds = switch ($PairingKind) {
        "ConfirmOnly" { [Windows.Devices.Enumeration.DevicePairingKinds]::ConfirmOnly }
        "DisplayPin" { [Windows.Devices.Enumeration.DevicePairingKinds]::DisplayPin }
        "ProvidePin" { [Windows.Devices.Enumeration.DevicePairingKinds]::ProvidePin }
        "ConfirmPinMatch" { [Windows.Devices.Enumeration.DevicePairingKinds]::ConfirmPinMatch }
        "All" {
            [Windows.Devices.Enumeration.DevicePairingKinds]::ConfirmOnly -bor
            [Windows.Devices.Enumeration.DevicePairingKinds]::DisplayPin -bor
            [Windows.Devices.Enumeration.DevicePairingKinds]::ProvidePin -bor
            [Windows.Devices.Enumeration.DevicePairingKinds]::ConfirmPinMatch
        }
    }
    if ($UseDefaultPairing) {
        $operation = $pairing.PairAsync()
    } else {
        $operation = $pairing.Custom.PairAsync($kinds)
    }
    $result = Await-WinRtOperation $operation ([Windows.Devices.Enumeration.DevicePairingResult])
} finally {
    if ($eventHandler) {
        $eventHandler.Detach($pairing.Custom)
    }
}

$eventMessages = [System.Collections.Generic.List[string]]::new()
$eventLog.ToArray() | ForEach-Object { [void]$eventMessages.Add($_) }

[pscustomobject]@{
    Status = $result.Status
    ProtectionLevelUsed = $result.ProtectionLevelUsed
    HandlerEvents = (($eventMessages | Select-Object -Unique) -join '; ')
} | Format-List

if ($result.Status -ne [Windows.Devices.Enumeration.DevicePairingResultStatus]::Paired) {
    throw "Pairing failed with status: $($result.Status)"
}
Write-Host "Paired successfully." -ForegroundColor Green
