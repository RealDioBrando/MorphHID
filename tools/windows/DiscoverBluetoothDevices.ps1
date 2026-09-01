param([int]$TimeoutMultiplier = 10)
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
    public static class BluetoothDiscoveryApi {
        [DllImport("bthprops.cpl", SetLastError = true)]
        public static extern IntPtr BluetoothFindFirstDevice(ref BLUETOOTH_DEVICE_SEARCH_PARAMS searchParams, ref BLUETOOTH_DEVICE_INFO deviceInfo);
        [DllImport("bthprops.cpl")] [return: MarshalAs(UnmanagedType.Bool)]
        public static extern bool BluetoothFindNextDevice(IntPtr findHandle, ref BLUETOOTH_DEVICE_INFO deviceInfo);
        [DllImport("bthprops.cpl")] [return: MarshalAs(UnmanagedType.Bool)]
        public static extern bool BluetoothFindDeviceClose(IntPtr findHandle);
    }
}
"@
function Format-BluetoothAddress([UInt64]$Value) {
    $bytes = [BitConverter]::GetBytes($Value)
    if ($bytes.Length -gt 6) { $bytes = $bytes[0..5] }
    [array]::Reverse($bytes)
    return (($bytes | ForEach-Object { $_.ToString('X2') }) -join ':')
}
$search = New-Object MorphHID.Windows.BLUETOOTH_DEVICE_SEARCH_PARAMS
$search.Size = [Runtime.InteropServices.Marshal]::SizeOf([type][MorphHID.Windows.BLUETOOTH_DEVICE_SEARCH_PARAMS])
$search.ReturnAuthenticated = 1; $search.ReturnRemembered = 1; $search.ReturnUnknown = 1; $search.ReturnConnected = 1
$search.IssueInquiry = 1; $search.TimeoutMultiplier = [byte]$TimeoutMultiplier; $search.Radio = [IntPtr]::Zero
$device = New-Object MorphHID.Windows.BLUETOOTH_DEVICE_INFO
$device.Size = [Runtime.InteropServices.Marshal]::SizeOf([type][MorphHID.Windows.BLUETOOTH_DEVICE_INFO])
$find = [MorphHID.Windows.BluetoothDiscoveryApi]::BluetoothFindFirstDevice([ref]$search, [ref]$device)
if ($find -eq [IntPtr]::Zero) { throw "Bluetooth inquiry failed: $([Runtime.InteropServices.Marshal]::GetLastWin32Error())" }
$devices = @()
try {
    do {
        $current = $device
        $devices += [pscustomobject]@{
            Name = $current.Name
            Address = Format-BluetoothAddress $current.Address.Value
            ClassOfDevice = ('0x{0:X8}' -f $current.ClassOfDevice)
            Connected = ($current.Connected -ne 0)
            Remembered = ($current.Remembered -ne 0)
            Authenticated = ($current.Authenticated -ne 0)
        }
    } while ([MorphHID.Windows.BluetoothDiscoveryApi]::BluetoothFindNextDevice($find, [ref]$device))
} finally { [void][MorphHID.Windows.BluetoothDiscoveryApi]::BluetoothFindDeviceClose($find) }
$devices | Sort-Object Address | Format-Table -AutoSize
