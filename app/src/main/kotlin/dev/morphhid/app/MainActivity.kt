package dev.morphhid.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.morphhid.app.bluetooth.BluetoothHidTransport
import dev.morphhid.app.data.ProfileRepository
import dev.morphhid.core.control.TransportPhase
import dev.morphhid.ui.renderer.ProfileRenderer

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val controller = (application as MorphHidApplication).controller
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = androidx.compose.ui.graphics.Color(0xFF7C4DFF),
                    secondary = androidx.compose.ui.graphics.Color(0xFF00E5FF),
                ),
            ) {
                App(controller)
            }
        }
    }
}

private sealed interface Screen {
    data object Home : Screen
    data object Runtime : Screen
    data object Pairing : Screen
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(controller: AppController) {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    val sessionState by controller.session.state.collectAsState()
    val activeProfile by controller.activeProfile.collectAsState()

    // Runtime follows profile activation.
    LaunchedEffect(activeProfile) {
        if (activeProfile != null && screen != Screen.Runtime) screen = Screen.Runtime
        if (activeProfile == null && screen == Screen.Runtime) screen = Screen.Home
    }

    // System back gesture/button returns to Home instead of exiting.
    BackHandler(enabled = screen != Screen.Home) {
        screen = Screen.Home
    }

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Text(
                    when (screen) {
                        Screen.Home -> "MorphHID"
                        Screen.Runtime -> activeProfile?.device?.name ?: "Runtime"
                        Screen.Pairing -> "Connect to host"
                    }
                )
            })
        },
    ) { padding ->
        when (screen) {
            Screen.Home -> HomeScreen(
                controller = controller,
                modifier = Modifier.padding(padding),
                onOpenPairing = { screen = Screen.Pairing },
                onOpenRuntime = { screen = Screen.Runtime },
            )
            Screen.Runtime -> RuntimeScreen(
                controller = controller,
                sessionState = sessionState,
                activeProfile = activeProfile,
                modifier = Modifier.padding(padding),
                onExit = {
                    controller.deactivate()
                    screen = Screen.Home
                },
            )
            Screen.Pairing -> PairingScreen(
                controller = controller,
                modifier = Modifier.padding(padding),
                onBack = { screen = Screen.Home },
            )
        }
    }
}

@Composable
private fun HomeScreen(
    controller: AppController,
    modifier: Modifier = Modifier,
    onOpenPairing: () -> Unit,
    onOpenRuntime: () -> Unit,
) {
    val context = LocalContext.current
    var profiles by remember { mutableStateOf<List<ProfileRepository.StoredProfile>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    val sessionState by controller.session.state.collectAsState()




    // Runtime permissions (BLUETOOTH_CONNECT on S+, notifications on 33+).
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }
    LaunchedEffect(Unit) {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
        }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (wanted.isNotEmpty()) permissionLauncher.launch(wanted.toTypedArray())
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val text = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.use { it.readText() }
                if (text == null) {
                    message = "Could not read the selected file"
                } else {
                    val name = uri.lastPathSegment?.substringAfterLast('/') ?: "imported.json"
                    val outcome = controller.repository.save(name, text)
                    message = if (outcome.stored != null) {
                        "Imported '${outcome.stored.profile.device.name}'"
                    } else {
                        outcome.error ?: "Import failed"
                    }
                    profiles = controller.repository.list()
                }
            } catch (e: Exception) {
                message = "Import failed: ${e.message}"
            }
        }
    }

    LaunchedEffect(Unit) { profiles = controller.repository.list() }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            StatusCard(sessionState)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                    Text("Import profile")
                }
                OutlinedButton(onClick = onOpenPairing) {
                    Text("Connect host")
                }
            }
        }
        message?.let { msg ->
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Text(msg, modifier = Modifier.padding(12.dp), fontSize = 13.sp)
                }
            }
        }
        item {
            Text("Profiles", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(profiles, key = { it.fileName }) { stored ->
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (stored.hasErrors) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(stored.profile.device.name, fontSize = 16.sp)
                    if (stored.profile.device.description.isNotBlank()) {
                        Text(
                            stored.profile.device.description,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "controls: ${stored.compiled.controls.size} · reports: ${stored.compiled.reports.size} · fp ${stored.compiled.fingerprint.take(8)}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = !stored.hasErrors,
                            onClick = {
                                controller.activateProfile(stored)
                                onOpenRuntime()
                            },
                        ) { Text("Activate") }
                        OutlinedButton(onClick = {
                            controller.repository.delete(stored.fileName)
                            profiles = controller.repository.list()
                        }) { Text("Delete") }
                    }
                    if (stored.issues.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        stored.issues.take(3).forEach {
                            Text(
                                "${it.severity}: ${it.message}",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }
        }
        item {
            Text(
                "Recent activity",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(
            controller.session.audit.recent(8).reversed(),
            key = { "${it.timestampMs}-${it.action}-${it.target}" },
        ) { event ->
            Text(
                "${event.result.padEnd(7)} ${event.action} ${event.target ?: ""} — ${event.actorId}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusCard(state: dev.morphhid.core.control.SessionState) {
    Card(shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp)) {
            val (phase, color) = when (state.connection.phase) {
                TransportPhase.CONNECTED -> "Connected" to MaterialTheme.colorScheme.primary
                TransportPhase.CONNECTING -> "Connecting" to MaterialTheme.colorScheme.secondary
                TransportPhase.REGISTERED, TransportPhase.REGISTERING -> "Registered" to MaterialTheme.colorScheme.secondary
                TransportPhase.FAILED -> "Failed" to MaterialTheme.colorScheme.error
                else -> "Idle" to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(phase, fontSize = 18.sp, color = color)
            state.connection.hostName?.let {
                Text("Host: $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.activeProfileName?.let {
                Text("Profile: $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.connection.message?.let {
                Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
            }
            if (state.runningMacros.isNotEmpty()) {
                Text(
                    "Running macros: ${state.runningMacros.joinToString()}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}

@Composable
private fun RuntimeScreen(
    controller: AppController,
    sessionState: dev.morphhid.core.control.SessionState,
    activeProfile: dev.morphhid.core.profile.Profile?,
    modifier: Modifier = Modifier,
    onExit: () -> Unit,
) {
    val requestedScreen by controller.requestedScreen.collectAsState()
    if (activeProfile == null) {
        Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No active profile")
            Spacer(Modifier.height(12.dp))
            Button(onClick = onExit) { Text("Back") }
        }
        return
    }
    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when (sessionState.connection.phase) {
                    TransportPhase.CONNECTED -> "● ${sessionState.connection.hostName ?: "host"}"
                    else -> sessionState.connection.phase.name.lowercase()
                },
                fontSize = 13.sp,
                color = if (sessionState.connection.phase == TransportPhase.CONNECTED)
                    MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onExit) { Text("Stop") }
        }
        ProfileRenderer(
            profile = activeProfile,
            host = controller.widgetHost,
            ledStates = sessionState.ledStates,
            requestedScreenId = requestedScreen,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PairingScreen(
    controller: AppController,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val sessionState by controller.session.state.collectAsState()
    var hosts by remember { mutableStateOf<List<android.bluetooth.BluetoothDevice>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var discoverable by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val discoverableLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { discoverable = controller.transport.isDiscoverable() }
    fun requestDiscoverable() {
        if (controller.transport.setDiscoverable(300)) {
            discoverable = true
            return
        }
        try {
            discoverableLauncher.launch(
                Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                    putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
                },
            )
        } catch (e: Exception) {
            message = "Cannot start discoverable: ${e.message}"
        }
    }

    LaunchedEffect(Unit) {
        hosts = controller.transport.bondedHosts()
        discoverable = controller.transport.isDiscoverable()
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // --- Status row ---
        item {
            Card(shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(14.dp)) {
                    val phase = sessionState.connection.phase
                    Text(
                        when (phase) {
                            dev.morphhid.core.control.TransportPhase.CONNECTED -> "Connected to ${sessionState.connection.hostName ?: "host"}"
                            dev.morphhid.core.control.TransportPhase.REGISTERED -> "HID profile registered, waiting for host"
                            dev.morphhid.core.control.TransportPhase.REGISTERING -> "Registering HID profile..."
                            else -> "No HID profile active"
                        },
                        fontSize = 15.sp,
                        color = if (phase == dev.morphhid.core.control.TransportPhase.CONNECTED)
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (discoverable) "Phone is discoverable (hosts can find it)"
                        else "Phone is NOT discoverable — tap 'Make Discoverable' below",
                        fontSize = 12.sp,
                        color = if (discoverable) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        // --- Discoverable button ---
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !discoverable,
                    onClick = {
                        // Hidden setScanMode is unavailable on many Android 15/16
                        // builds; use the supported activity-result request.
                        requestDiscoverable()
                    },
                ) { Text("Make Discoverable") }
                OutlinedButton(onClick = {
                    hosts = controller.transport.bondedHosts()
                    discoverable = controller.transport.isDiscoverable()
                }) { Text("Refresh") }
            }
        }

        // --- Instructions ---
        item {
            Card(shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text("Windows / macOS / Linux connection", fontSize = 15.sp)
                    Text(
                        "1. Activate a profile in MorphHID (Home screen → Activate).\n" +
                            "   Wait for status to say 'HID profile registered'.\n\n" +
                            "2. Tap 'Make Discoverable' above (or below if already done).\n\n" +
                            "3. If this phone was previously paired with the computer:\n" +
                            "   a. On the computer: remove this phone from Bluetooth devices.\n" +
                            "   b. On this phone: tap 'Unpair' next to the computer below.\n" +
                            "   (Old cached pairing data prevents Windows from seeing the HID service.)\n\n" +
                            "4. On the computer: open Bluetooth → Add device.\n" +
                            "   When pairing finishes, MorphHID automatically connects from the phone.\n" +
                            "   If it does not, remove the pairing on both sides and try again.\n\n" +
                            "Note: Windows may show the phone as 'PC' or a generic icon — this is " +
                            "cosmetic (Bluetooth Class-of-Device is OS-controlled). The real test " +
                            "is whether Device Manager gains HID Keyboard/Mouse entries.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text("Android host connection", fontSize = 15.sp)
                    Text(
                        "1. Pair the two devices from Android Bluetooth settings first.\n" +
                            "2. The host appears below — tap 'Connect' next to it.\n" +
                            "3. The phone initiates the HID connection to the host.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }

        // --- Bonded hosts ---
        item {
            Text(
                "Paired devices",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (hosts.isEmpty()) {
            item {
                Text(
                    "No paired devices. Pair from Android Bluetooth settings first.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(hosts, key = { it.address }) { device ->
            val name = try { device.name } catch (e: SecurityException) { device.address }
            Card(shape = RoundedCornerShape(14.dp)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(name ?: device.address, fontSize = 15.sp)
                        Text(device.address, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy, onClick = {
                            busy = true
                            controller.connectHost(device.address) { ok ->
                                busy = false
                                if (ok) onBack()
                            }
                        }) { Text("Connect") }
                        TextButton(enabled = !busy, onClick = {
                            busy = true
                            val ok = controller.transport.unpairHost(device.address)
                            busy = false
                            if (ok) {
                                hosts = controller.transport.bondedHosts()
                                message = "Unpaired ${name ?: device.address}"
                            } else {
                                message = "Unpair failed"
                            }
                        }) { Text("Unpair") }
                    }
                }
            }
        }

        message?.let { msg ->
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Text(msg, modifier = Modifier.padding(12.dp), fontSize = 13.sp)
                }
            }
        }
    }
}
