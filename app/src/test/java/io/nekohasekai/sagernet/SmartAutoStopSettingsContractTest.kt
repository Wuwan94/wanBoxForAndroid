package io.nekohasekai.sagernet

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Android adapter contract; controller behavior is tested through its public seam. */
class SmartAutoStopSettingsContractTest {
    @Test fun configurationApplyUsesWarningAwareReload() {
        val source = File("src/main/java/io/nekohasekai/sagernet/ui/SettingsPreferenceFragment.kt").readText()
        val apply = source.substringAfter("if (key == Key.SMART_AUTO_STOP_RULES || key == Key.SMART_AUTO_STOP_ENABLED)")
            .substringBefore("if (key == Key.PROFILE_CARD_STYLE)")
        assertTrue(apply.contains("SagerNet.reloadService { error ->"))
        assertTrue(apply.contains("runOnMainDispatcher"))
        assertTrue(apply.contains("val activity = activity"))
        assertTrue(apply.contains("activity != null && !activity.isFinishing && !activity.isDestroyed"))
        assertTrue(apply.contains("activity.alert(io.nekohasekai.sagernet.bg.SmartAutoStopApplyErrors.message(activity, error)).tryToShow()"))
        assertFalse(apply.contains("requireActivity("))
        assertFalse(apply.contains("requireContext("))
        val application = File("src/main/java/io/nekohasekai/sagernet/SagerNet.kt").readText()
        val overload = application.substringAfter("fun reloadService(onError: (Exception) -> Unit)")
            .substringBefore("fun restartService")
        assertTrue(overload.contains("configurationReload.request(onError ="))
        assertTrue(overload.contains("OutboundReferenceWarningRequired"))
        assertTrue(overload.contains("reportModuleError(error)"))
        assertTrue(overload.contains("onError(error)"))
        assertFalse(apply.contains("RootModuleClient.startOrReload(startIfStopped = false)"))
    }
}
