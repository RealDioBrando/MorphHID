package dev.morphhid.core.hid

/**
 * Runtime encoder for HID input reports and decoder for host output reports.
 * Maintains the state of every control in a compiled profile; [flushDirty]
 * serializes only reports whose state changed and resets relative deltas.
 *
 * Not thread-safe by itself; the ControlSession serializes access.
 */
class ReportCodec(private val compiled: CompiledHid) {

    private val controlsById: Map<String, ControlDescriptor> =
        compiled.controls.associateBy { it.id }

    // Keyboard key-array state (usage codes in press order).
    private val pressedKeys = LinkedHashSet<Int>()
    private var modifierBits = 0

    // Bitmap state: reportId -> (bitOffset -> on). Used by buttons & consumer keys.
    private val bitState = HashMap<Int, MutableMap<Int, Boolean>>()

    // Absolute axis state: controlId -> raw value.
    private val axisValues = HashMap<String, Int>()

    // Relative accumulators: controlId -> pending delta (cleared on flush).
    private val relativeAccum = HashMap<String, Int>()

    // Hat state per report: 0..7 direction, >=8 neutral/null.
    private val hatValues = HashMap<Int, Int>()

    private val dirty = mutableSetOf<Int>()

    // ------------------------------------------------------------------ input

    fun press(controlId: String): Boolean {
        val c = controlsById[controlId] ?: return false
        return when (c.kind) {
            ControlKind.KEY -> {
                if (!pressedKeys.add(c.usage)) return false
                markDirty(c)
                true
            }
            ControlKind.MODIFIER -> {
                val bit = 1 shl (c.usage - MODIFIER_BASE)
                if (modifierBits and bit != 0) return false
                modifierBits = modifierBits or bit
                markDirty(c)
                true
            }
            ControlKind.BUTTON, ControlKind.CONSUMER -> {
                val m = bitState.getOrPut(c.reportId) { mutableMapOf() }
                if (m[c.bitOffset] == true) return false
                m[c.bitOffset] = true
                markDirty(c)
                true
            }
            else -> false
        }
    }

    fun release(controlId: String): Boolean {
        val c = controlsById[controlId] ?: return false
        return when (c.kind) {
            ControlKind.KEY -> {
                if (!pressedKeys.remove(c.usage)) return false
                markDirty(c)
                true
            }
            ControlKind.MODIFIER -> {
                val bit = 1 shl (c.usage - MODIFIER_BASE)
                if (modifierBits and bit == 0) return false
                modifierBits = modifierBits and bit.inv()
                markDirty(c)
                true
            }
            ControlKind.BUTTON, ControlKind.CONSUMER -> {
                val m = bitState[c.reportId] ?: return false
                if (m[c.bitOffset] != true) return false
                m[c.bitOffset] = false
                markDirty(c)
                true
            }
            else -> false
        }
    }

    fun isPressed(controlId: String): Boolean {
        val c = controlsById[controlId] ?: return false
        return when (c.kind) {
            ControlKind.KEY -> pressedKeys.contains(c.usage)
            ControlKind.MODIFIER -> modifierBits and (1 shl (c.usage - MODIFIER_BASE)) != 0
            ControlKind.BUTTON, ControlKind.CONSUMER ->
                bitState[c.reportId]?.get(c.bitOffset) == true
            else -> false
        }
    }

    fun setAxis(controlId: String, value: Int): Boolean {
        val c = controlsById[controlId] ?: return false
        if (c.kind != ControlKind.AXIS) return false
        val clamped = value.coerceIn(c.logicalMin, c.logicalMax)
        if (axisValues[controlId] == clamped) return false
        axisValues[controlId] = clamped
        markDirty(c)
        return true
    }

    /** Sets an axis from a normalized [-1, 1] value, scaled to logical range. */
    fun setAxisNormalized(controlId: String, normalized: Float): Boolean {
        val c = controlsById[controlId] ?: return false
        val n = normalized.coerceIn(-1f, 1f)
        val mid = (c.logicalMin + c.logicalMax) / 2.0
        val half = (c.logicalMax - c.logicalMin) / 2.0
        return setAxis(controlId, Math.round(mid + n * half).toInt())
    }

    fun addRelative(controlId: String, delta: Int): Boolean {
        val c = controlsById[controlId] ?: return false
        if (c.kind != ControlKind.REL_AXIS || delta == 0) return false
        relativeAccum[controlId] = (relativeAccum[controlId] ?: 0) + delta
        markDirty(c)
        return true
    }

    /** Hat value 0..7 (0=N, clockwise), 8 = neutral. */
    fun setHat(controlId: String, value: Int): Boolean {
        val c = controlsById[controlId] ?: return false
        if (c.kind != ControlKind.HAT) return false
        val v = value.coerceIn(0, 15)
        if (hatValues[c.reportId] == v) return false
        hatValues[c.reportId] = v
        markDirty(c)
        return true
    }

    // ----------------------------------------------------------------- output

    /** Decodes a host->device output report (e.g. keyboard LEDs). */
    fun decodeOutput(reportId: Int, payload: ByteArray): Map<String, Boolean> {
        val result = mutableMapOf<String, Boolean>()
        for (c in compiled.controls) {
            if (!c.isOutput || c.reportId != reportId) continue
            val byte = payload.getOrNull(c.bitOffset / 8) ?: continue
            val bit = (byte.toInt() shr (c.bitOffset % 8)) and 1
            result[c.id] = bit == 1
        }
        return result
    }

    // ------------------------------------------------------------------ flush

    /** Serializes changed reports as (reportId, payload-without-id) pairs. */
    fun flushDirty(): List<Pair<Int, ByteArray>> {
        if (dirty.isEmpty()) return emptyList()
        val out = mutableListOf<Pair<Int, ByteArray>>()
        for (reportId in dirty.sorted()) {
            val layout = compiled.reportLayout(reportId) ?: continue
            val payload = ByteArray(layout.inputBytes)
            when (layout.collectionType) {
                "keyboard" -> encodeKeyboard(payload, layout)
                "pointer" -> encodePointer(payload, reportId)
                "consumer" -> encodeBitmap(payload, reportId)
                "gamepad" -> encodeGamepad(payload, reportId)
            }
            out += reportId to payload
        }
        dirty.clear()
        relativeAccum.clear()
        return out
    }

    /** Clears every control to its neutral state and marks all reports dirty. */
    fun releaseAll() {
        pressedKeys.clear()
        modifierBits = 0
        bitState.values.forEach { it.keys.forEach { k -> it[k] = false } }
        hatValues.clear()
        // Axes return to center.
        for (c in compiled.controls) {
            if (c.kind == ControlKind.AXIS) {
                axisValues[c.id] = (c.logicalMin + c.logicalMax) / 2
            }
        }
        relativeAccum.clear()
        dirty += compiled.reports.map { it.reportId }
    }

    fun markAllDirty() {
        dirty += compiled.reports.map { it.reportId }
    }

    // --------------------------------------------------------------- encoders

    private fun encodeKeyboard(payload: ByteArray, layout: ReportLayout) {
        payload[0] = modifierBits.toByte()
        // payload[1] is the reserved/const byte and stays 0.
        val slots = layout.inputBytes - 2
        if (pressedKeys.size > slots) {
            // ErrorRollOver: more keys than slots.
            for (i in 0 until slots) payload[2 + i] = KeyboardUsage.ERROR_ROLLOVER.toByte()
        } else {
            val iter = pressedKeys.iterator()
            for (i in 0 until slots) {
                val usage = if (iter.hasNext()) iter.next() else 0
                payload[2 + i] = usage.toByte()
            }
        }
    }

    private fun encodePointer(payload: ByteArray, reportId: Int) {
        encodeBitmap(payload, reportId)
        for (c in controlsOf(reportId, ControlKind.REL_AXIS)) {
            val idx = c.bitOffset / 8
            val delta = (relativeAccum[c.id] ?: 0).coerceIn(-127, 127)
            payload[idx] = delta.toByte()
        }
        for (c in controlsOf(reportId, ControlKind.AXIS)) {
            val idx = c.bitOffset / 8
            payload[idx] = rawAxis(c).toByte()
        }
    }

    private fun encodeBitmap(payload: ByteArray, reportId: Int) {
        val m = bitState[reportId] ?: return
        for ((bitOffset, on) in m) {
            if (!on) continue
            val idx = bitOffset / 8
            if (idx < payload.size) {
                payload[idx] = (payload[idx].toInt() or (1 shl (bitOffset % 8))).toByte()
            }
        }
    }

    private fun encodeGamepad(payload: ByteArray, reportId: Int) {
        encodeBitmap(payload, reportId)
        val hatControl = compiled.controls.firstOrNull {
            it.reportId == reportId && it.kind == ControlKind.HAT
        }
        if (hatControl != null) {
            val idx = hatControl.bitOffset / 8
            val hat = (hatValues[reportId] ?: HAT_NEUTRAL) and 0x0F
            payload[idx] = (payload[idx].toInt() or hat).toByte()
        }
        for (c in controlsOf(reportId, ControlKind.AXIS)) {
            val idx = c.bitOffset / 8
            payload[idx] = rawAxis(c).toByte()
        }
    }

    private fun rawAxis(c: ControlDescriptor): Int =
        (axisValues[c.id] ?: (c.logicalMin + c.logicalMax) / 2)
            .coerceIn(c.logicalMin, c.logicalMax)

    private fun controlsOf(reportId: Int, kind: ControlKind): List<ControlDescriptor> =
        compiled.controls.filter { it.reportId == reportId && it.kind == kind }
            .sortedBy { it.bitOffset }

    private fun markDirty(c: ControlDescriptor) {
        dirty += c.reportId
    }

    companion object {
        private const val MODIFIER_BASE = 0xE0
        const val HAT_NEUTRAL = 8
    }
}