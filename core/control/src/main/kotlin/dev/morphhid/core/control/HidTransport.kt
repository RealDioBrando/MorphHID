package dev.morphhid.core.control

import dev.morphhid.core.hid.CompiledHid
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class TransportPhase {
    IDLE, OBTAINING_PROXY, REGISTERING, REGISTERED,
    CONNECTING, CONNECTED, DISCONNECTING, FAILED,
}

data class TransportState(
    val phase: TransportPhase = TransportPhase.IDLE,
    val hostAddress: String? = null,
    val hostName: String? = null,
    val message: String? = null,
) {
    val isActive: Boolean
        get() = phase == TransportPhase.CONNECTED || phase == TransportPhase.CONNECTING ||
            phase == TransportPhase.REGISTERED
}

data class OutputReport(val reportId: Int, val payload: ByteArray)

/**
 * Transport-agnostic HID link. Implemented by [dev.morphhid.app.bluetooth.BluetoothHidTransport]
 * on-device and by fakes in tests. Payloads exclude the report-id byte; the
 * transport framing decides how the id travels on the wire.
 */
interface HidTransport {
    val state: StateFlow<TransportState>
    val outputReports: SharedFlow<OutputReport>

    /** Registers the app as an HID device with the compiled identity/descriptor. */
    suspend fun register(compiled: CompiledHid): Boolean

    /** Connects to a bonded host by MAC address. */
    suspend fun connect(hostAddress: String): Boolean

    suspend fun disconnect()

    suspend fun unregister()

    suspend fun sendReport(reportId: Int, payload: ByteArray): Boolean
}