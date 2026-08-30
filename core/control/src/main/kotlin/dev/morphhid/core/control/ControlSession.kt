package dev.morphhid.core.control

import dev.morphhid.core.hid.CompiledHid
import dev.morphhid.core.hid.ControlKind
import dev.morphhid.core.hid.ReportCodec
import dev.morphhid.core.profile.MacroPolicy
import dev.morphhid.core.profile.MacroStep
import dev.morphhid.core.profile.Profile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

/** Non-HID side effects produced by macros for the UI layer. */
sealed interface UiEvent {
    data class Page(val screenId: String) : UiEvent
    data class Haptic(val ms: Long) : UiEvent
}

data class SessionState(
    val activeProfileName: String? = null,
    val connection: TransportState = TransportState(),
    val runningMacros: Set<String> = emptySet(),
    val ledStates: Map<String, Boolean> = emptyMap(),
    val lastError: String? = null,
)

/**
 * The single control plane shared by the on-screen UI, macro engine and
 * agent adapters. All mutations are serialized through one mutex so that
 * report state is always consistent and every actor can be audited.
 */
class ControlSession(
    private val transport: HidTransport,
    val audit: AuditLog = InMemoryAuditLog(),
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _uiEvents = MutableSharedFlow<UiEvent>(extraBufferCapacity = 64)
    val uiEvents: SharedFlow<UiEvent> = _uiEvents.asSharedFlow()

    private var profile: Profile? = null
    private var compiled: CompiledHid? = null
    private var codec: ReportCodec? = null
    private var policy: AccessPolicy = AccessPolicy(null)

    private val macroJobs = HashMap<String, Job>()
    private val macroQueues = HashMap<String, MutableList<Pair<Actor, String>>>()

    init {
        scope.launch {
            transport.state.collect { ts -> _state.update { it.copy(connection = ts) } }
        }
        scope.launch {
            transport.outputReports.collect { report ->
                val leds = mutex.withLock {
                    codec?.decodeOutput(report.reportId, report.payload).orEmpty()
                }
                if (leds.isNotEmpty()) {
                    _state.update { it.copy(ledStates = it.ledStates + leds) }
                }
            }
        }
    }

    // ------------------------------------------------------------- lifecycle

    suspend fun activate(profile: Profile, compiled: CompiledHid): OpResult = mutex.withLock {
        if (this.compiled != null) {
            cancelMacrosLocked()
            runCatching { transport.disconnect() }
            runCatching { transport.unregister() }
        }
        this.profile = profile
        this.compiled = compiled
        this.codec = ReportCodec(compiled)
        this.policy = AccessPolicy(profile)
        this.macroQueues.clear()

        val ok = runCatching { transport.register(compiled) }.getOrDefault(false)
        if (!ok) {
            this.profile = null
            this.compiled = null
            this.codec = null
            this.policy = AccessPolicy(null)
            val msg = "transport rejected HID registration"
            _state.update { it.copy(lastError = msg) }
            record(HumanActor, "activate", profile.device.name, "error", msg)
            return@withLock OpResult.Error(msg)
        }
        _state.update {
            it.copy(activeProfileName = profile.device.name, ledStates = emptyMap(), lastError = null)
        }
        record(HumanActor, "activate", profile.device.name, "ok", "descriptor=${compiled.descriptor.size}B fp=${compiled.fingerprint.take(12)}")
        OpResult.Ok
    }

    suspend fun deactivate(): OpResult = mutex.withLock {
        cancelMacrosLocked()
        runCatching { transport.disconnect() }
        runCatching { transport.unregister() }
        profile = null
        compiled = null
        codec = null
        policy = AccessPolicy(null)
        macroQueues.clear()
        _state.update { SessionState() }
        record(HumanActor, "deactivate", null, "ok", null)
        OpResult.Ok
    }

    suspend fun connectHost(address: String): OpResult {
        val ok = transport.connect(address)
        record(HumanActor, "connect", address, if (ok) "ok" else "error", null)
        return if (ok) OpResult.Ok else OpResult.Error("connect failed")
    }

    // --------------------------------------------------------------- controls

    suspend fun actuate(actor: Actor, controlId: String, pressed: Boolean): OpResult {
        val check = policy.check(actor, ActionKind.ACTUATE_CONTROL, controlId)
        if (check !is OpResult.Ok) {
            record(actor, if (pressed) "press" else "release", controlId, "denied", (check as? OpResult.Denied)?.reason)
            return check
        }
        val result = mutex.withLock { actuateLocked(actor, controlId, pressed) }
        record(actor, if (pressed) "press" else "release", controlId,
            if (result is OpResult.Ok) "ok" else "error",
            if (result is OpResult.Error) result.message else null)
        return result
    }

    /** Continuous pointer stream; exempt from the per-action rate meter. */
    suspend fun movePointer(actor: Actor, dx: Float, dy: Float): OpResult {
        val check = policy.check(actor, ActionKind.ACTUATE_CONTROL, "pointer.x", meter = false)
        if (check !is OpResult.Ok) return check
        return mutex.withLock {
            val c = codec ?: return@withLock OpResult.Error("no active profile")
            if (!requireConnected()) return@withLock OpResult.Error("not connected")
            c.addRelative("pointer.x", Math.round(dx).toInt())
            c.addRelative("pointer.y", Math.round(dy).toInt())
            flushLocked()
            OpResult.Ok
        }
    }

    /** Continuous axis stream; exempt from the per-action rate meter. */
    suspend fun setAxisNormalized(actor: Actor, controlId: String, value: Float): OpResult {
        val check = policy.check(actor, ActionKind.ACTUATE_CONTROL, controlId, meter = false)
        if (check !is OpResult.Ok) return check
        return mutex.withLock {
            val c = codec ?: return@withLock OpResult.Error("no active profile")
            if (!requireConnected()) return@withLock OpResult.Error("not connected")
            c.setAxisNormalized(controlId, value)
            flushLocked()
            OpResult.Ok
        }
    }

    suspend fun setHat(actor: Actor, controlId: String, value: Int): OpResult {
        val check = policy.check(actor, ActionKind.ACTUATE_CONTROL, controlId, meter = false)
        if (check !is OpResult.Ok) return check
        return mutex.withLock {
            val c = codec ?: return@withLock OpResult.Error("no active profile")
            if (!requireConnected()) return@withLock OpResult.Error("not connected")
            c.setHat(controlId, value)
            flushLocked()
            OpResult.Ok
        }
    }

    suspend fun releaseAll(actor: Actor): OpResult {
        val result = mutex.withLock {
            val c = codec ?: return@withLock OpResult.Error("no active profile")
            c.releaseAll()
            if (requireConnected()) flushLocked()
            OpResult.Ok
        }
        record(actor, "releaseAll", null, "ok", null)
        return result
    }

    suspend fun emergencyStop(actor: Actor): OpResult {
        mutex.withLock { cancelMacrosLocked() }
        val result = mutex.withLock {
            codec?.releaseAll()
            codec?.markAllDirty()
            if (requireConnected()) flushLocked()
            runCatching { transport.disconnect() }
            OpResult.Ok
        }
        record(actor, "emergencyStop", null, "ok", null)
        return result
    }

    // ----------------------------------------------------------------- macros

    suspend fun runMacro(actor: Actor, macroId: String): OpResult {
        val check = policy.check(actor, ActionKind.INVOKE_MACRO, macroId)
        if (check !is OpResult.Ok) {
            record(actor, "runMacro", macroId, "denied", (check as? OpResult.Denied)?.reason)
            return check
        }
        val spec = profile?.macros?.get(macroId)
            ?: return OpResult.Error("unknown macro '$macroId'")

        val steps = try {
            resolveSteps(macroId, emptySet())
        } catch (e: IllegalArgumentException) {
            record(actor, "runMacro", macroId, "error", e.message)
            return OpResult.Error(e.message ?: "macro resolution failed")
        }

        when (spec.policy) {
            MacroPolicy.RESTART -> mutex.withLock { macroJobs[macroId]?.cancel() }
            MacroPolicy.IGNORE -> mutex.withLock {
                if (macroJobs[macroId]?.isActive == true) {
                    record(actor, "runMacro", macroId, "ignored", "already running")
                    return OpResult.Error("macro '$macroId' is already running")
                }
            }
            MacroPolicy.QUEUE -> mutex.withLock {
                if (macroJobs[macroId]?.isActive == true) {
                    macroQueues.getOrPut(macroId) { mutableListOf() }.add(actor to macroId)
                    record(actor, "runMacro", macroId, "queued", null)
                    return OpResult.Ok
                }
            }
            MacroPolicy.PARALLEL -> Unit
        }

        val job = scope.launch {
            _state.update { it.copy(runningMacros = it.runningMacros + macroId) }
            try {
                MacroRuntime(MacroHostImpl(actor)).execute(steps)
                record(actor, "runMacro", macroId, "ok", "steps=${steps.size}")
            } catch (e: CancellationException) {
                record(actor, "runMacro", macroId, "cancelled", null)
                throw e
            } catch (e: Exception) {
                record(actor, "runMacro", macroId, "error", e.message)
            } finally {
                _state.update { it.copy(runningMacros = it.runningMacros - macroId) }
                mutex.withLock {
                    if (macroJobs[macroId] == coroutineContext[Job]) macroJobs.remove(macroId)
                }
                drainMacroQueue(macroId)
            }
        }
        mutex.withLock { macroJobs[macroId] = job }
        record(actor, "runMacro", macroId, "started", "steps=${steps.size} policy=${spec.policy}")
        return OpResult.Ok
    }


    /**
     * Runs an ad-hoc sequence (a UI binding) through the same engine, audit
     * and stuck-key protection as declared macros. `run` steps resolve
     * against the active profile's macros.
     */
    suspend fun runAdhoc(actor: Actor, label: String, steps: List<MacroStep>): OpResult {
        val check = policy.check(actor, ActionKind.ACTUATE_CONTROL, null)
        if (check !is OpResult.Ok) {
            record(actor, "binding", label, "denied", (check as? OpResult.Denied)?.reason)
            return check
        }
        val resolved = try {
            resolveFrom(steps, emptySet())
        } catch (e: IllegalArgumentException) {
            record(actor, "binding", label, "error", e.message)
            return OpResult.Error(e.message ?: "binding resolution failed")
        }
        val key = "adhoc:" + label
        mutex.withLock { macroJobs[key]?.cancel() }
        val job = scope.launch {
            try {
                MacroRuntime(MacroHostImpl(actor)).execute(resolved)
                record(actor, "binding", label, "ok", "steps=" + resolved.size)
            } catch (e: CancellationException) {
                record(actor, "binding", label, "cancelled", null)
                throw e
            } catch (e: Exception) {
                record(actor, "binding", label, "error", e.message)
            } finally {
                mutex.withLock {
                    if (macroJobs[key] == coroutineContext[Job]) macroJobs.remove(key)
                }
            }
        }
        mutex.withLock { macroJobs[key] = job }
        return OpResult.Ok
    }
    fun listControls(actor: Actor): List<String> {
        val check = policy.check(actor, ActionKind.READ, null)
        if (check !is OpResult.Ok) return emptyList()
        return compiled?.controls?.map { it.id } ?: emptyList()
    }

    fun listMacros(actor: Actor): List<String> {
        val check = policy.check(actor, ActionKind.READ, null)
        if (check !is OpResult.Ok) return emptyList()
        return profile?.macros?.keys?.toList() ?: emptyList()
    }

    fun activeProfile(): Profile? = profile

    fun activeCompiled(): CompiledHid? = compiled

    // ---------------------------------------------------------------- private

    private suspend fun actuateLocked(actor: Actor, controlId: String, pressed: Boolean): OpResult {
        val c = codec ?: return OpResult.Error("no active profile")
        if (!requireConnected()) return OpResult.Error("not connected")
        val changed = if (pressed) c.press(controlId) else c.release(controlId)
        if (changed) flushLocked()
        return OpResult.Ok
    }

    private suspend fun flushLocked() {
        val c = codec ?: return
        for ((reportId, payload) in c.flushDirty()) {
            transport.sendReport(reportId, payload)
        }
    }

    private fun requireConnected(): Boolean =
        transport.state.value.phase == TransportPhase.CONNECTED

    private suspend fun drainMacroQueue(macroId: String) {
        val next = mutex.withLock {
            macroQueues[macroId]?.removeFirstOrNull()
        } ?: return
        runMacro(next.first, next.second)
    }

    private fun cancelMacrosLocked() {
        macroJobs.values.forEach { it.cancel() }
        macroJobs.clear()
    }

    /** Flattens `run` steps with cycle detection and a total-step cap. */
    private fun resolveFrom(steps: List<MacroStep>, visited: Set<String>): List<MacroStep> {
        val out = mutableListOf<MacroStep>()
        resolveInto(steps, visited, out)
        if (out.size > MAX_FLATTENED_STEPS) {
            throw IllegalArgumentException("macro expands to more than MAX_FLATTENED_STEPS steps")
        }
        return out
    }

    private fun resolveSteps(macroId: String, visited: Set<String>): List<MacroStep> {
        val spec = profile?.macros?.get(macroId)
            ?: throw IllegalArgumentException("unknown macro '$macroId'")
        return resolveFrom(spec.steps, visited + macroId)
    }

    private fun resolveInto(steps: List<MacroStep>, visited: Set<String>, out: MutableList<MacroStep>) {
        for (step in steps) {
            when (step) {
                is MacroStep.Run -> {
                    val nested = profile?.macros?.get(step.macro)
                        ?: throw IllegalArgumentException("unknown macro '${step.macro}'")
                    resolveInto(nested.steps, visited + step.macro, out)
                }
                is MacroStep.Repeat -> {
                    val inner = mutableListOf<MacroStep>()
                    resolveInto(step.steps, visited, inner)
                    out += step.copy(steps = inner)
                }
                else -> out += step
            }
        }
    }

    private fun record(actor: Actor, action: String, target: String?, result: String, detail: String?) {
        audit.record(
            AuditEvent(
                timestampMs = System.currentTimeMillis(),
                actorId = "${if (actor is Actor.Human) "human" else "agent"}:${actor.id}",
                action = action,
                target = target,
                result = result,
                detail = detail,
            )
        )
    }

    private inner class MacroHostImpl(private val actor: Actor) : MacroRuntime.Host {
        override suspend fun pressKey(keyId: String) {
            mutex.withLock { actuateLocked(actor, keyId, pressed = true) }
        }

        override suspend fun releaseKey(keyId: String) {
            mutex.withLock { actuateLocked(actor, keyId, pressed = false) }
        }

        override suspend fun setControl(controlId: String, value: String) {
            mutex.withLock {
                val c = codec ?: return@withLock
                if (!requireConnected()) return@withLock
                val control = compiled?.findControl(controlId)
                when (control?.kind) {
                    ControlKind.AXIS -> {
                        val v = value.toFloatOrNull() ?: return@withLock
                        if (abs(v) <= 1.0f) {
                            c.setAxisNormalized(controlId, v)
                        } else {
                            c.setAxis(controlId, v.toInt())
                        }
                        flushLocked()
                    }
                    ControlKind.KEY, ControlKind.MODIFIER, ControlKind.BUTTON, ControlKind.CONSUMER -> {
                        val on = value.equals("1", true) || value.equals("on", true) || value.equals("true", true)
                        if (on) c.press(controlId) else c.release(controlId)
                        flushLocked()
                    }
                    else -> Unit
                }
            }
        }

        override suspend fun page(screenId: String) {
            _uiEvents.tryEmit(UiEvent.Page(screenId))
        }

        override suspend fun haptic(ms: Long) {
            _uiEvents.tryEmit(UiEvent.Haptic(ms))
        }

        override suspend fun delay(ms: Long) {
        kotlinx.coroutines.delay(ms)
        }
    }

    companion object {
        private val HumanActor = Actor.Human()
        private const val MAX_FLATTENED_STEPS = 10_000
    }
}
