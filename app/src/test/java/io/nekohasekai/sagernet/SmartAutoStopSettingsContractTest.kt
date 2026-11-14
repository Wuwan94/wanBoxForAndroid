package io.nekohasekai.sagernet

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Android adapter contract; controller behavior is tested through its public seam. */
class SmartAutoStopSettingsContractTest {
    @Test fun configurationFailureUsesOnlyASafeActivityDialog() {
        val source = File("src/main/java/io/nekohasekai/sagernet/ui/SettingsPreferenceFragment.kt").readText()
        val apply = source.substringAfter("if (key == Key.SMART_AUTO_STOP_RULES || key == Key.SMART_AUTO_STOP_ENABLED)")
            .substringBefore("if (key == Key.PROFILE_CARD_STYLE)")
        assertTrue(apply.contains("RootModuleClient.startOrReload(startIfStopped = false)"))
        assertTrue(apply.contains("catch (e: kotlinx.coroutines.CancellationException) { throw e }"))
        val failure = apply.substringAfter("catch (e: Exception)")
        assertTrue(failure.contains("val activity = activity"))
        assertTrue(failure.contains("activity != null && !activity.isFinishing && !activity.isDestroyed"))
        assertTrue(failure.contains("activity.alert(io.nekohasekai.sagernet.bg.RootModuleErrors.message(activity, e)).tryToShow()"))
        assertFalse(failure.contains("requireContext()"))
        assertFalse(failure.contains("Toast"))
    }
}
