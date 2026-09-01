package dev.morphhid.app.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [HidTransport] backed by the platform HID-device profile
 * (android.bluetooth.BluetoothHidDevice, API 28+).
 *
 * Lifecycle rules learned from on-device testing (see docs/NOTES.md):
 *  - The profile proxy is obtained ONCE and kept for the app's lifetime;
 *    closing/re-requesting it during a profile swap is racy and leaves the
 *    stack unable to register or connect again.
 *  - Swapping the registered app must: disconnect -> unregister -> settle
 *    -> register. Registering while an app is still registered fails.
 *  - Connection callbacks are matched against the connect/disconnect target
 *    so stale events from a previous host cannot fail a new connection.
 */
class BluetoothHidTransport(
    private val context: Context,
) : HidTransport {

    private val executor = java.util.concurrent.Executor { it.run() }

    /** Auto-connects to a host immediately after it finishes bonding. */
    private val autoConnectScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var bondReceiver: BroadcastReceiver? = null

    private val _state = MutableStateFlow(TransportState())
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val _outputReports = MutableSharedFlow<OutputReport>(extraBufferCapacity = 32)
    override val outputReports: SharedFlow<OutputReport> = _outputReports.asSharedFlow()

    private var adapter: BluetoothAdapter? = null
    private var hidDevice: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private var registered = false

    /** Currently registered profile, used to answer host GET_REPORT requests. */
    @Volatile private var activeCompiledHid: CompiledHid? = null

    @Volatile private var proxyResult: CompletableDeferred<Boolean>? = null
    @Volatile private var registerResult: CompletableDeferred<Boolean>? = null
    @Volatile private var connectResult: CompletableDeferred<Boolean>? = null
    @Volatile private var connectTarget: BluetoothDevice? = null
    @Volatile private var disconnectResult: CompletableDeferred<Boolean>? = null
    @Volatile private var disconnectTarget: BluetoothDevice? = null

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
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, connectionState: Int) {
            Log.i(TAG, "onConnectionStateChanged device=${device?.address} state=$connectionState")
            when (connectionState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    // Hosts (notably Windows) connect TO the phone after the
                    // user triggers "Add Bluetooth device" while a profile is
                    // activated. Accept any bonded host automatically; the
                    // connect() flow below completes its own target.
                    val autoAccept = device != null &&
                        device.bondState == BluetoothDevice.BOND_BONDED
                    host = device
                    _state.update {
                        it.copy(
                            phase = TransportPhase.CONNECTED,
                            hostAddress = device?.address,
                            hostName = safeName(device),
                            message = if (autoAccept && connectTarget == null) {
                                "Host connected"
                            } else null,
                        )
                    }
                    if (connectTarget != null && device?.address == connectTarget?.address) {
                        connectResult?.complete(true)
                    }
                }

                BluetoothProfile.STATE_CONNECTING -> {
                    _state.update {
                        it.copy(phase = TransportPhase.CONNECTING, hostAddress = device?.address)
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (disconnectTarget != null && device?.address == disconnectTarget?.address) {
                        disconnectResult?.complete(true)
                    }
                    // A DISCONNECTED for the connect target means the attempt failed.
                    if (connectTarget != null && device?.address == connectTarget?.address) {
                        connectResult?.complete(false)
                    }
                    // Stale events for a previous host must not clear a new connection.
                    val currentHost = host
                    if (device == null || currentHost == null || device.address == currentHost.address) {
                        host = null
                        _state.update {
                            it.copy(
                                phase = if (registered) TransportPhase.REGISTERED else TransportPhase.IDLE,
                                hostAddress = null,
                                hostName = null,
                            )
                        }
                    }
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
            Log.i(
                TAG,
                "onGetReport device=${device?.address} type=${type.toInt() and 0xff} " +
                    "id=${id.toInt() and 0xff} bufferSize=$bufferSize",
            )
            handleGetReport(device, type, id, bufferSize)
        }

        override fun onSetReport(device: BluetoothDevice?, reportType: Byte, reportId: Byte, data: ByteArray?) {
            Log.i(
                TAG,
                "onSetReport device=${device?.address} type=${reportType.toInt() and 0xff} " +
                    "id=${reportId.toInt() and 0xff} size=${data?.size}",
            )
            if (data != null && data.isNotEmpty()) {
                _outputReports.tryEmit(OutputReport(reportId.toInt() and 0xff, data))
            }
        }

        override fun onSetProtocol(device: BluetoothDevice?, protocol: Byte) {
            Log.i(
                TAG,
                "onSetProtocol device=${device?.address} protocol=${protocol.toInt() and 0xff}",
            )
        }

        override fun onVirtualCableUnplug(device: BluetoothDevice?) {
            Log.w(TAG, "onVirtualCableUnplug device=${device?.address}")
            if (device == null || host?.address == device.address) {
                host = null
                _state.update {
                    it.copy(
                        phase = if (registered) TransportPhase.REGISTERED else TransportPhase.IDLE,
                        hostAddress = null,
                        hostName = null,
                    )
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
                proxyResult?.complete(true)
            } else {
                _state.update { it.copy(phase = TransportPhase.FAILED, message = "HID device profile unavailable") }
                proxyResult?.complete(false)
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

        // Obtain the proxy once and keep it for the app's lifetime.
        if (hidDevice == null) {
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
            val proxyOk = withTimeoutOrNull(PROXY_TIMEOUT_MS) { deferred.await() } == true
            proxyResult = null
            if (!proxyOk || hidDevice == null) {
                _state.update { it.copy(phase = TransportPhase.FAILED, message = "HID proxy unavailable") }
                return false
            }
        }

        // Swap flow: if an app is registered, unregister and let the stack settle.
        if (registered) {
            if (!unregisterAppAndWait()) {
                // Best effort: proceed, registerApp will fail if still registered.
                delay(SETTLE_MS)
            } else {
                delay(SETTLE_MS)
            }
        }

        activeCompiledHid = compiled
        val registerDeferred = CompletableDeferred<Boolean>()
        registerResult = registerDeferred
        _state.update { it.copy(phase = TransportPhase.REGISTERING, message = null) }
        val sdp = BluetoothHidDeviceAppSdpSettings(
            compiled.deviceName,
            compiled.description,
            compiled.provider,
            compiled.subclassByte,
            compiled.descriptor,
        )
        // Match the known-working Windows-compatible Android HID-device path.
        // Both QoS arguments are intentionally null; an outgoing QoS flow spec
        // caused Windows to leave the HID L2CAP channel pending.
        val ok = try {
            hidDevice?.registerApp(sdp, null, null, executor, callback) ?: false
        } catch (e: SecurityException) {
            Log.e(TAG, "registerApp failed", e)
            false
        }
        if (!ok) {
            activeCompiledHid = null
            registerResult = null
            _state.update { it.copy(phase = TransportPhase.FAILED, message = "registerApp returned false") }
            return false
        }
        val result = withTimeoutOrNull(REGISTER_TIMEOUT_MS) { registerDeferred.await() } ?: false
        registerResult = null
        if (!result) {
            activeCompiledHid = null
            _state.update { it.copy(phase = TransportPhase.FAILED, message = "HID app registration failed/timed out") }
            return false
        }

        registerBondReceiver()
        return true
    }

    override suspend fun connect(hostAddress: String): Boolean {
        if (!hasConnectPermission()) return false
        val a = adapter
            ?: (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return false
        val device = try {
            a.getRemoteDevice(hostAddress)
        } catch (e: IllegalArgumentException) {
            return false
        }
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            _state.update { it.copy(phase = TransportPhase.FAILED, message = "Host is not bonded/paired") }
            return false
        }
        val hid = hidDevice
        if (hid == null || !registered) {
            _state.update { it.copy(phase = TransportPhase.FAILED, message = "No active HID profile — activate a profile first") }
            return false
        }

        // Idempotent: already connected to this host.
        if (_state.value.phase == TransportPhase.CONNECTED && host?.address == hostAddress) {
            return true
        }

        // If connected to a different host, disconnect cleanly and settle first.
        val current = host
        if (current != null && current.address != hostAddress) {
            disconnectAndWait(current)
            delay(SETTLE_MS)
        }

        val deferred = CompletableDeferred<Boolean>()
        connectResult = deferred
        connectTarget = device
        _state.update {
            it.copy(phase = TransportPhase.CONNECTING, hostAddress = hostAddress, hostName = safeName(device))
        }
        val initiated = try {
            hid.connect(device)
        } catch (e: SecurityException) {
            Log.e(TAG, "connect failed", e)
            false
        }
        if (!initiated) {
            connectResult = null
            connectTarget = null
            _state.update { it.copy(phase = if (registered) TransportPhase.REGISTERED else TransportPhase.IDLE) }
            return false
        }
        val connected = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { deferred.await() } ?: false
        connectResult = null
        connectTarget = null
        if (!connected) {
            _state.update { it.copy(phase = if (registered) TransportPhase.REGISTERED else TransportPhase.IDLE) }
        }
        return connected
    }

    override suspend fun disconnect() {
        val device = host ?: return
        disconnectAndWait(device)
    }

    override suspend fun unregister() {
        val currentHost = host
        if (currentHost != null) {
            disconnectAndWait(currentHost)
        }
        if (registered) {
            unregisterAppAndWait()
        }
        registered = false
        host = null
        activeCompiledHid = null
        unregisterBondReceiver()
        _state.update { TransportState() }
        // The profile proxy stays bound and is reused by the next register().
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

    /**
     * Removes the bond on the PHONE side. Necessary when a host has cached
     * an old SDP/HID record for this phone: the pairing must be removed on
     * BOTH sides before a fresh "Add device" on Windows/macOS re-runs
     * service discovery and picks up the registered HID app.
     *
     * Uses the hidden BluetoothDevice.removeBond() API via reflection —
     * widely used by BT utility apps; wrapped in try/catch for OEM quirks.
     */
    fun unpairHost(address: String): Boolean {
        if (!hasConnectPermission()) return false
        val a = adapter
            ?: (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return false
        val device = try {
            a.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            return false
        }
        return try {
            val method = device.javaClass.getMethod("removeBond")
            (method.invoke(device) as? Boolean) ?: false
        } catch (e: Exception) {
            Log.w(TAG, "removeBond failed for $address", e)
            false
        }
    }

    /**
     * Returns true if the phone is currently in Bluetooth discoverable
     * (scan) mode — i.e. other devices can find it during their "Add device"
     * flow. This is essential for Windows/macOS hosts that initiate the
     * connection TO the phone.
     */
    fun isDiscoverable(): Boolean {
        if (!hasConnectPermission()) return false
        val a = adapter
            ?: (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return false
        return try {
            a.scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE
        } catch (e: SecurityException) {
            false
        }
    }

    /**
     * Silently makes the phone Bluetooth-discoverable for [durationSeconds]
     * (max 300 on most devices) using the hidden setScanMode API.
     *
     * This is the KEY step for Windows/macOS: the host must discover the
     * phone (which has the HID SDP record registered) during "Add device".
     * Without discoverability, Windows either doesn't find the phone at
     * all, or finds it from a cached bond but without the HID service.
     *
     * Returns true on success; if reflection fails the caller should
     * fall back to the system [ACTION_REQUEST_DISCOVERABLE] intent.
     */
    fun setDiscoverable(durationSeconds: Int = 120): Boolean {
        if (!hasConnectPermission()) return false
        val a = adapter
            ?: (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return false
        return try {
            val dur = durationSeconds.coerceIn(1, 300)
            val method = BluetoothAdapter::class.java.getMethod(
                "setScanMode",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            val result = method.invoke(a, BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE, dur)
            // setScanMode returns true on success
            val ok = (result as? Boolean) ?: false
            if (ok) {
                Log.i(TAG, "setScanMode(DISCOVERABLE, ${dur}s) succeeded")
            } else {
                Log.w(TAG, "setScanMode returned false")
            }
            ok
        } catch (e: Exception) {
            Log.w(TAG, "setScanMode reflection failed — use intent fallback", e)
            false
        }
    }

    /**
     * Cancels discoverability (reverts to connectable-only mode).
     */
    fun cancelDiscoverable(): Boolean {
        if (!hasConnectPermission()) return false
        val a = adapter
            ?: (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return false
        return try {
            val method = BluetoothAdapter::class.java.getMethod(
                "setScanMode",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            method.invoke(a, BluetoothAdapter.SCAN_MODE_CONNECTABLE, 0) as? Boolean ?: false
        } catch (e: Exception) {
            false
        }
    }

    // ---------------------------------------------------------------- private

    /** Conservative GET_REPORT responder. */
    private fun handleGetReport(
        device: BluetoothDevice?,
        type: Byte,
        id: Byte,
        bufferSize: Int,
    ) {
        val hid = hidDevice ?: return
        if (device == null) return
        val compiled = activeCompiledHid
        val reportId = id.toInt() and 0xff
        val report = compiled?.reportLayout(reportId)
        val size = when (type) {
            BluetoothHidDevice.REPORT_TYPE_INPUT -> report?.inputBytes
            BluetoothHidDevice.REPORT_TYPE_OUTPUT -> report?.outputBytes
            else -> null
        }

        if (report == null || size == null || size <= 0) {
            val error = if (type == BluetoothHidDevice.REPORT_TYPE_FEATURE) {
                BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ
            } else {
                BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID
            }
            reportErrorSafely(device, error)
            return
        }
        if (bufferSize > 0 && bufferSize < size) {
            reportErrorSafely(device, BluetoothHidDevice.ERROR_RSP_INVALID_PARAM)
            return
        }

        val payload = ByteArray(size)
        val replied = try {
            hid.replyReport(device, type, id, payload)
        } catch (e: SecurityException) {
            Log.w(TAG, "replyReport failed", e)
            false
        }
        Log.i(TAG, "GET_REPORT reply id=$reportId size=$size ok=$replied")
    }

    private fun reportErrorSafely(device: BluetoothDevice, error: Byte) {
        try {
            hidDevice?.reportError(device, error)
        } catch (e: SecurityException) {
            Log.w(TAG, "reportError failed", e)
        }
    }

    /**
     * Windows hosts pair successfully but often do not initiate the HID
     * L2CAP connection themselves. Once a host finishes bonding while a
     * MorphHID profile is active, immediately call connect() from the phone.
     */
    private fun registerBondReceiver() {
        if (bondReceiver != null) return

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                val device = try {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                } catch (e: Exception) {
                    null
                } ?: return
                val state = intent.getIntExtra(
                    BluetoothDevice.EXTRA_BOND_STATE,
                    BluetoothDevice.ERROR,
                )
                if (state != BluetoothDevice.BOND_BONDED || !registered || host != null) return

                autoConnectScope.launch {
                    delay(AUTO_CONNECT_DELAY_MS)
                    if (registered && host == null) {
                        Log.i(TAG, "Auto-connecting to newly bonded host ${device.address}")
                        connect(device.address)
                    }
                }
            }
        }

        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )
        bondReceiver = receiver
    }

    private fun unregisterBondReceiver() {
        bondReceiver?.let { receiver ->
            try {
                context.unregisterReceiver(receiver)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister bond receiver", e)
            }
        }
        bondReceiver = null
    }

    /** Unregisters the app and waits for onAppStatusChanged(false). */
    private suspend fun unregisterAppAndWait(): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        registerResult = deferred
        return try {
            hidDevice?.unregisterApp()
            val done = withTimeoutOrNull(UNREGISTER_TIMEOUT_MS) { deferred.await() }
            done != null
        } catch (e: SecurityException) {
            Log.w(TAG, "unregisterApp failed", e)
            false
        } finally {
            registerResult = null
        }
    }

    /** Disconnects a specific device and waits for the state callback. */
    private suspend fun disconnectAndWait(device: BluetoothDevice) {
        val hid = hidDevice ?: return
        val deferred = CompletableDeferred<Boolean>()
        disconnectResult = deferred
        disconnectTarget = device
        try {
            hid.disconnect(device)
        } catch (e: SecurityException) {
            Log.w(TAG, "disconnect failed", e)
        }
        withTimeoutOrNull(DISCONNECT_TIMEOUT_MS) { deferred.await() }
        disconnectResult = null
        disconnectTarget = null
        // Fallback if no callback arrived.
        if (host == null || host?.address == device.address) {
            host = null
            _state.update {
                it.copy(
                    phase = if (registered) TransportPhase.REGISTERED else TransportPhase.IDLE,
                    hostAddress = null,
                    hostName = null,
                )
            }
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
        private const val UNREGISTER_TIMEOUT_MS = 5_000L
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val DISCONNECT_TIMEOUT_MS = 5_000L
        private const val SETTLE_MS = 500L
        // Give Windows time to finish pairing/SDP enumeration and install its
        // HID driver before the phone initiates the HID L2CAP connection.
        private const val AUTO_CONNECT_DELAY_MS = 3_000L
    }
}