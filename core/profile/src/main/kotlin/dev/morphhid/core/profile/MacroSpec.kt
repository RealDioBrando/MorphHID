package dev.morphhid.core.profile

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class MacroPolicy { RESTART, IGNORE, QUEUE, PARALLEL }

@Serializable
data class MacroSpec(
    val steps: List<MacroStep> = emptyList(),
    val policy: MacroPolicy = MacroPolicy.RESTART,
    val description: String = "",
)

@Serializable
sealed interface MacroStep {
    @Serializable
    @SerialName("press")
    data class Press(val keys: List<String>) : MacroStep

    @Serializable
    @SerialName("release")
    data class Release(val keys: List<String>) : MacroStep

    @Serializable
    @SerialName("hold")
    data class Hold(val keys: List<String>, val durationMs: Long = 90) : MacroStep

    @Serializable
    @SerialName("tap")
    data class Tap(val key: String, val holdMs: Long = 15) : MacroStep

    @Serializable
    @SerialName("type")
    data class Type(val text: String, val keyDelayMs: Long = 45, val jitterMs: Long = 0) : MacroStep

    @Serializable
    @SerialName("delay")
    data class Delay(val ms: Long = 100, val jitterMs: Long = 0) : MacroStep

    @Serializable
    @SerialName("repeat")
    data class Repeat(val times: Int = 1, val intervalMs: Long = 50, val steps: List<MacroStep> = emptyList()) : MacroStep

    @Serializable
    @SerialName("run")
    data class Run(val macro: String) : MacroStep

    @Serializable
    @SerialName("set")
    data class Set(val control: String, val value: String) : MacroStep

    @Serializable
    @SerialName("haptic")
    data class Haptic(val ms: Long = 20) : MacroStep

    @Serializable
    @SerialName("page")
    data class Page(val screen: String) : MacroStep
}