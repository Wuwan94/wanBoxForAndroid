package io.nekohasekai.sagernet.bg

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.nekohasekai.sagernet.R

object SmartAutoStopNotifications {
    const val CHANNEL = "smart_auto_stop"

    fun ensureChannel(context: Context): NotificationChannel {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,
            context.getString(R.string.smart_auto_stop_title), NotificationManager.IMPORTANCE_LOW))
        return manager.getNotificationChannel(CHANNEL)
    }

    fun availability(context: Context): NotificationGate {
        val channel = ensureChannel(context)
        val runtimeGranted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return SmartAutoStopNotificationGate.evaluate(Build.VERSION.SDK_INT, runtimeGranted,
            NotificationManagerCompat.from(context).areNotificationsEnabled(),
            channel.importance != NotificationManager.IMPORTANCE_NONE)
    }

    fun settingsIntent(context: Context): Intent {
        val channelBlocked = ensureChannel(context).importance == NotificationManager.IMPORTANCE_NONE
        return if (NotificationManagerCompat.from(context).areNotificationsEnabled() && channelBlocked) {
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL)
        } else {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
    }
}
