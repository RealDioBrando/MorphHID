package dev.morphhid.core.profile

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Root of a MorphHID profile: a declarative description of
 *  1) the HID device(s) to emulate (collections + report layout),
 *  2) the on-screen control UI,
 *  3) macros / sequences,
 *  4) agent access policy.
 */
@Serializable
data class Profile(
    val schemaVersion: Int = 1,
    val device: DeviceSpec,
    val ui: UiSpec? = null,
    val macros: Map<String, MacroSpec> = emptyMap(),
    val agent: AgentSpec? = null,
)

@Serializable
data class DeviceSpec(
    val name: String,
    val description: String = "",
    val provider: String = "MorphHID",
    val hid: HidSpec = HidSpec(),
)

@Serializable
data class HidSpec(
    /** SDP subclass hint: "keyboard" (0x40), "mouse" (0x80), "combo" (0xC0), "none" (0x00). */
    val subclass: String = "combo",
    val collections: List<HidCollectionSpec> = emptyList(),
)

@Serializable
sealed interface HidCollectionSpec {
    /** Report id 1..255. Assigned automatically (sequentially) when null. */
    val reportId: Int?

    @Serializable
    @SerialName("keyboard")
    data class Keyboard(
        override val reportId: Int? = null,
        val rolloverKeys: Int = 6,
        val includeModifiers: Boolean = true,
        val includeLeds: Boolean = true,
    ) : HidCollectionSpec

    @Serializable
    @SerialName("pointer")
    data class Pointer(
        override val reportId: Int? = null,
        val buttons: Int = 3,
        /** Subset of ["x", "y", "wheel"]. */
        val relativeAxes: List<String> = listOf("x", "y"),
        /** Subset of ["x", "y"] — absolute coordinate pointer (v2 quality). */
        val absoluteAxes: List<String> = emptyList(),
    ) : HidCollectionSpec

    @Serializable
    @SerialName("consumer")
    data class Consumer(
        override val reportId: Int? = null,
        /** Usage names, e.g. ["playPause", "volumeUp", "volumeDown", "mute"]. See UsageNames. */
        val usages: List<String> = emptyList(),
    ) : HidCollectionSpec

    @Serializable
    @SerialName("gamepad")
    data class Gamepad(
        override val reportId: Int? = null,
        val buttons: Int = 16,
        /** Subset of ["lx", "ly", "rx", "ry", "z", "rz"]. */
        val axes: List<String> = listOf("lx", "ly", "rx", "ry"),
        val hat: Boolean = true,
        val axisMin: Int = -127,
        val axisMax: Int = 127,
    ) : HidCollectionSpec
}