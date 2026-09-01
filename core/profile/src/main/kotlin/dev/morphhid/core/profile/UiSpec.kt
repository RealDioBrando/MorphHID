package dev.morphhid.core.profile

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UiSpec(
    val theme: ThemeSpec? = null,
    val screens: List<ScreenSpec> = emptyList(),
)

@Serializable
data class ThemeSpec(
    val dark: Boolean = true,
    /** "#RRGGBB" */
    val primaryColor: String? = null,
    val accentColor: String? = null,
    val backgroundColor: String? = null,
)

@Serializable
data class ScreenSpec(
    val id: String,
    val title: String = "",
    val layout: LayoutSpec = LayoutSpec.Grid(columns = 3),
    val widgets: List<WidgetSpec> = emptyList(),
)

@Serializable
sealed interface LayoutSpec {
    @Serializable
    @SerialName("grid")
    data class Grid(val columns: Int = 3) : LayoutSpec

    @Serializable
    @SerialName("rows")
    data class Rows(val rows: Int = 4) : LayoutSpec
}

@Serializable
sealed interface WidgetSpec {
    val id: String
    val label: String

    @Serializable
    @SerialName("button")
    data class Button(
        override val id: String,
        override val label: String = "",
        val onTap: BindingSpec? = null,
        val onDoubleTap: BindingSpec? = null,
        val onLongPress: BindingSpec? = null,
        /** If set, the key is held down while the button is pressed (momentary). */
        val momentaryKey: String? = null,
        val holdThresholdMs: Int = 450,
        val doubleTapWindowMs: Int = 280,
    ) : WidgetSpec

    @Serializable
    @SerialName("toggle")
    data class Toggle(
        override val id: String,
        override val label: String = "",
        val control: String? = null,
        val onTurnOn: BindingSpec? = null,
        val onTurnOff: BindingSpec? = null,
        val defaultOn: Boolean = false,
    ) : WidgetSpec

    @Serializable
    @SerialName("joystick")
    data class Joystick(
        override val id: String,
        override val label: String = "",
        val xAxis: String? = "x",
        val yAxis: String? = "y",
        val deadzone: Float = 0.12f,
        val springReturn: Boolean = true,
    ) : WidgetSpec

    @Serializable
    @SerialName("dpad")
    data class Dpad(
        override val id: String,
        override val label: String = "",
        val up: BindingSpec? = null,
        val down: BindingSpec? = null,
        val left: BindingSpec? = null,
        val right: BindingSpec? = null,
        val diagonals: Boolean = false,
    ) : WidgetSpec

    @Serializable
    @SerialName("slider")
    data class Slider(
        override val id: String,
        override val label: String = "",
        val control: String = "",
        val vertical: Boolean = true,
    ) : WidgetSpec

    @Serializable
    @SerialName("pointerPad")
    data class PointerPad(
        override val id: String,
        override val label: String = "",
        val sensitivity: Float = 1.0f,
        /** Tap on the pad = click this pointer button (1-based). Null disables tap-click. */
        val tapButton: Int? = 1,
        val scrollStrip: Boolean = false,
        /** Span the full screen width and use a wider laptop-touchpad aspect ratio. */
        val wide: Boolean = false,
    ) : WidgetSpec

    @Serializable
    @SerialName("trackPoint")
    data class TrackPoint(
        override val id: String,
        override val label: String = "",
        val sensitivity: Float = 1.0f,
        /** Tap on the knob = click this pointer button (1-based). Null disables tap-click. */
        val tapButton: Int? = 1,
    ) : WidgetSpec

    @Serializable
    @SerialName("keyGrid")
    data class KeyGrid(
        override val id: String,
        override val label: String = "",
        val rows: List<KeyRow> = emptyList(),
    ) : WidgetSpec

    @Serializable
    @SerialName("led")
    data class Led(
        override val id: String,
        override val label: String = "",
        /** capsLock | numLock | scrollLock */
        val led: String = "capsLock",
    ) : WidgetSpec

    @Serializable
    @SerialName("label")
    data class Label(
        override val id: String,
        override val label: String = "",
        val text: String = "",
    ) : WidgetSpec
}

@Serializable
data class KeyRow(
    val keys: List<String> = emptyList(),
)

@Serializable
sealed interface BindingSpec {
    @Serializable
    @SerialName("key")
    data class Key(val key: String) : BindingSpec

    @Serializable
    @SerialName("combo")
    data class Combo(val keys: List<String>) : BindingSpec

    @Serializable
    @SerialName("macro")
    data class Macro(val macro: String) : BindingSpec

    @Serializable
    @SerialName("text")
    data class Text(val text: String, val keyDelayMs: Int = 45, val jitterMs: Int = 0) : BindingSpec

    @Serializable
    @SerialName("page")
    data class Page(val screen: String) : BindingSpec
}
