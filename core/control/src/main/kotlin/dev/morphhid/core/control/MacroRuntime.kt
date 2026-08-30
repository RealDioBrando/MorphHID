package dev.morphhid.core.control

import dev.morphhid.core.hid.TextLayout
import dev.morphhid.core.profile.MacroStep
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Interprets macro steps against a host interface. Timing-sensitive by
 * design: every key event is a separate report, with configurable hold and
 * gap durations plus optional jitter for human-like pacing.
 *
 * Stuck-key protection: any key this run pressed is tracked and released in
 * a `finally` block, including on cancellation (emergency stop).
 */
class MacroRuntime(
    private val host: Host,
    private val random: Random = Random.Default,
) {
    interface Host {
        suspend fun pressKey(keyId: String)
        suspend fun releaseKey(keyId: String)
        suspend fun setControl(controlId: String, value: String)
        suspend fun page(screenId: String)
        suspend fun haptic(ms: Long)
        suspend fun delay(ms: Long)
    }

    suspend fun execute(steps: List<MacroStep>) {
        val pressed = mutableSetOf<String>()
        try {
            executeSteps(steps, pressed, depth = 0)
        } finally {
            if (pressed.isNotEmpty()) {
                // Release in reverse order of pressing. NonCancellable so the
                // releases survive emergency-stop/cancellation of the run.
                withContext(NonCancellable) {
                    for (key in pressed.toList().asReversed()) {
                        try {
                            host.releaseKey(key)
                        } catch (_: Exception) {
                            // Best-effort release during cancellation.
                        }
                    }
                }
            }
        }
    }

    private suspend fun executeSteps(steps: List<MacroStep>, pressed: MutableSet<String>, depth: Int) {
        if (depth > MAX_DEPTH) throw IllegalArgumentException("macro nesting exceeds $MAX_DEPTH levels")
        for (step in steps) {
            when (step) {
                is MacroStep.Press -> for (key in step.keys) {
                    host.pressKey(key)
                    pressed += key
                }

                is MacroStep.Release -> for (key in step.keys) {
                    host.releaseKey(key)
                    pressed -= key
                }

                is MacroStep.Hold -> {
                    for (key in step.keys) {
                        host.pressKey(key)
                        pressed += key
                    }
                    host.delay(step.durationMs)
                    for (key in step.keys.asReversed()) {
                        host.releaseKey(key)
                        pressed -= key
                    }
                }

                is MacroStep.Tap -> {
                    host.pressKey(step.key)
                    pressed += step.key
                    host.delay(step.holdMs)
                    host.releaseKey(step.key)
                    pressed -= step.key
                }

                is MacroStep.Type -> typeText(step, pressed)

                is MacroStep.Delay ->
                    host.delay(step.ms + jitter(step.jitterMs))

                is MacroStep.Repeat -> {
                    repeat(step.times) { i ->
                        executeSteps(step.steps, pressed, depth + 1)
                        if (i != step.times - 1 && step.intervalMs > 0) {
                            host.delay(step.intervalMs)
                        }
                    }
                }

                is MacroStep.Run -> {
                    // The session resolves nested macros before execution.
                    throw IllegalStateException("nested run steps must be resolved by the session")
                }

                is MacroStep.Set -> host.setControl(step.control, step.value)
                is MacroStep.Haptic -> host.haptic(step.ms)
                is MacroStep.Page -> host.page(step.screen)
            }
        }
    }

    /**
     * Types text using the US layout. Shift is held only while needed, so a
     * run of uppercase/symbol characters produces one modifier transition
     * instead of one per character.
     */
    private suspend fun typeText(step: MacroStep.Type, pressed: MutableSet<String>) {
        var shiftHeld = false
        try {
            for (ch in step.text) {
                val cap = TextLayout.keyCapFor(ch) ?: continue // untypable chars are skipped
                if (cap.shift && !shiftHeld) {
                    host.pressKey(SHIFT)
                    pressed += SHIFT
                    shiftHeld = true
                } else if (!cap.shift && shiftHeld) {
                    host.releaseKey(SHIFT)
                    pressed -= SHIFT
                    shiftHeld = false
                }
                val keyId = KEYBOARD_PREFIX + cap.key
                host.pressKey(keyId)
                pressed += keyId
                host.delay(TAP_HOLD_MS)
                host.releaseKey(keyId)
                pressed -= keyId
                host.delay(step.keyDelayMs + jitter(step.jitterMs))
            }
        } finally {
            if (shiftHeld) {
                host.releaseKey(SHIFT)
                pressed -= SHIFT
            }
        }
    }

    private fun jitter(range: Long): Long =
        if (range <= 0) 0 else random.nextLong(range + 1)

    companion object {
        const val MAX_DEPTH = 5
        const val TAP_HOLD_MS = 15L
        const val SHIFT = "keyboard.leftShift"
        const val KEYBOARD_PREFIX = "keyboard."
    }
}
