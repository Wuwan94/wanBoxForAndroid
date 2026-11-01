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
}
