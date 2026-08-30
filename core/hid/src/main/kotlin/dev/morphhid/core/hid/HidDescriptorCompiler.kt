package dev.morphhid.core.hid

import dev.morphhid.core.profile.HidCollectionSpec
import dev.morphhid.core.profile.HidSpec
import dev.morphhid.core.profile.Profile

/**
 * Compiles a profile's HID spec into:
 *  - a binary HID report descriptor (HID 1.11 item stream),
 *  - the SDP subclass byte,
 *  - report layouts and control descriptors for the runtime codec.
 *
 * Pure Kotlin (no Android dependencies) so it is unit-testable on the JVM
 * and reusable by future tooling (CLI validator, host-side emulator).
 */
class HidDescriptorCompiler {

    fun compile(profile: Profile): CompiledHid {
        val hid = profile.device.hid
        if (hid.collections.isEmpty()) {
            throw IllegalArgumentException("profile declares no HID collections")
        }

        // --- assign report ids -------------------------------------------------
        val usedIds = mutableSetOf<Int>()
        var next = 1
        val assigned = mutableListOf<Pair<HidCollectionSpec, Int>>()
        for (c in hid.collections) {
            val id = c.reportId ?: next
            if (id < 1 || id > 255) {
                throw IllegalArgumentException("reportId must be 1..255, got $id")
            }
            if (!usedIds.add(id)) {
                throw IllegalArgumentException("duplicate reportId $id")
            }
            if (id >= next) next = id + 1
            assigned += c to id
        }

        val b = DescriptorBuilder()
        val reports = mutableListOf<ReportLayout>()
        val controls = mutableListOf<ControlDescriptor>()

        for ((c, reportId) in assigned) {
            when (c) {
                is HidCollectionSpec.Keyboard ->
                    compileKeyboard(b, c, reportId, reports, controls)
                is HidCollectionSpec.Pointer ->
                    compilePointer(b, c, reportId, reports, controls)
                is HidCollectionSpec.Consumer ->
                    compileConsumer(b, c, reportId, reports, controls)
                is HidCollectionSpec.Gamepad ->
                    compileGamepad(b, c, reportId, reports, controls)
            }
        }

        for (r in reports) {
            if (r.inputBytes > MAX_INPUT_REPORT_BYTES) {
                throw IllegalArgumentException(
                    "report ${r.reportId} ($r) exceeds ${MAX_INPUT_REPORT_BYTES}-byte HID interrupt frame"
                )
            }
        }

        return CompiledHid(
            deviceName = profile.device.name,
            description = profile.device.description,
            provider = profile.device.provider,
            subclassByte = subclassByteFor(hid),
            descriptor = b.toByteArray(),
            reports = reports,
            controls = controls,
        )
    }

    private fun subclassByteFor(hid: HidSpec): Byte = when (hid.subclass.lowercase()) {
        "keyboard" -> SUBCLASS_KEYBOARD
        "mouse", "pointer" -> SUBCLASS_MOUSE
        "combo" -> SUBCLASS_COMBO
        "none" -> 0
        else -> throw IllegalArgumentException("unknown subclass '${hid.subclass}'")
    }

    // ------------------------------------------------------------------ keyboard

    private fun compileKeyboard(
        b: DescriptorBuilder,
        c: HidCollectionSpec.Keyboard,
        reportId: Int,
        reports: MutableList<ReportLayout>,
        controls: MutableList<ControlDescriptor>,
    ) {
        val rollover = c.rolloverKeys.coerceIn(1, 32)

        b.global(ITEM_USAGE_PAGE, UsagePages.GENERIC_DESKTOP)
        b.local(ITEM_USAGE, GenericDesktopUsages.KEYBOARD)
        b.main(ITEM_COLLECTION, COLL_APPLICATION)

        b.global(ITEM_REPORT_ID, reportId)

        // Modifier byte (8 bits) or 8 bits of padding.
        if (c.includeModifiers) {
            b.global(ITEM_USAGE_PAGE, UsagePages.KEYBOARD)
            b.local(ITEM_USAGE_MIN, 0xE0)
            b.local(ITEM_USAGE_MAX, 0xE7)
            b.global(ITEM_LOGICAL_MIN, 0)
            b.global(ITEM_LOGICAL_MAX, 1)
            b.global(ITEM_REPORT_SIZE, 1)
            b.global(ITEM_REPORT_COUNT, 8)
            b.main(ITEM_INPUT, FLAGS_DATA_VAR_ABS)
        } else {
            b.global(ITEM_REPORT_SIZE, 8)
            b.global(ITEM_REPORT_COUNT, 1)
            b.main(ITEM_INPUT, FLAGS_CONST)
        }
        val modifiers = c.includeModifiers

        // Reserved byte.
        b.global(ITEM_REPORT_COUNT, 1)
        b.global(ITEM_REPORT_SIZE, 8)
        b.main(ITEM_INPUT, FLAGS_CONST)

        // Key array (boot-compatible: usages 0..101, N slots).
        b.global(ITEM_USAGE_PAGE, UsagePages.KEYBOARD)
        b.local(ITEM_USAGE_MIN, 0)
        b.local(ITEM_USAGE_MAX, 101)
        b.global(ITEM_LOGICAL_MIN, 0)
        b.global(ITEM_LOGICAL_MAX, 101)
        b.global(ITEM_REPORT_SIZE, 8)
        b.global(ITEM_REPORT_COUNT, rollover)
        b.main(ITEM_INPUT, FLAGS_DATA_ARRAY)

        val inputBytes = 1 + 1 + rollover // modifiers/reserved + keys
        var outputBytes = 0

        // LED output report: 3 bits + 5 bits padding.
        if (c.includeLeds) {
            b.global(ITEM_USAGE_PAGE, UsagePages.LED)
            b.local(ITEM_USAGE_MIN, 1)
            b.local(ITEM_USAGE_MAX, 3)
            b.global(ITEM_LOGICAL_MIN, 0)
            b.global(ITEM_LOGICAL_MAX, 1)
            b.global(ITEM_REPORT_SIZE, 1)
            b.global(ITEM_REPORT_COUNT, 3)
            b.main(ITEM_OUTPUT, FLAGS_DATA_VAR_ABS)
            b.global(ITEM_REPORT_COUNT, 5)
            b.main(ITEM_OUTPUT, FLAGS_CONST)
            outputBytes = 1
            controls += ControlDescriptor("led.numLock", ControlKind.LED, reportId, UsagePages.LED, 1, bitOffset = 0, bitSize = 1, isOutput = true)
            controls += ControlDescriptor("led.capsLock", ControlKind.LED, reportId, UsagePages.LED, 2, bitOffset = 1, bitSize = 1, isOutput = true)
            controls += ControlDescriptor("led.scrollLock", ControlKind.LED, reportId, UsagePages.LED, 3, bitOffset = 2, bitSize = 1, isOutput = true)
        }

        b.main(ITEM_END_COLLECTION, 0)

        reports += ReportLayout(reportId, "keyboard", inputBytes, outputBytes)

        // Register key/modifier controls.
        for ((name, usage) in KeyboardUsage.BY_NAME) {
            if (usage in KeyboardUsage.MODIFIER_USAGES) {
                if (modifiers) {
                    controls += ControlDescriptor(
                        "keyboard.$name", ControlKind.MODIFIER, reportId,
                        UsagePages.KEYBOARD, usage,
                        bitOffset = usage - 0xE0, bitSize = 1,
                    )
                }
            } else if (usage <= 101) {
                controls += ControlDescriptor(
                    "keyboard.$name", ControlKind.KEY, reportId,
                    UsagePages.KEYBOARD, usage,
                )
            }
        }
    }

    // ------------------------------------------------------------------- pointer

    private fun compilePointer(
        b: DescriptorBuilder,
        c: HidCollectionSpec.Pointer,
        reportId: Int,
        reports: MutableList<ReportLayout>,
        controls: MutableList<ControlDescriptor>,
    ) {
        val buttons = c.buttons.coerceIn(0, 32)
        val relAxes = c.relativeAxes.filter { it in setOf("x", "y", "wheel") }.distinct()
        val absAxes = c.absoluteAxes.filter { it in setOf("x", "y") }.distinct()

        b.global(ITEM_USAGE_PAGE, UsagePages.GENERIC_DESKTOP)
        b.local(ITEM_USAGE, GenericDesktopUsages.MOUSE)
        b.main(ITEM_COLLECTION, COLL_APPLICATION)
        b.local(ITEM_USAGE, GenericDesktopUsages.POINTER)
        b.main(ITEM_COLLECTION, COLL_PHYSICAL)

        b.global(ITEM_REPORT_ID, reportId)

        var bitCursor = 0
        if (buttons > 0) {
            b.global(ITEM_USAGE_PAGE, UsagePages.BUTTON)
            b.local(ITEM_USAGE_MIN, 1)
            b.local(ITEM_USAGE_MAX, buttons)
            b.global(ITEM_LOGICAL_MIN, 0)
            b.global(ITEM_LOGICAL_MAX, 1)
            b.global(ITEM_REPORT_SIZE, 1)
            b.global(ITEM_REPORT_COUNT, buttons)
            b.main(ITEM_INPUT, FLAGS_DATA_VAR_ABS)
            for (i in 1..buttons) {
                controls += ControlDescriptor(
                    "pointer.button$i", ControlKind.BUTTON, reportId,
                    UsagePages.BUTTON, i, bitOffset = i - 1, bitSize = 1,
                )
            }
            bitCursor += buttons
            val pad = (8 - (buttons % 8)) % 8
            if (pad > 0) {
                b.global(ITEM_REPORT_COUNT, pad)
                b.main(ITEM_INPUT, FLAGS_CONST)
                bitCursor += pad
            }
        }
        val buttonBytes = (bitCursor + 7) / 8

        if (relAxes.isNotEmpty() || absAxes.isNotEmpty()) {
            b.global(ITEM_USAGE_PAGE, UsagePages.GENERIC_DESKTOP)
            var relCount = 0
            for (axis in relAxes) {
                b.local(ITEM_USAGE, axisUsage(axis))
                controls += ControlDescriptor(
                    "pointer.$axis", ControlKind.REL_AXIS, reportId,
                    UsagePages.GENERIC_DESKTOP, axisUsage(axis),
                    bitOffset = (buttonBytes + relCount) * 8, bitSize = 8,
                    logicalMin = -127, logicalMax = 127, relative = true,
                )
                relCount++
            }
            if (relAxes.isNotEmpty()) {
                b.global(ITEM_LOGICAL_MIN, -127)
                b.global(ITEM_LOGICAL_MAX, 127)
                b.global(ITEM_REPORT_SIZE, 8)
                b.global(ITEM_REPORT_COUNT, relAxes.size)
                b.main(ITEM_INPUT, FLAGS_DATA_VAR_REL)
            }
            if (absAxes.isNotEmpty()) {
                for (axis in absAxes) {
                    b.local(ITEM_USAGE, axisUsage(axis))
                    controls += ControlDescriptor(
                        "pointer.$axis", ControlKind.AXIS, reportId,
                        UsagePages.GENERIC_DESKTOP, axisUsage(axis),
                        bitOffset = (buttonBytes + relCount) * 8, bitSize = 8,
                        logicalMin = -127, logicalMax = 127,
                    )
                    relCount++
                }
                b.global(ITEM_LOGICAL_MIN, -127)
                b.global(ITEM_LOGICAL_MAX, 127)
                b.global(ITEM_REPORT_SIZE, 8)
                b.global(ITEM_REPORT_COUNT, absAxes.size)
                b.main(ITEM_INPUT, FLAGS_DATA_VAR_ABS)
            }
        }

        b.main(ITEM_END_COLLECTION, 0)
        b.main(ITEM_END_COLLECTION, 0)

        val inputBytes = buttonBytes + relAxes.size + absAxes.size
        reports += ReportLayout(reportId, "pointer", inputBytes, 0)
    }

    // ------------------------------------------------------------------ consumer

    private fun compileConsumer(
        b: DescriptorBuilder,
        c: HidCollectionSpec.Consumer,
        reportId: Int,
        reports: MutableList<ReportLayout>,
        controls: MutableList<ControlDescriptor>,
    ) {
        val usages = c.usages.map { name ->
            consumerUsageFor(name)
        }
        if (usages.isEmpty()) {
            throw IllegalArgumentException("consumer collection (report $reportId) declares no usages")
        }
        val lo = usages.min()
        val hi = usages.max()
        val bits = hi - lo + 1

        b.global(ITEM_USAGE_PAGE, UsagePages.CONSUMER)
        b.local(ITEM_USAGE, 0x01) // Consumer Control
        b.main(ITEM_COLLECTION, COLL_APPLICATION)
        b.global(ITEM_REPORT_ID, reportId)
        b.local(ITEM_USAGE_MIN, lo)
        b.local(ITEM_USAGE_MAX, hi)
        b.global(ITEM_LOGICAL_MIN, 0)
        b.global(ITEM_LOGICAL_MAX, 1)
        b.global(ITEM_REPORT_SIZE, 1)
        b.global(ITEM_REPORT_COUNT, bits)
        b.main(ITEM_INPUT, FLAGS_DATA_VAR_ABS)
        val pad = (8 - (bits % 8)) % 8
        if (pad > 0) {
            b.global(ITEM_REPORT_COUNT, pad)
            b.main(ITEM_INPUT, FLAGS_CONST)
        }
        b.main(ITEM_END_COLLECTION, 0)

        for ((name, usage) in c.usages.zip(usages)) {
            controls += ControlDescriptor(
                "consumer.$name", ControlKind.CONSUMER, reportId,
                UsagePages.CONSUMER, usage, bitOffset = usage - lo, bitSize = 1,
            )
        }

        reports += ReportLayout(reportId, "consumer", (bits + 7) / 8, 0)
    }

    private fun consumerUsageFor(name: String): Int =
        ConsumerUsage.BY_NAME[name]
            ?: throw IllegalArgumentException("unknown consumer usage '$name'")

    // ------------------------------------------------------------------- gamepad

    private fun compileGamepad(
        b: DescriptorBuilder,
        c: HidCollectionSpec.Gamepad,
        reportId: Int,
        reports: MutableList<ReportLayout>,
        controls: MutableList<ControlDescriptor>,
    ) {
        val buttons = c.buttons.coerceIn(0, 64)
        val axes = c.axes.filter { GamepadAxisUsage.BY_NAME.containsKey(it) }.distinct()

        b.global(ITEM_USAGE_PAGE, UsagePages.GENERIC_DESKTOP)
        b.local(ITEM_USAGE, GenericDesktopUsages.GAMEPAD)
        b.main(ITEM_COLLECTION, COLL_APPLICATION)
        b.global(ITEM_REPORT_ID, reportId)

        var byteCursor = 0
        if (buttons > 0) {
            b.global(ITEM_USAGE_PAGE, UsagePages.BUTTON)
            b.local(ITEM_USAGE_MIN, 1)
            b.local(ITEM_USAGE_MAX, buttons)
            b.global(ITEM_LOGICAL_MIN, 0)
            b.global(ITEM_LOGICAL_MAX, 1)
            b.global(ITEM_REPORT_SIZE, 1)
            b.global(ITEM_REPORT_COUNT, buttons)
            b.main(ITEM_INPUT, FLAGS_DATA_VAR_ABS)
            for (i in 1..buttons) {
                controls += ControlDescriptor(
                    "gamepad.button$i", ControlKind.BUTTON, reportId,
                    UsagePages.BUTTON, i, bitOffset = i - 1, bitSize = 1,
                )
            }
            val pad = (8 - (buttons % 8)) % 8
            if (pad > 0) {
                b.global(ITEM_REPORT_COUNT, pad)
                b.main(ITEM_INPUT, FLAGS_CONST)
            }
            byteCursor = (buttons + 7) / 8
        }

        if (c.hat) {
            b.global(ITEM_USAGE_PAGE, UsagePages.GENERIC_DESKTOP)
            b.local(ITEM_USAGE, GenericDesktopUsages.HAT_SWITCH)
            b.global(ITEM_LOGICAL_MIN, 0)
            b.global(ITEM_LOGICAL_MAX, 7)
            b.global(ITEM_PHYSICAL_MIN, 0)
            b.global(ITEM_PHYSICAL_MAX, 315)
            b.global(ITEM_REPORT_SIZE, 4)
            b.global(ITEM_REPORT_COUNT, 1)
            b.main(ITEM_INPUT, FLAGS_DATA_VAR_ABS_NULL)
            b.global(ITEM_REPORT_SIZE, 4)
            b.global(ITEM_REPORT_COUNT, 1)
            b.main(ITEM_INPUT, FLAGS_CONST)
            controls += ControlDescriptor(
                "gamepad.hat", ControlKind.HAT, reportId,
                UsagePages.GENERIC_DESKTOP, GenericDesktopUsages.HAT_SWITCH,
                bitOffset = byteCursor * 8, bitSize = 4,
                logicalMin = 0, logicalMax = 7,
            )
            byteCursor += 1
        }

        if (axes.isNotEmpty()) {
            b.global(ITEM_USAGE_PAGE, UsagePages.GENERIC_DESKTOP)
            for (axis in axes) {
                b.local(ITEM_USAGE, GamepadAxisUsage.BY_NAME.getValue(axis))
            }
            b.global(ITEM_LOGICAL_MIN, c.axisMin)
            b.global(ITEM_LOGICAL_MAX, c.axisMax)
            b.global(ITEM_REPORT_SIZE, 8)
            b.global(ITEM_REPORT_COUNT, axes.size)
            b.main(ITEM_INPUT, FLAGS_DATA_VAR_ABS)
            for ((i, axis) in axes.withIndex()) {
                controls += ControlDescriptor(
                    "gamepad.$axis", ControlKind.AXIS, reportId,
                    UsagePages.GENERIC_DESKTOP, GamepadAxisUsage.BY_NAME.getValue(axis),
                    bitOffset = (byteCursor + i) * 8, bitSize = 8,
                    logicalMin = c.axisMin, logicalMax = c.axisMax,
                )
            }
            byteCursor += axes.size
        }

        b.main(ITEM_END_COLLECTION, 0)
        reports += ReportLayout(reportId, "gamepad", byteCursor, 0)
    }

    private fun axisUsage(name: String): Int = when (name) {
        "x" -> GenericDesktopUsages.X
        "y" -> GenericDesktopUsages.Y
        "wheel" -> GenericDesktopUsages.WHEEL
        else -> throw IllegalArgumentException("unknown pointer axis '$name'")
    }

    companion object {
        const val MAX_INPUT_REPORT_BYTES = 47

        const val SUBCLASS_KEYBOARD: Byte = 0x40
        const val SUBCLASS_MOUSE: Byte = -0x80
        const val SUBCLASS_COMBO: Byte = -0x40

        // Item tags (byte = tag<<4 | type<<2 | size).
        private const val ITEM_USAGE_PAGE = 0x00
        private const val ITEM_LOGICAL_MIN = 0x01
        private const val ITEM_LOGICAL_MAX = 0x02
        private const val ITEM_PHYSICAL_MIN = 0x03
        private const val ITEM_PHYSICAL_MAX = 0x04
        private const val ITEM_REPORT_SIZE = 0x07
        private const val ITEM_REPORT_ID = 0x08
        private const val ITEM_REPORT_COUNT = 0x09
        private const val ITEM_USAGE = 0x00
        private const val ITEM_USAGE_MIN = 0x01
        private const val ITEM_USAGE_MAX = 0x02
        private const val ITEM_COLLECTION = 0x0A
        private const val ITEM_END_COLLECTION = 0x0C
        private const val ITEM_INPUT = 0x08
        private const val ITEM_OUTPUT = 0x09

        private const val COLL_APPLICATION = 0x01
        private const val COLL_PHYSICAL = 0x00

        private const val FLAGS_DATA_VAR_ABS = 0x02
        private const val FLAGS_DATA_VAR_REL = 0x06
        private const val FLAGS_DATA_VAR_ABS_NULL = 0x42
        private const val FLAGS_DATA_ARRAY = 0x00
        private const val FLAGS_CONST = 0x03
    }
}

/**
 * Streams HID short items. Values are encoded little-endian; size is chosen
 * from the value so that both signed logical values and unsigned usages are
 * represented unambiguously.
 */
internal class DescriptorBuilder {
    private val bytes = mutableListOf<Int>()

    fun global(tag: Int, value: Int) = item(tag, TYPE_GLOBAL, value)
    fun local(tag: Int, value: Int) = item(tag, TYPE_LOCAL, value)
    fun main(tag: Int, value: Int) {
        if (tag == 0x0C) { // End Collection has no data.
            bytes.add(0xC0)
        } else {
            item(tag, TYPE_MAIN, value)
        }
    }

    private fun item(tag: Int, type: Int, value: Int) {
        val size = sizeFor(value)
        bytes.add((tag shl 4) or (type shl 2) or size)
        var v = value
        repeat(when (size) { 1 -> 1; 2 -> 2; else -> 4 }) {
            bytes.add(v and 0xFF)
            v = v shr 8
        }
    }

    private fun sizeFor(value: Int): Int = when {
        value in -128..127 -> 1
        value in 0..65535 -> 2
        else -> 4
    }

    fun toByteArray(): ByteArray = bytes.map { it.toByte() }.toByteArray()

    companion object {
        private const val TYPE_MAIN = 0
        private const val TYPE_GLOBAL = 1
        private const val TYPE_LOCAL = 2
    }
}