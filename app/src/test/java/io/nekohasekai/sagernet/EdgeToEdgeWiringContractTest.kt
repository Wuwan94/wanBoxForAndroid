package io.nekohasekai.sagernet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EdgeToEdgeWiringContractTest {

    @Test
    fun manifestEnablesAndroidXBackInvocationByDefault() {
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertTrue(manifest.contains("android:enableOnBackInvokedCallback=\"true\""))
    }

    @Test
    fun themedActivityUsesTheSharedRootSafeAreaPolicy() {
        val activity = File("src/main/java/io/nekohasekai/sagernet/ui/ThemedActivity.kt").readText()
        val policy = File("src/main/java/io/nekohasekai/sagernet/widget/WindowInsetsPolicy.kt").readText()
        val onCreate = activity
            .substringAfter("override fun onCreate(savedInstanceState: Bundle?) {")
            .substringBefore("\n    }")
        val contentViewOverrides = activity
            .substringAfter("override fun setContentView(view: View?) {")
            .substringBefore("override fun setTheme(resId: Int)")

        assertTrue(activity.contains("WindowInsetsPolicy.configureWindow(window)"))
        assertTrue(activity.contains("contentContainer.getChildAt(0)"))
        assertTrue(activity.contains("WindowInsetsPolicy.applySafeAreaPadding(contentRoot)"))
        assertTrue(activity.contains("android.R.id.content"))
        assertTrue(onCreate.contains("configureWindowInsets()"))
        assertTrue(contentViewOverrides.contains("applyContentInsets()"))
        assertTrue(policy.contains("WindowCompat.setDecorFitsSystemWindows(window, false)"))
    }

    @Test
    fun floatingThemedDialogKeepsItsWindowBoundsAndSkipsRootInsets() {
        val activity = File("src/main/java/io/nekohasekai/sagernet/ui/ThemedActivity.kt").readText()
        val switchActivity = File("src/main/java/io/nekohasekai/sagernet/ui/SwitchActivity.kt").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val themes = File("src/main/res/values/themes.xml").readText()
        val nodeDialog = File("src/main/java/io/nekohasekai/sagernet/ui/NodeSelectDialogActivity.kt").readText()

        assertTrue(switchActivity.contains("override val isDialog = true"))
        assertTrue(manifest.contains("android:name=\"io.nekohasekai.sagernet.ui.SwitchActivity\""))
        assertTrue(manifest.contains("android:theme=\"@style/Theme.SagerNet.Dialog\""))
        assertTrue(themes.contains("name=\"Theme.SagerNet.Dialog\" parent=\"Theme.Material3.DayNight.Dialog.Alert\""))

        val windowInsetsSetup = activity
            .substringAfter("private fun configureWindowInsets() {")
            .substringBefore("\n    }")
        assertTrue(windowInsetsSetup.trimStart().startsWith("if (isDialog) return"))
        assertTrue(windowInsetsSetup.contains("WindowInsetsPolicy.configureWindow(window)"))

        val rootInsetsSetup = activity
            .substringAfter("private fun applyContentInsets() {")
            .substringBefore("\n    }")
        assertTrue(rootInsetsSetup.trimStart().startsWith("if (isDialog) return"))
        assertTrue(rootInsetsSetup.contains("WindowInsetsPolicy.applySafeAreaPadding(contentRoot)"))
        assertTrue(nodeDialog.contains("WindowInsetsPolicy.configureWindow(window, lightSystemBars = !Theme.usingNightMode())"))
        assertTrue(nodeDialog.contains("WindowInsetsPolicy.applySafeAreaPadding(binding.dialogBackdrop)"))
    }

    @Test
    fun platformBackOverridesUseAndroidXDispatcherCallbacks() {
        val activitySources = listOf(
            "src/main/java/io/nekohasekai/sagernet/ui/profile/ProfileSettingsActivity.kt",
            "src/main/java/io/nekohasekai/sagernet/ui/profile/ConfigEditActivity.kt",
            "src/main/java/io/nekohasekai/sagernet/ui/GroupSettingsActivity.kt",
            "src/main/java/io/nekohasekai/sagernet/ui/RouteSettingsActivity.kt",
            "src/main/java/io/nekohasekai/sagernet/ui/GroupEditorActivity.kt",
            "src/main/java/io/nekohasekai/sagernet/ui/profile/BalancerPriorityActivity.kt",
        ).map { File(it).readText() }

        activitySources.forEach { source ->
            assertFalse(source.contains("override fun onBackPressed()"))
            assertTrue(source.contains("OnBackPressedCallback"))
            assertTrue(source.contains("onBackPressedDispatcher.addCallback"))
        }
    }

    @Test
    fun assetsActivityLeavesBackNavigationToTheDefaultDispatcher() {
        val activity = File("src/main/java/io/nekohasekai/sagernet/ui/AssetsActivity.kt").readText()

        assertFalse(activity.contains("OnBackPressedCallback"))
        assertFalse(activity.contains("override fun onBackPressed()"))
        assertTrue(activity.contains("onBackPressedDispatcher.onBackPressed()"))
    }

    @Test
    fun balancerToolbarHomeUsesDispatcherAndStillReturnsItsResult() {
        val activity = File(
            "src/main/java/io/nekohasekai/sagernet/ui/profile/BalancerPriorityActivity.kt"
        ).readText()
        val homeHandler = activity
            .substringAfter("override fun onOptionsItemSelected(item: MenuItem): Boolean {")
            .substringBefore("\n    private fun tierLabel")

        assertTrue(activity.contains("override fun handleOnBackPressed() = finishWithResult()"))
        assertTrue(homeHandler.contains("onBackPressedDispatcher.onBackPressed()"))
        assertFalse(homeHandler.contains("finishWithResult()"))
        assertTrue(activity.contains("putExtra(EXTRA_PRIORITY_ORDER, orderedIds.toLongArray())"))
        assertTrue(activity.contains("putExtra(EXTRA_EXCLUDED_IDS, excludedIds.toLongArray())"))
        assertTrue(activity.contains("putExtra(EXTRA_LOOSE_IDS, looseIds.toLongArray())"))
    }

    @Test
    fun landingIpBottomSheetAppliesOnlyHorizontalAndBottomInsets() {
        val bottomSheet = File(
            "src/main/java/io/nekohasekai/sagernet/widget/LandingIpBottomSheet.kt"
        ).readText()

        assertTrue(
            bottomSheet.contains("WindowInsetsPolicy.applySafeAreaPadding(view, includeTop = false)")
        )
    }
}
