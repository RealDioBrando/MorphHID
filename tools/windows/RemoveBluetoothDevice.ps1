# Removes a remembered Bluetooth pairing without opening the Windows Settings UI.
# Usage:
#   .\RemoveBluetoothDevice.ps1 -List
#   .\RemoveBluetoothDevice.ps1 -Address AA:BB:CC:DD:EE:FF -Remove
# BluetoothRemoveDevice also removes remembered-but-unauthenticated entries when present.

param(
    [string]$Address,
    [switch]$List,
    [switch]$Remove
)

$ErrorActionPreference = 'Stop'

Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;

namespace MorphHID.Windows {
    [StructLayout(LayoutKind.Sequential)]
    public struct BLUETOOTH_ADDRESS {
        public ulong Value;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct SYSTEMTIME {
        public ushort Year, Month, DayOfWeek, Day, Hour, Minute, Second, Milliseconds;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    public struct BLUETOOTH_DEVICE_INFO {
        public int Size;
        public BLUETOOTH_ADDRESS Address;
        public uint ClassOfDevice;
        public int Connected;
        public int Remembered;
        public int Authenticated;
        public SYSTEMTIME LastSeen;
        public SYSTEMTIME LastUsed;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 248)]
        public string Name;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct BLUETOOTH_DEVICE_SEARCH_PARAMS {
        public int Size;
        public int ReturnAuthenticated;
        public int ReturnRemembered;
        public int ReturnUnknown;
        public int ReturnConnected;
        public int IssueInquiry;
        public byte TimeoutMultiplier;
        public IntPtr Radio;
    }

    public static class BluetoothApi {
        [DllImport("bthprops.cpl", SetLastError = true)]
        public static extern IntPtr BluetoothFindFirstDevice(ref BLUETOOTH_DEVICE_SEARCH_PARAMS searchParams, ref BLUETOOTH_DEVICE_INFO deviceInfo);

        [DllImport("bthprops.cpl", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        public static extern bool BluetoothFindNextDevice(IntPtr findHandle, ref BLUETOOTH_DEVICE_INFO deviceInfo);

        [DllImport("bthprops.cpl", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        public static extern bool BluetoothFindDeviceClose(IntPtr findHandle);

        [DllImport("bthprops.cpl", SetLastError = false)]
        public static extern uint BluetoothRemoveDevice(ref BLUETOOTH_ADDRESS address);
    }
}
"@

function ConvertTo-BluetoothAddress([string]$Text) {
    $normalized = $Text -replace '[^0-9A-Fa-f]', ''
    if ($normalized.Length -ne 12) {
        throw "Bluetooth address must contain exactly 12 hex digits: $Text"
    }
    $bytes = for ($i = 0; $i -lt 12; $i += 2) { [Convert]::ToByte($normalized.Substring($i, 2), 16) }
    $bytes += @(0, 0)
    [array]::Reverse($bytes)
    return [BitConverter]::ToUInt64($bytes, 0)
}

function Format-BluetoothAddress([UInt64]$Value) {
    $bytes = [BitConverter]::GetBytes($Value)
    if ($bytes.Length -gt 6) { $bytes = $bytes[0..5] }
    [array]::Reverse($bytes)
    return (($bytes | ForEach-Object { $_.ToString('X2') }) -join ':')
}

$search = New-Object MorphHID.Windows.BLUETOOTH_DEVICE_SEARCH_PARAMS
$search.Size = [Runtime.InteropServices.Marshal]::SizeOf([type][MorphHID.Windows.BLUETOOTH_DEVICE_SEARCH_PARAMS])
$search.ReturnAuthenticated = 1
$search.ReturnRemembered = 1
$search.ReturnUnknown = 1
$search.ReturnConnected = 1
$search.IssueInquiry = 0
$search.TimeoutMultiplier = 0
$search.Radio = [IntPtr]::Zero

$device = New-Object MorphHID.Windows.BLUETOOTH_DEVICE_INFO
$device.Size = [Runtime.InteropServices.Marshal]::SizeOf([type][MorphHID.Windows.BLUETOOTH_DEVICE_INFO])

$find = [MorphHID.Windows.BluetoothApi]::BluetoothFindFirstDevice([ref]$search, [ref]$device)
if ($find -eq [IntPtr]::Zero) {
    $errorCode = [Runtime.InteropServices.Marshal]::GetLastWin32Error()
    throw "BluetoothFindFirstDevice failed (Win32 error $errorCode)."
}

$devices = @()
try {
    do {
        $current = $device
        $devices += [pscustomobject]@{
            Name = $current.Name
            Address = Format-BluetoothAddress $current.Address.Value
            Numeric = ('0x{0:X12}' -f $current.Address.Value)
            NativeAddress = $current.Address.Value
            ClassOfDevice = ('0x{0:X8}' -f $current.ClassOfDevice)
            Connected = ($current.Connected -ne 0)
            Remembered = ($current.Remembered -ne 0)
            Authenticated = ($current.Authenticated -ne 0)
        }
    } while ([MorphHID.Windows.BluetoothApi]::BluetoothFindNextDevice($find, [ref]$device))
} finally {
    [void][MorphHID.Windows.BluetoothApi]::BluetoothFindDeviceClose($find)
}

if ($List -or -not $Address) {
    $devices | Sort-Object Address | Format-Table -AutoSize
    return
}

$target = $devices | Where-Object Address -eq ($Address -replace '-', ':').ToUpperInvariant()
if (-not $target) {
    # Handle addresses entered using lowercase or alternate separators.
    $normalizedTarget = ($Address -replace '[^0-9A-Fa-f]', '').ToUpperInvariant()
    $target = $devices | Where-Object { ($_.Address -replace '[^0-9A-Fa-f]', '').ToUpperInvariant() -eq $normalizedTarget }
}

if (-not $target) {
    throw "Bluetooth device not found: $Address"
}

$target | Format-List

if (-not $Remove) {
    return
}

$nativeAddress = New-Object MorphHID.Windows.BLUETOOTH_ADDRESS
$nativeAddress.Value = [UInt64]$target.NativeAddress
$result = [MorphHID.Windows.BluetoothApi]::BluetoothRemoveDevice([ref]$nativeAddress)
if ($result -eq 0) {
    Write-Host "Removed Bluetooth pairing for $Address." -ForegroundColor Green
} elseif ($result -eq 1168) {
    Write-Host "Windows reports the device was already absent." -ForegroundColor Yellow
} else {
    throw "BluetoothRemoveDevice failed with Win32 error $result."
}
