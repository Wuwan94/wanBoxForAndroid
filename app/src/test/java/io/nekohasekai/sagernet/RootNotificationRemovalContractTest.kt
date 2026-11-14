// @author 雾晚
package io.nekohasekai.sagernet

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Root module notifications must not reappear through secondary App paths. @author 雾晚 */
class RootNotificationRemovalContractTest {
    private fun source(path: String) = File("src/main/java/io/nekohasekai/sagernet/$path").readText()
    @Test fun applyPreservesSuspendedIntentWhileStillStartingStoppedResults() {
        val apply = source("bg/RootModuleClient.kt").substringAfter("suspend fun applyBackend(")
            .substringBefore("return applied")
        assertTrue(apply.contains("if (startIfStopped)"))
        assertTrue(apply.contains("if (!applied.state.canStop && applied.state != BaseService.State.Suspended) call(\"start\")"))
    }

    @Test fun notificationOnlyPreferencesAreAbsentButTrafficStatisticsRemainIndependent() {
        val settings = File("src/main/res/xml/global_preferences.xml").readText()
        for (key in listOf("showDirectSpeed", "speedInterval", "showGroupInNotification")) {
            assertFalse(settings.contains("app:key=\"$key\""))
            assertFalse(source("ui/DocsFragment.kt").contains("($key)"))
        }
        assertTrue(settings.contains("app:key=\"profileTrafficStatistics\""))
        assertFalse(source("ui/SettingsPreferenceFragment.kt").contains("profileTrafficStatistics.isEnabled"))
    }

    @Test fun activeRootObserverDoesNotOwnAnAndroidForegroundService() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("bg.RootTunService"))
        assertFalse(manifest.contains("bg.ProxyService"))
        assertFalse(manifest.contains("bg.VpnService"))
        val observer = source("bg/RootTunService.kt")
        assertFalse(observer.contains("startForeground("))
        assertTrue(observer.contains("Module observer does not own a foreground service"))
    }

    @Test fun subscriptionAndTestingContinueWithoutTrayPresentations() {
        val worker = source("bg/SubscriptionUpdater.kt")
        assertTrue(worker.contains("GroupUpdater.executeUpdate(profile, false)"))
        assertTrue(worker.contains("enqueueUniquePeriodicWork("))
        assertFalse(worker.contains("NotificationCompat"))
        val screen = source("ui/ConfigurationFragment.kt")
        assertFalse(screen.contains("ConnectionTestNotification"))
        assertFalse(File("src/main/java/moe/matsuri/nb4a/ui/ConnectionTestNotification.kt").exists())
        assertTrue(screen.contains("speedTestDialog?.show()"))
    }

    @Test fun upgradeClearsLegacyTrayEntriesAndSmartAutoStopOwnsPermissionRequest() {
        val app = source("SagerNet.kt")
        val clear = app.substringAfter("fun clearLegacyNotifications()").substringBefore("private fun moduleCommand")
        assertFalse(clear.contains("cancelAll("))
        assertTrue(clear.contains("notification.cancel(ServiceNotification.notificationId)"))
        assertFalse(clear.contains("1041"))
        assertFalse(clear.contains("smart_auto_stop"))
        assertTrue(clear.contains("notification.deleteNotificationChannel(channel)"))
        for (id in listOf("service-vpn", "service-proxy", "service-subscription", "connection-test")) {
            assertTrue(clear.contains("\"$id\""))
        }
        assertFalse(app.contains("createNotificationChannels("))
        assertFalse(source("ui/MainActivity.kt").contains("POST_NOTIFICATIONS"))
        assertFalse(app.contains("POST_NOTIFICATIONS"))
        assertTrue(File("src/main/AndroidManifest.xml").readText().contains("android.permission.POST_NOTIFICATIONS"))
        val settings = source("ui/SettingsPreferenceFragment.kt")
        val enableListener = settings.substringAfter("findPreference<SwitchPreference>(Key.SMART_AUTO_STOP_ENABLED)?.setOnPreferenceChangeListener")
            .substringBefore("findPreference<Preference>(\"smartAutoStopPermission\")")
        assertTrue(enableListener.contains("if (enabled == true)"))
        assertTrue(enableListener.contains("SmartAutoStopNotifications.availability(requireContext())"))
        assertTrue(enableListener.contains("NotificationGate.REQUEST_PERMISSION"))
        assertTrue(enableListener.contains("NotificationGate.OPEN_SETTINGS"))
        assertTrue(enableListener.contains("notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)"))
        val notifications = source("bg/SmartAutoStopNotifications.kt")
        assertTrue(notifications.contains("Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission"))
        assertEquals(1, Regex("notificationPermission\\.launch\\(").findAll(settings).count())
    }
}
