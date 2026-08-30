package dev.morphhid.app.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import dev.morphhid.core.control.HidTransport
import dev.morphhid.core.control.OutputReport
import dev.morphhid.core.control.TransportPhase
import dev.morphhid.core.control.TransportState
import dev.morphhid.core.hid.CompiledHid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [HidTransport] backed by the platform's public HID-device profile API
 * (android.bluetooth.BluetoothHidDevice, available since API 28).
 *
 * Notes kept in docs/NOTES.md:
 *  - Report payloads passed to sendReport() exclude the report-id byte; the
 *    platform sendReport(host, id, data) overload carries the id separately.
 *  - Host->device output reports (e.g. keyboard LEDs) are surfaced through
 *    onInterruptData; LED decoding happens in the codec.
 */
class BluetoothHidTransport(
    private val context: Context,
) : HidTransport {

    private val executor = java.util.concurrent.Executor { it.run() }

    private val _state = MutableStateFlow(TransportState())
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val _outputReports = MutableSharedFlow<OutputReport>(extraBufferCapacity = 32)
    override val outputReports: SharedFlow<OutputReport> = _outputReports.asSharedFlow()

    private var adapter: BluetoothAdapter? = null
    private var hidDevice: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private var registered = false

    @Volatile private var proxyResult: CompletableDeferred<Boolean>? = null
    @Volatile private var registerResult: CompletableDeferred<Boolean>? = null
    @Volatile private var connectResult: CompletableDeferred<Boolean>? = null

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, isRegistered: Boolean) {
            Log.i(TAG, "onAppStatusChanged registered=$isRegistered")
            registered = isRegistered
            if (isRegistered) {
                _state.update {
                    if (it.phase == TransportPhase.CONNECTED) it
                    else it.copy(phase = TransportPhase.REGISTERED)
                }
            } else {
                _state.update {
                    if (it.phase == TransportPhase.CONNECTED) it
                    else it.copy(phase = TransportPhase.IDLE)
                }
            }
            registerResult?.complete(isRegistered)
            registerResult = null
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, connectionState: Int) {
            Log.i(TAG, "onConnectionStateChanged state=$connectionState")
            when (connectionState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    _state.update {
                        it.copy(
                            phase = TransportPhase.CONNECTED,
                            hostAddress = device?.address,
                            hostName = safeName(device),
                        )
                    }
                    connectResult?.complete(true)
                    connectResult = null
                }
                BluetoothProfile.STATE_CONNECTING -> {
                    _state.update {
                        it.copy(phase = TransportPhase.CONNECTING, hostAddress = device?.address)
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _state.update {
                        it.copy(
                            phase = if (registered) TransportPhase.REGISTERED else TransportPhase.IDLE,
                        )
                    }
                    connectResult?.complete(false)
                    connectResult = null
                }
            }
        }

        override fun onInterruptData(device: BluetoothDevice?, reportId: Byte, data: ByteArray?) {
            if (data != null && data.isNotEmpty()) {
                Log.d(TAG, "onInterruptData id=$reportId size=${data.size}")
                _outputReports.tryEmit(OutputReport(reportId.toInt() and 0xFF, data))
            }
        }
    }

    private val proxyListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            if (profile == BluetoothProfile.HID_DEVICE && proxy is BluetoothHidDevice) {
                hidDevice = proxy
                _state.update { it.copy(phase = TransportPhase.REGISTERING) }
                proxyResult?.complete(true)
                proxyResult = null
            } else {
                _state.update { it.copy(phase = TransportPhase.FAILED, message = "HID device profile unavailable") }
                proxyResult?.complete(false)
                proxyResult = null
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            Log.w(TAG, "HID profile service disconnected")
            hidDevice = null
            registered = false
            _state.update { it.copy(phase = TransportPhase.FAILED, message = "Bluetooth HID service disconnected") }
        }
    }

    override suspend fun register(compiled: CompiledHid): Boolean {
        if (!hasConnectPermission()) {
            _state.update { it.copy(phase = TransportPhase.FAILED, message = "Missing BLUETOOTH_CONNECT permission") }
            return false
        }
        adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val a = adapter
        if (a == null || !a.isEnabled) {
            _state.update { it.copy(phase = TransportPhase.FAILED, message = "Bluetooth is off") }
            return false
        }

        _state.update { it.copy(phase = TransportPhase.OBTAINING_PROXY) }
        val deferred = CompletableDeferred<Boolean>()
        proxyResult = deferred

        val gotProxy = try {
            a.getProfileProxy(context, proxyListener, BluetoothProfile.HID_DEVICE)
        } catch (e: SecurityException) {
            Log.e(TAG, "getProfileProxy failed", e)
            false
        }

        if (!gotProxy) {
            proxyResult = null
            _state.update { it.copy(phase = TransportPhase.FAILED, message = "getProfileProxy failed") }
            return false
        }

        // Proxy connects asynchronously; registration happens once we have it.
        val proxyObtained = withTimeoutOrNull(PROXY_TIMEOUT_MS) { deferred.await() }
        if (proxyObtained != true || hidDevice == null) {
            proxyResult = null
            _state.update { it.copy(phase = TransportPhase.FAILED, message = "HID proxy unavailable") }
            return false
        }

        // Now actually register the app with the compiled identity.
        val registerDeferred = CompletableDeferred<Boolean>()
        registerResult = registerDeferred
        _state.update { it.copy(phase = TransportPhase.REGISTERING) }
        val sdp = BluetoothHidDeviceAppSdpSettings(
            compiled.deviceName,
            compiled.description,
            compiled.provider,
            compiled.subclassByte,
            compiled.descriptor,
        )
        val ok = try {
            hidDevice?.registerApp(sdp, null, null, executor, callback) ?: false
        } catch (e: SecurityException) {
            Log.e(TAG, "registerApp failed", e)
            false
        }
        if (!ok) {
            registerResult = null
            _state.update { it.copy(phase = TransportPhase.FAILED, message = "registerApp returned false") }
            return false
        }
        val result = withTimeoutOrNull(REGISTER_TIMEOUT_MS) { registerDeferred.await() } ?: false
        registerResult = null
        if (!result) {
            _state.update { it.copy(phase = TransportPhase.FAILED, message = "HID app registration failed/timed out") }
        }
        return result
    }

    override suspend fun connect(hostAddress: String): Boolean {
        if (!hasConnectPermission()) return false
        val a = adapter ?: return false
        val device = try {
            a.getRemoteDevice(hostAddress)
        } catch (e: IllegalArgumentException) {
            return false
        }
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            _state.update { it.copy(phase = TransportPhase.FAILED, message = "Host is not bonded/paired") }
            return false
        }
        val hid = hidDevice ?: return false
        val deferred = CompletableDeferred<Boolean>()
        connectResult = deferred
        _state.update { it.copy(phase = TransportPhase.CONNECTING, hostAddress = hostAddress, hostName = safeName(device)) }
        val initiated = try {
            hid.connect(device)
        } catch (e: SecurityException) {
            Log.e(TAG, "connect failed", e)
            false
        }
        if (!initiated) {
            connectResult = null
            return false
        }
        val connected = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { deferred.await() } ?: false
        if (!connected) {
            _state.update { it.copy(phase = if (registered) TransportPhase.REGISTERED else TransportPhase.IDLE) }
        }
        return connected
    }

    override suspend fun disconnect() {
        val hid = hidDevice ?: return
        val device = host ?: return
        try {
            hid.disconnect(device)
        } catch (e: SecurityException) {
            Log.w(TAG, "disconnect failed", e)
        }
        host = null
        _state.update { it.copy(phase = if (registered) TransportPhase.REGISTERED else TransportPhase.IDLE) }
    }

    override suspend fun unregister() {
        val hid = hidDevice
        val a = adapter
        registered = false
        if (hid != null) {
            try {
                hid.unregisterApp()
            } catch (e: SecurityException) {
                Log.w(TAG, "unregisterApp failed", e)
            }
        }
        if (hid != null && a != null) {
            try {
                a.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
            } catch (_: Exception) {
            }
        }
        hidDevice = null
        host = null
        _state.update { TransportState() }
    }

    override suspend fun sendReport(reportId: Int, payload: ByteArray): Boolean {
        val hid = hidDevice ?: return false
        val target = host ?: return false
        if (!hasConnectPermission()) return false
        return try {
            hid.sendReport(target, reportId, payload)
        } catch (e: SecurityException) {
            Log.w(TAG, "sendReport failed", e)
            false
        }
    }

    fun bondedHosts(): List<BluetoothDevice> {
        if (!hasConnectPermission()) return emptyList()
        val a = adapter
            ?: (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return emptyList()
        return try {
            a.bondedDevices.toList()
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    private fun hasConnectPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    private fun safeName(device: BluetoothDevice?): String? = try {
        device?.name
    } catch (e: SecurityException) {
        device?.address
    }

    companion object {
        private const val TAG = "MorphHidTransport"
        private const val PROXY_TIMEOUT_MS = 5_000L
        private const val REGISTER_TIMEOUT_MS = 10_000L
        private const val CONNECT_TIMEOUT_MS = 20_000L
    }
}