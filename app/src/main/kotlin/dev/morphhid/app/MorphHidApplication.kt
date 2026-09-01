package dev.morphhid.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import dev.morphhid.app.bluetooth.BluetoothHidTransport
import dev.morphhid.app.data.ProfileRepository
import dev.morphhid.app.service.HidForegroundService
import dev.morphhid.core.control.Actor
import dev.morphhid.core.control.ControlSession
import dev.morphhid.core.control.InMemoryAuditLog
import dev.morphhid.core.control.UiEvent
import dev.morphhid.core.profile.BindingSpec
import dev.morphhid.core.profile.MacroStep
import dev.morphhid.core.profile.Profile
import dev.morphhid.ui.renderer.WidgetHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MorphHidApplication : Application() {

    lateinit var controller: AppController
        private set

    override fun onCreate() {
        super.onCreate()
        controller = AppController(this)
        controller.start()
    }
}

/**
 * Application-scoped wiring between the UI, profile store, control session
 * and Bluetooth transport. Owns the single [ControlSession] every actor
 * (human UI, future agent adapters) funnels through.
 */
class AppController(private val context: Context) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val repository = ProfileRepository(context)
    val transport = BluetoothHidTransport(context)
    val session = ControlSession(transport, InMemoryAuditLog(capacity = 1000), scope)

    private val _activeProfile = MutableStateFlow<Profile?>(null)
    val activeProfile: StateFlow<Profile?> = _activeProfile.asStateFlow()

    private val _requestedScreen = MutableStateFlow<String?>(null)
    val requestedScreen: StateFlow<String?> = _requestedScreen.asStateFlow()

    val widgetHost: WidgetHost = ControllerWidgetHost()

    fun start() {
        repository.ensureSamples()
        scope.launch {
            session.uiEvents.collect { event ->
                when (event) {
                    is UiEvent.Page -> _requestedScreen.value = event.screenId
                    is UiEvent.Haptic -> vibrate(event.ms)
                }
            }
        }
    }

    fun activateProfile(stored: ProfileRepository.StoredProfile) {
        scope.launch {
            val result = session.activate(stored.profile, stored.compiled)
            if (result is dev.morphhid.core.control.OpResult.Ok) {
                _activeProfile.value = stored.profile
                HidForegroundService.start(context)
                // Make the phone discoverable so hosts (esp. Windows) can find
                // it and see the registered HID SDP record during "Add device".
                // Silent reflection first; UI can also trigger the intent.
                transport.setDiscoverable(300)
            }
        }
    }

    fun deactivate() {
        scope.launch {
            session.deactivate()
            _activeProfile.value = null
            HidForegroundService.stop(context)
        }
    }

    fun connectHost(address: String, onResult: (Boolean) -> Unit) {
        scope.launch { onResult(session.connectHost(address) is dev.morphhid.core.control.OpResult.Ok) }
    }

    fun unpairHost(address: String): Boolean = transport.unpairHost(address)

    fun makeDiscoverable(durationSeconds: Int = 300): Boolean = transport.setDiscoverable(durationSeconds)

    fun isDiscoverable(): Boolean = transport.isDiscoverable()

    private fun vibrate(ms: Long) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(ms)
            }
        } catch (_: Exception) {
        }
    }

    private inner class ControllerWidgetHost : WidgetHost {
        override fun onKey(keyId: String, pressed: Boolean) {
            scope.launch { session.actuate(Actor.Human(), keyId, pressed) }
        }

        override fun onBinding(binding: BindingSpec) {
            val steps = bindingSteps(binding)
            scope.launch { session.runAdhoc(Actor.Human(), bindingLabel(binding), steps) }
        }

        override fun onAxis(controlId: String, value: Float) {
            scope.launch { session.setAxisNormalized(Actor.Human(), controlId, value) }
        }

        override fun onControlSet(controlId: String, on: Boolean) {
            scope.launch { session.actuate(Actor.Human(), controlId, on) }
        }

        override fun onPointerDelta(dx: Float, dy: Float) {
            scope.launch { session.movePointer(Actor.Human(), dx, dy) }
        }

        override fun onWheel(delta: Float) {
            scope.launch { session.scroll(Actor.Human(), delta) }
        }


        override fun onPage(screenId: String) {
            _requestedScreen.value = screenId
        }
    }

    companion object {
        fun bindingSteps(binding: BindingSpec): List<MacroStep> = when (binding) {
            is BindingSpec.Key -> listOf(MacroStep.Tap(key = binding.key))
            is BindingSpec.Combo -> listOf(MacroStep.Hold(keys = binding.keys, durationMs = 90))
            is BindingSpec.Macro -> listOf(MacroStep.Run(macro = binding.macro))
            is BindingSpec.Text -> listOf(
                MacroStep.Type(text = binding.text, keyDelayMs = binding.keyDelayMs.toLong(), jitterMs = binding.jitterMs.toLong())
            )
            is BindingSpec.Page -> listOf(MacroStep.Page(screen = binding.screen))
        }

        fun bindingLabel(binding: BindingSpec): String = when (binding) {
            is BindingSpec.Key -> "key:${binding.key}"
            is BindingSpec.Combo -> "combo:${binding.keys.joinToString("+")}"
            is BindingSpec.Macro -> "macro:${binding.macro}"
            is BindingSpec.Text -> "text:${binding.text.take(16)}"
            is BindingSpec.Page -> "page:${binding.screen}"
        }
    }
}
