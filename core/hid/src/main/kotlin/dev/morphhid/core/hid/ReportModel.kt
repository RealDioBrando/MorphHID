package dev.morphhid.core.hid

import java.security.MessageDigest

enum class ControlKind { KEY, MODIFIER, BUTTON, CONSUMER, AXIS, REL_AXIS, HAT, LED }

/**
 * A single addressable control inside a compiled profile, e.g.
 * "keyboard.a" (key-array slot semantics), "keyboard.leftCtrl" (modifier bit),
 * "consumer.mute" (consumer bitmap bit), "pointer.x" (relative axis),
 * "gamepad.lx" (absolute axis), "keyboard.capsLock" (output LED bit).
 */
data class ControlDescriptor(
    val id: String,
    val kind: ControlKind,
    val reportId: Int,
    val usagePage: Int,
    val usage: Int,
    /** Bit offset inside the input report payload (excluding report id). */
    val bitOffset: Int = 0,
    val bitSize: Int = 1,
    val logicalMin: Int = 0,
    val logicalMax: Int = 1,
    val relative: Boolean = false,
    /** True for host->device reports (LEDs). */
    val isOutput: Boolean = false,
)

data class ReportLayout(
    val reportId: Int,
    val collectionType: String,
    val inputBytes: Int,
    val outputBytes: Int,
)

/** Result of compiling a profile's device spec into HID-level artifacts. */
class CompiledHid(
    val deviceName: String,
    val description: String,
    val provider: String,
    /** SDP "Device Subclass" byte: 0x40 keyboard, 0x80 pointing, 0xC0 combo, 0x00 other. */
    val subclassByte: Byte,
    val descriptor: ByteArray,
    val reports: List<ReportLayout>,
    val controls: List<ControlDescriptor>,
) {
    val fingerprint: String by lazy {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(byteArrayOf(subclassByte))
        md.update(descriptor)
        md.digest().joinToString("") { "%02x".format(it) }
    }

    private val controlsById: Map<String, ControlDescriptor> =
        controls.associateBy { it.id }

    fun findControl(id: String): ControlDescriptor? = controlsById[id]

    fun reportLayout(reportId: Int): ReportLayout? =
        reports.firstOrNull { it.reportId == reportId }

    override fun equals(other: Any?): Boolean = other is CompiledHid && other.fingerprint == fingerprint
    override fun hashCode(): Int = fingerprint.hashCode()
}