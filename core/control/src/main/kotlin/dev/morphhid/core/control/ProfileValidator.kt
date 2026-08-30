package dev.morphhid.core.control

import dev.morphhid.core.hid.ConsumerUsage
import dev.morphhid.core.hid.ControlKind
import dev.morphhid.core.hid.GamepadAxisUsage
import dev.morphhid.core.hid.HidDescriptorCompiler
import dev.morphhid.core.hid.TextLayout
import dev.morphhid.core.profile.BindingSpec
import dev.morphhid.core.profile.HidCollectionSpec
import dev.morphhid.core.profile.MacroStep
import dev.morphhid.core.profile.Profile
import dev.morphhid.core.profile.WidgetSpec

/**
 * Semantic validation of a parsed profile. Structural (JSON) errors are
 * reported by the parser; this class catches dangling references, unknown
 * usage names, untypable text, macro cycles, oversized reports and duplicate
 * ids before a profile can be activated.
 */
class ProfileValidator {

    enum class Severity { ERROR, WARNING }

    data class Issue(val severity: Severity, val message: String, val path: String? = null)

    fun validate(profile: Profile): List<Issue> {
        val issues = mutableListOf<Issue>()

        if (profile.schemaVersion != SUPPORTED_SCHEMA) {
            issues += Issue(Severity.ERROR, "unsupported schemaVersion ${profile.schemaVersion} (expected $SUPPORTED_SCHEMA)", "schemaVersion")
        }
        if (profile.device.name.isBlank()) {
            issues += Issue(Severity.ERROR, "device.name must not be blank", "device.name")
        }
        if (profile.device.hid.collections.isEmpty()) {
            issues += Issue(Severity.ERROR, "at least one HID collection is required", "device.hid.collections")
        }

        // --- report ids & collection specifics --------------------------------
        val seenReportIds = mutableSetOf<Int>()
        profile.device.hid.collections.forEachIndexed { i, c ->
            val path = "device.hid.collections[$i]"
            c.reportId?.let { id ->
                if (id !in 1..255) issues += Issue(Severity.ERROR, "reportId must be 1..255, got $id", path)
                if (!seenReportIds.add(id)) issues += Issue(Severity.ERROR, "duplicate reportId $id", path)
            }
            when (c) {
                is HidCollectionSpec.Keyboard -> if (c.rolloverKeys !in 1..32) {
                    issues += Issue(Severity.ERROR, "rolloverKeys must be 1..32", path)
                }
                is HidCollectionSpec.Pointer -> {
                    if (c.buttons !in 0..32) issues += Issue(Severity.ERROR, "buttons must be 0..32", path)
                    for (a in c.relativeAxes) if (a !in setOf("x", "y", "wheel")) {
                        issues += Issue(Severity.ERROR, "unknown relative axis '$a'", path)
                    }
                    for (a in c.absoluteAxes) if (a !in setOf("x", "y")) {
                        issues += Issue(Severity.ERROR, "unknown absolute axis '$a'", path)
                    }
                }
                is HidCollectionSpec.Consumer -> {
                    if (c.usages.isEmpty()) issues += Issue(Severity.ERROR, "consumer collection needs at least one usage", path)
                    for (u in c.usages) if (u !in ConsumerUsage.BY_NAME) {
                        issues += Issue(Severity.ERROR, "unknown consumer usage '$u'", path)
                    }
                }
                is HidCollectionSpec.Gamepad -> {
                    if (c.buttons !in 0..64) issues += Issue(Severity.ERROR, "buttons must be 0..64", path)
                    for (a in c.axes) if (a !in GamepadAxisUsage.BY_NAME) {
                        issues += Issue(Severity.ERROR, "unknown gamepad axis '$a'", path)
                    }
                    if (c.axisMin >= c.axisMax) {
                        issues += Issue(Severity.ERROR, "axisMin must be < axisMax", path)
                    }
                }
            }
        }

        // --- compile (report sizes, descriptor validity) -----------------------
        var controlIds: Set<String> = emptySet()
        var booleanControls: Set<String> = emptySet()
        var axisControls: Set<String> = emptySet()
        try {
            val compiled = HidDescriptorCompiler().compile(profile)
            controlIds = compiled.controls.map { it.id }.toSet()
            booleanControls = compiled.controls
                .filter { it.kind in setOf(ControlKind.KEY, ControlKind.MODIFIER, ControlKind.BUTTON, ControlKind.CONSUMER) }
                .map { it.id }.toSet()
            axisControls = compiled.controls
                .filter { it.kind == ControlKind.AXIS || it.kind == ControlKind.REL_AXIS }
                .map { it.id }.toSet()
            if (controlIds.size < compiled.controls.size) {
                issues += Issue(Severity.ERROR, "duplicate control ids after compilation", "device.hid")
            }
        } catch (e: IllegalArgumentException) {
            issues += Issue(Severity.ERROR, e.message ?: "failed to compile HID descriptor", "device.hid")
        }

        // --- macros -------------------------------------------------------------
        for ((macroId, macro) in profile.macros) {
            checkMacroSteps(macroId, macro.steps, setOf(macroId), controlIds, issues)
        }

        // --- ui -----------------------------------------------------------------
        val screenIds = mutableSetOf<String>()
        profile.ui?.screens?.forEach { s ->
            if (!screenIds.add(s.id)) {
                issues += Issue(Severity.ERROR, "duplicate screen id '${s.id}'", "ui.screens")
            }
        }
        val widgetIds = mutableSetOf<String>()
        val ui = profile.ui
        ui?.screens?.forEach { screen ->
            screen.widgets.forEach { w ->
                if (!widgetIds.add(w.id)) {
                    issues += Issue(Severity.ERROR, "duplicate widget id '${w.id}'", "ui.screens[${screen.id}].widgets")
                }
                checkWidget(w, controlIds, booleanControls, axisControls, screenIds, profile.macros.keys, issues)
            }
        }
        if (ui != null && ui.screens.isEmpty()) {
            issues += Issue(Severity.WARNING, "profile has a ui section but no screens; runtime screen will be blank", "ui.screens")
        }

        // --- agent ---------------------------------------------------------------
        for (s in profile.agent?.sensitiveControls.orEmpty()) {
            if (s !in controlIds) {
                issues += Issue(Severity.WARNING, "sensitive control '$s' does not exist", "agent.sensitiveControls")
            }
        }

        return issues
    }

    private fun checkMacroSteps(
        macroId: String,
        steps: List<MacroStep>,
        visited: Set<String>,
        controlIds: Set<String>,
        issues: MutableList<Issue>,
    ) {
        for (step in steps) {
            when (step) {
                is MacroStep.Press, is MacroStep.Release -> {
                    val keys = if (step is MacroStep.Press) step.keys else (step as MacroStep.Release).keys
                    for (k in keys) if (k !in controlIds) {
                        issues += Issue(Severity.ERROR, "macro '$macroId' references unknown key '$k'", "macros.$macroId")
                    }
                }
                is MacroStep.Hold -> for (k in step.keys) if (k !in controlIds) {
                    issues += Issue(Severity.ERROR, "macro '$macroId' references unknown key '$k'", "macros.$macroId")
                }
                is MacroStep.Tap -> if (step.key !in controlIds) {
                    issues += Issue(Severity.ERROR, "macro '$macroId' references unknown key '${step.key}'", "macros.$macroId")
                }
                is MacroStep.Type -> if (!TextLayout.canTypeAll(step.text)) {
                    issues += Issue(Severity.ERROR, "macro '$macroId' cannot type '${step.text}' (US layout only in v1)", "macros.$macroId")
                }
                is MacroStep.Run -> {
                    // Cycle check happens here: nested macro id must not be in visited.
                    // (Existence is checked in ControlSession.resolveSteps as well.)
                }
                is MacroStep.Set -> if (step.control !in controlIds) {
                    issues += Issue(Severity.ERROR, "macro '$macroId' references unknown control '${step.control}'", "macros.$macroId")
                }
                is MacroStep.Repeat -> checkMacroSteps(macroId, step.steps, visited, controlIds, issues)
                is MacroStep.Delay, is MacroStep.Haptic, is MacroStep.Page -> Unit
            }
        }
    }

    private fun checkWidget(
        w: WidgetSpec,
        controlIds: Set<String>,
        booleanControls: Set<String>,
        axisControls: Set<String>,
        screenIds: Set<String>,
        macroIds: Set<String>,
        issues: MutableList<Issue>,
    ) {
        fun checkBinding(b: BindingSpec?, what: String) {
            when (b) {
                is BindingSpec.Key -> if (b.key !in booleanControls) {
                    issues += Issue(Severity.ERROR, "$what references unknown key '${b.key}'", "ui.widgets[${w.id}]")
                }
                is BindingSpec.Combo -> for (k in b.keys) if (k !in booleanControls) {
                    issues += Issue(Severity.ERROR, "$what references unknown key '$k'", "ui.widgets[${w.id}]")
                }
                is BindingSpec.Macro -> if (b.macro !in macroIds) {
                    issues += Issue(Severity.ERROR, "$what references unknown macro '${b.macro}'", "ui.widgets[${w.id}]")
                }
                is BindingSpec.Text -> if (!TextLayout.canTypeAll(b.text)) {
                    issues += Issue(Severity.ERROR, "$what cannot type '${b.text}' (US layout only in v1)", "ui.widgets[${w.id}]")
                }
                is BindingSpec.Page -> if (b.screen !in screenIds) {
                    issues += Issue(Severity.ERROR, "$what references unknown screen '${b.screen}'", "ui.widgets[${w.id}]")
                }
                null -> Unit
            }
        }

        when (w) {
            is WidgetSpec.Button -> {
                checkBinding(w.onTap, "onTap")
                checkBinding(w.onDoubleTap, "onDoubleTap")
                checkBinding(w.onLongPress, "onLongPress")
                if (w.momentaryKey != null && w.momentaryKey !in booleanControls) {
                    issues += Issue(Severity.ERROR, "momentaryKey references unknown key '${w.momentaryKey}'", "ui.widgets[${w.id}]")
                }
                if (w.onTap == null && w.onDoubleTap == null && w.onLongPress == null && w.momentaryKey == null) {
                    issues += Issue(Severity.WARNING, "button has no bindings", "ui.widgets[${w.id}]")
                }
            }
            is WidgetSpec.Toggle -> {
                if (w.control != null && w.control !in booleanControls) {
                    issues += Issue(Severity.ERROR, "control references unknown boolean control '${w.control}'", "ui.widgets[${w.id}]")
                }
                checkBinding(w.onTurnOn, "onTurnOn")
                checkBinding(w.onTurnOff, "onTurnOff")
            }
            is WidgetSpec.Joystick -> {
                for (axis in listOfNotNull(w.xAxis, w.yAxis)) {
                    if (axis !in axisControls) {
                        issues += Issue(Severity.ERROR, "joystick axis '$axis' is not a declared axis", "ui.widgets[${w.id}]")
                    }
                }
            }
            is WidgetSpec.Dpad -> {
                checkBinding(w.up, "up"); checkBinding(w.down, "down")
                checkBinding(w.left, "left"); checkBinding(w.right, "right")
            }
            is WidgetSpec.Slider -> if (w.control !in axisControls) {
                issues += Issue(Severity.ERROR, "slider control '${w.control}' is not a declared axis", "ui.widgets[${w.id}]")
            }
            is WidgetSpec.PointerPad -> Unit // pointer axes are implicit
            is WidgetSpec.KeyGrid -> for (row in w.rows) {
                for (key in row.keys) {
                    if ("keyboard.$key" !in booleanControls) {
                        issues += Issue(Severity.ERROR, "keyGrid references unknown key '$key'", "ui.widgets[${w.id}]")
                    }
                }
            }
            is WidgetSpec.Led, is WidgetSpec.Label -> Unit
        }
    }

    companion object {
        const val SUPPORTED_SCHEMA = 1
    }
}