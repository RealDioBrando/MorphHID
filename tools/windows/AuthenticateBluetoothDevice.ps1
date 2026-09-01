param(
    [Parameter(Mandatory = $true)]
    [string]$Address
)
$ErrorActionPreference = 'Stop'
Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
namespace MorphHID.Windows {
    [StructLayout(LayoutKind.Sequential)]
    public struct BLUETOOTH_ADDRESS { public ulong Value; }
    [StructLayout(LayoutKind.Sequential)]
    public struct SYSTEMTIME { public ushort Year, Month, DayOfWeek, Hour, Minute, Second, Milliseconds; }
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    public struct BLUETOOTH_DEVICE_INFO {
        public int Size; public BLUETOOTH_ADDRESS Address; public uint ClassOfDevice; public int Connected;
        public int Remembered; public int Authenticated; public SYSTEMTIME LastSeen; public SYSTEMTIME LastUsed;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 248)] public string Name;
    }
    [StructLayout(LayoutKind.Sequential)]
    public struct BLUETOOTH_DEVICE_SEARCH_PARAMS {
        public int Size; public int ReturnAuthenticated; public int ReturnRemembered; public int ReturnUnknown;
        public int ReturnConnected; public int IssueInquiry; public byte TimeoutMultiplier; public IntPtr Radio;
    }
    public static class BluetoothPairApi {
        [DllImport("bthprops.cpl", SetLastError = true)]
        public static extern IntPtr BluetoothFindFirstDevice(ref BLUETOOTH_DEVICE_SEARCH_PARAMS searchParams, ref BLUETOOTH_DEVICE_INFO deviceInfo);
        [DllImport("bthprops.cpl")] [return: MarshalAs(UnmanagedType.Bool)]
        public static extern bool BluetoothFindNextDevice(IntPtr findHandle, ref BLUETOOTH_DEVICE_INFO deviceInfo);
        [DllImport("bthprops.cpl")] [return: MarshalAs(UnmanagedType.Bool)]
        public static extern bool BluetoothFindDeviceClose(IntPtr findHandle);
        [DllImport("bthprops.cpl", SetLastError = true)]
        public static extern uint BluetoothAuthenticateDeviceEx(IntPtr hwndParent, IntPtr hRadio, ref BLUETOOTH_DEVICE_INFO pbtdi, IntPtr pbtOobData, int authenticationRequirement);
    }
}
"@
function ConvertTo-BluetoothAddress([string]$Text) {
    $normalized = ($Text -replace '[^0-9A-Fa-f]', '').ToUpperInvariant()
    if ($normalized.Length -ne 12) { throw "Bluetooth address must contain exactly 12 hex digits: $Text" }
    return [Convert]::ToUInt64($normalized, 16)
}
$search = New-Object MorphHID.Windows.BLUETOOTH_DEVICE_SEARCH_PARAMS
$search.Size = [Runtime.InteropServices.Marshal]::SizeOf([type][MorphHID.Windows.BLUETOOTH_DEVICE_SEARCH_PARAMS])
$search.ReturnAuthenticated = 1; $search.ReturnRemembered = 1; $search.ReturnUnknown = 1; $search.ReturnConnected = 1
$search.IssueInquiry = 0; $search.TimeoutMultiplier = 0; $search.Radio = [IntPtr]::Zero
$device = New-Object MorphHID.Windows.BLUETOOTH_DEVICE_INFO
$device.Size = [Runtime.InteropServices.Marshal]::SizeOf([type][MorphHID.Windows.BLUETOOTH_DEVICE_INFO])
$find = [MorphHID.Windows.BluetoothPairApi]::BluetoothFindFirstDevice([ref]$search, [ref]$device)
if ($find -eq [IntPtr]::Zero) { throw "BluetoothFindFirstDevice failed: $([Runtime.InteropServices.Marshal]::GetLastWin32Error())" }
$targetAddress = ConvertTo-BluetoothAddress $Address
$found = $false
try {
    do {
        if ($device.Address.Value -eq $targetAddress) { $found = $true; break }
    } while ([MorphHID.Windows.BluetoothPairApi]::BluetoothFindNextDevice($find, [ref]$device))
} finally { [void][MorphHID.Windows.BluetoothPairApi]::BluetoothFindDeviceClose($find) }
if (-not $found) { throw "Device not found: $Address" }
Write-Host "Authenticating $($device.Name) ($Address)..."
$result = [MorphHID.Windows.BluetoothPairApi]::BluetoothAuthenticateDeviceEx([IntPtr]::Zero, [IntPtr]::Zero, [ref]$device, [IntPtr]::Zero, 0)
Write-Host "BluetoothAuthenticateDeviceEx result: $result"
if ($result -ne 0) { throw "BluetoothAuthenticateDeviceEx failed with Win32 error $result" }
