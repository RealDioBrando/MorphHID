package dev.morphhid.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.morphhid.app.MainActivity
import dev.morphhid.app.MorphHidApplication
import dev.morphhid.core.control.TransportPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the HID session alive while backgrounded. The notification doubles
 * as the always-available kill switch ("Disconnect" action).
 */
class HidForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            (applicationContext as? MorphHidApplication)?.controller?.deactivate()
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        observeState()
        return START_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "HID session", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = buildNotification("MorphHID active")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun observeState() {
        val controller = (applicationContext as? MorphHidApplication)?.controller ?: return
        scope.launch {
            controller.session.state.collect { state ->
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val text = when (state.connection.phase) {
                    TransportPhase.CONNECTED ->
                        "Connected to ${state.connection.hostName ?: state.connection.hostAddress}"
                    TransportPhase.CONNECTING -> "Connecting..."
                    TransportPhase.REGISTERED -> "HID registered — pick a host to connect"
                    TransportPhase.FAILED -> "Error: ${state.connection.message ?: "unknown"}"
                    else -> "MorphHID active"
                }
                nm.notify(NOTIFICATION_ID, buildNotification(text))
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, HidForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("MorphHID")
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(0, "Disconnect", stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "morphhid_session"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "dev.morphhid.action.STOP"

        fun start(context: Context) {
            val intent = Intent(context, HidForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HidForegroundService::class.java))
        }
    }
}