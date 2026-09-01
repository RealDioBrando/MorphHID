# Reports or changes local Windows Bluetooth discovery/connectability without the Settings UI.
# Usage: .\BluetoothRadioState.ps1 [-Discoverable] [-NotDiscoverable]
param(
    [switch]$Discoverable,
    [switch]$NotDiscoverable
)

$ErrorActionPreference = 'Stop'
Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
namespace MorphHID.Windows {
    public static class BluetoothRadioApi {
        [DllImport("bthprops.cpl")]
        [return: MarshalAs(UnmanagedType.Bool)]
        public static extern bool BluetoothEnableDiscovery(IntPtr radio, bool enabled);

        [DllImport("bthprops.cpl")]
        [return: MarshalAs(UnmanagedType.Bool)]
        public static extern bool BluetoothIsDiscoverable(IntPtr radio);

        [DllImport("bthprops.cpl")]
        [return: MarshalAs(UnmanagedType.Bool)]
        public static extern bool BluetoothEnableIncomingConnections(IntPtr radio, bool enabled);

        [DllImport("bthprops.cpl")]
        [return: MarshalAs(UnmanagedType.Bool)]
        public static extern bool BluetoothIsConnectable(IntPtr radio);
    }
}
"@

if ($Discoverable) {
    if (-not [MorphHID.Windows.BluetoothRadioApi]::BluetoothEnableIncomingConnections([IntPtr]::Zero, $true)) {
        throw "Failed to enable incoming Bluetooth connections."
    }
    if (-not [MorphHID.Windows.BluetoothRadioApi]::BluetoothEnableDiscovery([IntPtr]::Zero, $true)) {
        throw "Failed to make Windows Bluetooth discoverable."
    }
}

if ($NotDiscoverable) {
    if (-not [MorphHID.Windows.BluetoothRadioApi]::BluetoothEnableDiscovery([IntPtr]::Zero, $false)) {
        throw "Failed to make Windows Bluetooth non-discoverable."
    }
}

[pscustomobject]@{
    Discoverable = [MorphHID.Windows.BluetoothRadioApi]::BluetoothIsDiscoverable([IntPtr]::Zero)
    Connectable = [MorphHID.Windows.BluetoothRadioApi]::BluetoothIsConnectable([IntPtr]::Zero)
} | Format-List
