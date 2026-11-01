// @author 雾晚
package io.nekohasekai.sagernet

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** preview.29 UI 精简：设置子页按 key 平铺、瘦管理器隐藏依赖内嵌核心的入口。 @author 雾晚 */
class UiCleanupContractTest {
    private fun source(path: String) = File("src/main/$path").readText()

    @Test fun everySettingsSectionKeyExistsInPreferenceXml() {
        val fragment = source("java/io/nekohasekai/sagernet/ui/SettingsPreferenceFragment.kt")
        val block = fragment.substringAfter("private val sectionKeys").substringBefore("class ListSummaryProvider")
        val xmlKeys = Regex("app:key=\"([^\"]+)\"").findAll(source("res/xml/global_preferences.xml"))
            .map { it.groupValues[1] }.toSet()
        val sectionNames = setOf("appearance", "root", "dns", "network")
        val keys = Regex("\"([A-Za-z0-9_]+)\"").findAll(block).map { it.groupValues[1] }
            .filter { it !in sectionNames }.toList()
        assertTrue(keys.isNotEmpty())
        keys.forEach { assertTrue("section key missing from XML: $it", it in xmlKeys) }
        // 旧实现按已不存在的分组 key 过滤，导致子页空白
        assertFalse(fragment.contains("setOf(\"categoryDNS\")"))
    }

    @Test fun coreOnlyEntriesAreHiddenInThinManager() {
        val nodes = source("java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt")
        assertTrue(nodes.contains("findItem(R.id.action_connection_url_test)?.isVisible = false"))
        assertTrue(nodes.contains("findItem(R.id.action_speed_test_group)?.isVisible = false"))
        assertTrue(nodes.contains("popup.menu.removeItem(R.id.action_test_profile_speed)"))
        assertTrue(source("java/io/nekohasekai/sagernet/ui/NetworkFragment.kt")
            .contains("binding.stunCard.isVisible = CoreRuntime.available"))
        val drawer = source("res/menu/main_drawer_menu.xml")
        assertFalse(drawer.contains("nav_faq"))
        assertFalse(drawer.contains("nav_dashboard"))
    }

    @Test fun subscriptionSelectionCanExitAndSingleGroupTabsAreHidden() {
        val groups = source("java/io/nekohasekai/sagernet/ui/GroupFragment.kt")
        val setSelectionMode = groups.substringAfter("fun setSelectionMode(enabled: Boolean)")
            .substringBefore("fun toggleGroupSelection")
        assertTrue(setSelectionMode.contains("selectionMode = enabled"))
        assertFalse(setSelectionMode.contains("selectionMode = true"))
        assertTrue(groups.contains("fragment.selectionMode && group.type == GroupType.SUBSCRIPTION"))

        val nodes = source("java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt")
        assertTrue(nodes.contains("val hideTab = realGroupCount < 2"))
        assertTrue(nodes.contains("tabLayout.isGone = hideTab"))
        assertTrue(nodes.contains("tabLayout.isGone = groupList.count { it.id != ALL_GROUPS_SENTINEL_ID } < 2"))
    }

    @Test fun networkDiagnosticsDoNotClaimDnsSafetyFromTraceReachability() {
        val network = source("java/io/nekohasekai/sagernet/ui/NetworkFragment.kt")
        assertTrue(network.contains("viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO)"))
        assertTrue(network.contains("it.startsWith(\"198.18.\") || it.startsWith(\"198.19.\")"))
        assertTrue(network.contains("不代表无 DNS 泄漏"))
        assertFalse(network.contains("getString(R.string.dns_safe)"))
        val chineseStrings = source("res/values-zh-rCN/strings.xml")
        assertTrue(chineseStrings.contains("不等同于完整 DNS 泄漏检测"))
        assertFalse(chineseStrings.contains("DNS 解析安全（无泄漏）"))
    }

    @Test fun cancelledSubscriptionRequestsAreNotRetriedOrSentDirectly() {
        val updater = source("java/io/nekohasekai/sagernet/group/RawUpdater.kt")
        val cancellationCatch = updater.indexOf("catch (cancelled: kotlinx.coroutines.CancellationException)")
        val proxyFallbackCatch = updater.indexOf("catch (proxyError: Throwable)")
        val uaLoopCancellationCatch = updater.lastIndexOf("catch (cancelled: kotlinx.coroutines.CancellationException)")
        val uaLoopGenericCatch = updater.indexOf("catch (e: Throwable)", uaLoopCancellationCatch)
        assertTrue(cancellationCatch >= 0 && cancellationCatch < proxyFallbackCatch)
        assertTrue(uaLoopCancellationCatch >= 0 && uaLoopCancellationCatch < uaLoopGenericCatch)
        assertTrue(updater.contains("withTimeoutOrNull(60_000L)"))
        assertTrue(updater.contains("request.executeCancellable()"))

        val http = source("java/io/nekohasekai/sagernet/core/WanHttp.kt")
        assertTrue(http.contains("continuation.invokeOnCancellation { call.cancel() }"))
        assertTrue(http.contains("callTimeout?.let { (timeout, unit) -> builder.callTimeout(timeout, unit) }"))
        val groupUpdater = source("java/io/nekohasekai/sagernet/group/GroupUpdater.kt")
        assertTrue(groupUpdater.contains("catch (cancelled: CancellationException)"))
        assertTrue(groupUpdater.contains("finally {\n                    finishUpdate(proxyGroup)"))
        val batchUi = source("java/io/nekohasekai/sagernet/ui/GroupFragment.kt")
        assertTrue(batchUi.contains("catch (cancelled: CancellationException)"))
    }
}
