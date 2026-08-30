package dev.morphhid.ui.renderer

import dev.morphhid.core.profile.BindingSpec

/**
 * Bridge between the generic renderer and the app's control plane.
 * The app implements this against [dev.morphhid.core.control.ControlSession].
 */
interface WidgetHost {
    /** Momentary key/button press or release (e.g. "keyboard.a", "pointer.button1"). */
    fun onKey(keyId: String, pressed: Boolean)

    /** Fire a one-shot binding (tap, combo, macro, text, page switch). */
    fun onBinding(binding: BindingSpec)

    /** Continuous normalized axis update in [-1, 1]. */
    fun onAxis(controlId: String, value: Float)

    /** Set a boolean control (used by toggles). */
    fun onControlSet(controlId: String, on: Boolean)

    /** Relative pointer motion in density-independent pixels. */
    fun onPointerDelta(dx: Float, dy: Float)

    /** Switch the runtime UI to a screen. */
    fun onPage(screenId: String)
}