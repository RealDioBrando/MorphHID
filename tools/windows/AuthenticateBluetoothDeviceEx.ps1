# Attempts a Windows-initiated classic Bluetooth pairing using the legacy
# BluetoothAuthenticateDeviceEx API with an authentication callback.
# This is a diagnostics tool; it intentionally does not touch MorphHID sources.

param(
    [Parameter(Mandatory = $true)]
    [string]$Address,
    [ValidateSet("NotRequired", "Required", "NotRequiredBonding", "RequiredBonding", "NotRequiredGeneralBonding", "RequiredGeneralBonding", "NotDefined")]
    [string]$Requirement = "NotRequired",
    [switch]$Blind,
    [switch]$NoAutoAccept,
    [int]$TimeoutSeconds = 90
)

$ErrorActionPreference = 'Stop'
$source = @"
using System;
using System.Collections.Concurrent;
using System.Runtime.InteropServices;
using System.Text;

namespace MorphHID.Windows {
    public enum AuthenticationMethod {
        Legacy = 0x1,
        Oob = 0x2,
        NumericComparison = 0x3,
        PasskeyNotification = 0x4,
        Passkey = 0x5
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct BLUETOOTH_ADDRESS { public ulong Value; }

    [StructLayout(LayoutKind.Sequential)]
    public struct SYSTEMTIME { public ushort Year, Month, DayOfWeek, Hour, Minute, Second, Milliseconds; }

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
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 248)] public string Name;
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

    [StructLayout(LayoutKind.Sequential)]
    public struct BLUETOOTH_AUTHENTICATION_CALLBACK_PARAMS {
        public BLUETOOTH_DEVICE_INFO Device;
        public AuthenticationMethod Method;
        public int IoCapability;
        public int AuthenticationRequirements;
        public uint NumericValue;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct BLUETOOTH_OOB_DATA_INFO {
        [MarshalAs(UnmanagedType.ByValArray, SizeConst = 16)] public byte[] C;
        [MarshalAs(UnmanagedType.ByValArray, SizeConst = 16)] public byte[] R;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct BLUETOOTH_AUTHENTICATE_RESPONSE {
        public BLUETOOTH_ADDRESS Address;
        public AuthenticationMethod Method;
        [MarshalAs(UnmanagedType.ByValArray, SizeConst = 32)] public byte[] Data;
        public byte NegativeResponse;
    }

    public delegate bool AuthenticationCallback(IntPtr parameter, ref BLUETOOTH_AUTHENTICATION_CALLBACK_PARAMS callbackParams);

    public sealed class PairingAgent : IDisposable {
        private readonly ConcurrentQueue<string> events = new ConcurrentQueue<string>();
        private readonly AuthenticationCallback callback;
        private readonly bool autoAccept;
        private IntPtr registration = IntPtr.Zero;

        public PairingAgent(bool autoAccept) {
            this.autoAccept = autoAccept;
            this.callback = new AuthenticationCallback(OnAuthenticationRequested);
        }

        public ConcurrentQueue<string> Events { get { return events; } }

        public uint Register(ref BLUETOOTH_DEVICE_INFO device) {
            return NativeMethods.BluetoothRegisterForAuthenticationEx(ref device, out registration, callback, IntPtr.Zero);
        }

        private bool OnAuthenticationRequested(IntPtr parameter, ref BLUETOOTH_AUTHENTICATION_CALLBACK_PARAMS p) {
            string message = string.Format(
                "AUTH_CALLBACK method={0} io={1} requirements=0x{2:X} value={3} device={4} ({5:X12})",
                p.Method, p.IoCapability, p.AuthenticationRequirements, p.NumericValue,
                p.Device.Name, p.Device.Address.Value);
            events.Enqueue(message);
            Console.WriteLine(message);

            if (!autoAccept) {
                events.Enqueue("AUTH_CALLBACK ignored (NoAutoAccept)");
                return false;
            }

            var response = new BLUETOOTH_AUTHENTICATE_RESPONSE();
            response.Address.Value = p.Device.Address.Value;
            response.Method = p.Method;
            response.NegativeResponse = 0;

            response.Data = new byte[32];
            if (p.Method == AuthenticationMethod.NumericComparison) {
                BitConverter.GetBytes(p.NumericValue).CopyTo(response.Data, 0);
            } else if (p.Method == AuthenticationMethod.PasskeyNotification || p.Method == AuthenticationMethod.Passkey) {
                BitConverter.GetBytes(p.NumericValue).CopyTo(response.Data, 0);
            } else if (p.Method == AuthenticationMethod.Legacy) {
                response.Data[0] = (byte)'0';
                response.Data[1] = (byte)'0';
                response.Data[2] = (byte)'0';
                response.Data[3] = (byte)'0';
                response.Data[16] = 4;
            }

            uint result = NativeMethods.BluetoothSendAuthenticationResponseEx(IntPtr.Zero, ref response);
            string responseMessage = string.Format("AUTH_RESPONSE method={0} result={1}", response.Method, result);
            events.Enqueue(responseMessage);
            Console.WriteLine(responseMessage);
            return result == 0;
        }

        public void Dispose() {
            if (registration != IntPtr.Zero) {
                NativeMethods.BluetoothUnregisterAuthentication(registration);
                registration = IntPtr.Zero;
            }
            GC.KeepAlive(callback);
        }
    }

    public static class NativeMethods {
        [DllImport("bthprops.cpl", SetLastError = true)]
        public static extern IntPtr BluetoothFindFirstDevice(ref BLUETOOTH_DEVICE_SEARCH_PARAMS searchParams, ref BLUETOOTH_DEVICE_INFO deviceInfo);

        [DllImport("bthprops.cpl")]
        [return: MarshalAs(UnmanagedType.Bool)]
        public static extern bool BluetoothFindNextDevice(IntPtr findHandle, ref BLUETOOTH_DEVICE_INFO deviceInfo);

        [DllImport("bthprops.cpl")]
        [return: MarshalAs(UnmanagedType.Bool)]
        public static extern bool BluetoothFindDeviceClose(IntPtr findHandle);

        [DllImport("bthprops.cpl", SetLastError = true)]
        public static extern uint BluetoothAuthenticateDeviceEx(IntPtr parentWindow, IntPtr radio, ref BLUETOOTH_DEVICE_INFO device, ref BLUETOOTH_OOB_DATA_INFO oobData, int authenticationRequirement);

        [DllImport("bthprops.cpl", EntryPoint = "BluetoothAuthenticateDeviceEx", SetLastError = true)]
        public static extern uint BluetoothAuthenticateDeviceExNoOob(IntPtr parentWindow, IntPtr radio, ref BLUETOOTH_DEVICE_INFO device, IntPtr oobData, int authenticationRequirement);

        [DllImport("bthprops.cpl", SetLastError = true)]
        public static extern uint BluetoothRegisterForAuthenticationEx(ref BLUETOOTH_DEVICE_INFO device, out IntPtr registration, AuthenticationCallback callback, IntPtr parameter);

        [DllImport("bthprops.cpl", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        public static extern bool BluetoothUnregisterAuthentication(IntPtr registration);

        [DllImport("bthprops.cpl", SetLastError = true)]
        public static extern uint BluetoothSendAuthenticationResponseEx(IntPtr radio, ref BLUETOOTH_AUTHENTICATE_RESPONSE response);
    }
}
"@

try {
    Add-Type -TypeDefinition $source -Language CSharp
} catch [System.Reflection.ReflectionTypeLoadException] {
    $_.Exception.LoaderExceptions | ForEach-Object { Write-Host "LOADER_EXCEPTION: $($_.Message)" }
    throw
}

function ConvertTo-BluetoothAddress([string]$Text) {
    $normalized = ($Text -replace '[^0-9A-Fa-f]', '').ToUpperInvariant()
    if ($normalized.Length -ne 12) { throw "Bluetooth address must contain exactly 12 hex digits: $Text" }
    return [Convert]::ToUInt64($normalized, 16)
}

$requirementValue = switch ($Requirement) {
    "NotRequired" { 0 }
    "Required" { 1 }
    "NotRequiredBonding" { 2 }
    "RequiredBonding" { 3 }
    "NotRequiredGeneralBonding" { 4 }
    "RequiredGeneralBonding" { 5 }
    "NotDefined" { 255 }
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
$find = [MorphHID.Windows.NativeMethods]::BluetoothFindFirstDevice([ref]$search, [ref]$device)
if ($find -eq [IntPtr]::Zero) { throw "BluetoothFindFirstDevice failed: $([Runtime.InteropServices.Marshal]::GetLastWin32Error())" }

$targetAddress = ConvertTo-BluetoothAddress $Address
$found = $false
try {
    do {
        if ($device.Address.Value -eq $targetAddress) { $found = $true; break }
    } while ([MorphHID.Windows.NativeMethods]::BluetoothFindNextDevice($find, [ref]$device))
} finally {
    [void][MorphHID.Windows.NativeMethods]::BluetoothFindDeviceClose($find)
}
if (-not $found) { throw "Device not found in Windows Bluetooth cache: $Address" }

Write-Host ("Target: {0} ({1:X12}) COD=0x{2:X8} Connected={3} Remembered={4} Authenticated={5}" -f `
    $device.Name, $device.Address.Value, $device.ClassOfDevice, $device.Connected, $device.Remembered, $device.Authenticated)

if ($device.Authenticated -ne 0) {
    Write-Host "Device is already authenticated." -ForegroundColor Green
    return
}

$agent = New-Object MorphHID.Windows.PairingAgent (-not $NoAutoAccept)
try {
    $registerResult = $agent.Register([ref]$device)
    Write-Host "BluetoothRegisterForAuthenticationEx result: $registerResult"
    if ($registerResult -ne 0) { throw "Failed to register authentication callback (Win32 error $registerResult)" }

    if ($Blind) {
        $oob = New-Object MorphHID.Windows.BLUETOOTH_OOB_DATA_INFO
        $oob.C = New-Object byte[] 16
        $oob.R = New-Object byte[] 16
        Write-Host "Calling BluetoothAuthenticateDeviceEx in blind mode (non-NULL OOB data), requirement=$Requirement..."
        $authResult = [MorphHID.Windows.NativeMethods]::BluetoothAuthenticateDeviceEx(
            [IntPtr]::Zero, [IntPtr]::Zero, [ref]$device, [ref]$oob, $requirementValue)
    } else {
        Write-Host "Calling BluetoothAuthenticateDeviceEx in wizard mode (NULL OOB data), requirement=$Requirement..."
        $authResult = [MorphHID.Windows.NativeMethods]::BluetoothAuthenticateDeviceExNoOob(
            [IntPtr]::Zero, [IntPtr]::Zero, [ref]$device, [IntPtr]::Zero, $requirementValue)
    }

    Write-Host "BluetoothAuthenticateDeviceEx result: $authResult"
    [pscustomobject]@{
        Win32Result = $authResult
        Requirement = $Requirement
        BlindMode = [bool]$Blind
        Events = (($agent.Events.ToArray() | Select-Object -Unique) -join '; ')
        DeviceAfter = ("{0} connected={1} remembered={2} authenticated={3}" -f $device.Name, $device.Connected, $device.Remembered, $device.Authenticated)
    } | Format-List

    if ($authResult -ne 0) { throw "BluetoothAuthenticateDeviceEx failed with Win32 error $authResult" }
    Write-Host "Authentication API returned success." -ForegroundColor Green
} finally {
    $agent.Dispose()
}
