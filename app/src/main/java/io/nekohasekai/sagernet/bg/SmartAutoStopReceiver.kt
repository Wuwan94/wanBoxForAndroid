package io.nekohasekai.sagernet.bg

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import kotlinx.coroutines.*

/** Root sends only closed-set phase codes. Notification actions never carry network rules. */
class SmartAutoStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val controller = SmartAutoStopController(object : SmartAutoStopOperations {
            override suspend fun resume() { RootModuleClient.resume() }
            override suspend fun stop() { RootModuleClient.stop() }
            override suspend fun status() = RootModuleClient.callFreshStatus().phase
        }) { phase -> reconcile(context, phase) }
        if (intent.action == STATE_ACTION) {
            controller.onPhase(intent.getStringExtra("phase"))
            return
        }
        if (intent.action != RESUME_ACTION && intent.action != STOP_ACTION) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeout(8_000) {
                    controller.onAction(if (intent.action == RESUME_ACTION) "resume" else "stop")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Preserve the last known notification on communication failure; never stop the core.
            } finally { pending.finish() }
        }
    }

    companion object {
        val STATE_ACTION = "${BuildConfig.APPLICATION_ID}.AUTO_STOP_STATE"
        private val RESUME_ACTION = "${BuildConfig.APPLICATION_ID}.AUTO_STOP_RESUME"
        private val STOP_ACTION = "${BuildConfig.APPLICATION_ID}.AUTO_STOP_STOP"
        private const val NOTIFICATION = 1041

        fun reconcile(context: Context, phase: String) {
            if (phase !in smartAutoStopPhases) return
            val manager = context.getSystemService(NotificationManager::class.java)
            if (phase != "suspended") { manager.cancel(NOTIFICATION); return }
            SmartAutoStopNotifications.ensureChannel(context)
            fun action(action: String, code: Int) = PendingIntent.getBroadcast(context, code,
                Intent(context, SmartAutoStopReceiver::class.java).setAction(action).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(context, SmartAutoStopNotifications.CHANNEL)
                .setSmallIcon(R.drawable.ic_line_heart)
                .setContentTitle(context.getString(R.string.smart_auto_stop_suspended))
                .setContentText(context.getString(R.string.smart_auto_stop_resume_hint))
                .setOngoing(true).setOnlyAlertOnce(true)
                .addAction(0, context.getString(R.string.smart_auto_stop_resume), action(RESUME_ACTION, 1))
                .addAction(0, context.getString(R.string.stop), action(STOP_ACTION, 2)).build()
            try { NotificationManagerCompat.from(context).notify(NOTIFICATION, notification) }
            catch (_: SecurityException) { /* Notification permission never controls networking. */ }
        }
    }
}
