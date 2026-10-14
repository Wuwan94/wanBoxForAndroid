// @author 雾晚
package io.nekohasekai.sagernet


import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element


/** Snapshot covers every legacy key/type/default/dependency, independently of UI ordering. @author 雾晚 */
class SettingsHierarchyContractTest {
    private val moduleInapplicable = setOf("showGroupInNotification", "httpProxyBypass", "meteredNetwork",
        "acquireWakeLock", "wakeResetConnections", "networkChangeResetConnections", "showDirectSpeed", "speedInterval",
        "appTheme", "showBottomBar", "showAllGroupsTab")
    // @author 雾晚：明确授权移除的 UI 项（Root 精简），key 保留迁移兼容
    private val authorizedUiRemovals = setOf(
        "enableClashAPI", "hideFromRecentApps", "isAutoConnect",
        "serviceMode", "disableMixedInbound", "clashApiFixed"
    )
    @Test fun everyOldKeyKeepsItsValueContractAndControlType() {
        val old = JSONObject(javaClass.getResource("/settings-before-3.0.7.json")!!.readText())
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(File("src/main/res/xml/global_preferences.xml"))
        val elements = document.getElementsByTagName("*")
        val actual = mutableMapOf<String, Element>()
        for (i in 0 until elements.length) {
            val element = elements.item(i) as Element
            val key = element.getAttributeNS("http://schemas.android.com/apk/res-auto", "key")
            if (key.isNotEmpty()) { assertNull("Duplicate key $key", actual.put(key, element)) }
        }
        // @author 雾晚: input-method routing is an ordinary rule, not a separate preference.
        // @author 雾晚：四组重组，分组 key（category*）是 UI 容器，不参与契约对比；允许新增 key（如 About 项）
        val oldPrefs = old.keySet() - moduleInapplicable - authorizedUiRemovals - old.keySet().filter { it.startsWith("category") }.toSet()
        val actualPrefs = actual.keys - actual.keys.filter { it.startsWith("category") }.toSet()
        assertTrue("Missing keys: ${oldPrefs - actualPrefs}", actualPrefs.containsAll(oldPrefs))
        assertFalse(actual.containsKey("rootModuleUpdateData"))
        old.keys().forEach { key ->
            if (key in moduleInapplicable) return@forEach
            if (key in authorizedUiRemovals) return@forEach
            // @author 雾晚：跳过旧分组容器 key，它们已被新分组替代
            if (key.startsWith("category")) return@forEach
            val before = old.getJSONObject(key); val node = actual.getValue(key)
            if (key == "tunImplementation") {
                assertEquals("Preference", node.tagName)
                assertEquals("false", node.getAttributeNS("http://schemas.android.com/apk/res-auto", "selectable"))
                return@forEach
            }
            assertEquals(key, before.getString("type"), node.tagName)
            val attributes = before.getJSONObject("attributes")
            attributes.keys().forEach { attribute ->
                val name = attribute.substringAfter('}')
                if (name !in setOf("title", "summary")) {
                    assertEquals("$key/$name", attributes.getString(attribute),
                        node.getAttributeNS(attribute.substringAfter('{').substringBefore('}'), name))
                }
            }
        }
        // @author 雾晚：八组收拢后取消独立高级设置分组，categoryFragment 归属 categoryHttpTls
        assertFalse(actual.containsKey("categoryAdvancedGroup"))
        assertEquals("categoryHttpTls", (actual.getValue("categoryFragment").parentNode as Element)
            .getAttributeNS("http://schemas.android.com/apk/res-auto", "key"))
    }


    @Test fun removedModuleControlsKeepHistoricalStorageWithoutClickableBindings() {
        val fragment = File("src/main/java/io/nekohasekai/sagernet/ui/SettingsPreferenceFragment.kt").readText()
        val store = File("src/main/java/io/nekohasekai/sagernet/database/DataStore.kt").readText()
        val docs = File("src/main/java/io/nekohasekai/sagernet/ui/DocsFragment.kt").readText()
        moduleInapplicable.forEach { key ->
            assertTrue("Legacy backup value removed: $key", store.contains("var $key "))
            assertFalse("Obsolete help entry: $key", docs.contains("($key)"))
        }
        for (key in listOf("ACQUIRE_WAKE_LOCK", "WAKE_RESET_CONNECTIONS", "NETWORK_CHANGE_RESET_CONNECTIONS",
            "METERED_NETWORK", "HTTP_PROXY_BYPASS")) assertFalse(fragment.contains("findPreference<Preference>(Key.$key)"))
        assertFalse(fragment.contains("val acquireWakeLock"))
        assertFalse(fragment.contains("val httpProxyBypass"))
    }

    @Test fun authorizedUiRemovalsArePurgedFromXmlAndFragmentWhileKeepingDataStoreContract() {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(File("src/main/res/xml/global_preferences.xml"))
        val elements = document.getElementsByTagName("*")
        val xmlKeys = mutableSetOf<String>()
        for (i in 0 until elements.length) {
            val element = elements.item(i) as Element
            val key = element.getAttributeNS("http://schemas.android.com/apk/res-auto", "key")
            if (key.isNotEmpty()) xmlKeys.add(key)
        }
        for (key in authorizedUiRemovals) {
            assertFalse("global_preferences.xml must not declare removed key: $key", xmlKeys.contains(key))
        }

        val fragment = File("src/main/java/io/nekohasekai/sagernet/ui/SettingsPreferenceFragment.kt").readText()
        for (keyName in listOf("PERSIST_ACROSS_REBOOT", "ENABLE_CLASH_API", "HIDE_FROM_RECENT_APPS")) {
            assertFalse("Fragment must not look up Key.$keyName", fragment.contains("findPreference<SwitchPreference>(Key.$keyName)"))
            assertFalse("Fragment must not reference Key.$keyName", fragment.contains("Key.$keyName"))
        }
        assertFalse(fragment.contains("val autoStart"))
        assertFalse(fragment.contains("val enableClashAPI"))
        assertFalse(fragment.contains("val hideFromRecentApps"))

        val constants = File("src/main/java/io/nekohasekai/sagernet/Constants.kt").readText()
        assertTrue("Constants must map PERSIST_ACROSS_REBOOT to isAutoConnect",
            constants.contains("""const val PERSIST_ACROSS_REBOOT = "isAutoConnect""""))
        assertTrue("Constants must map ENABLE_CLASH_API to enableClashAPI",
            constants.contains("""const val ENABLE_CLASH_API = "enableClashAPI""""))
        assertTrue("Constants must map HIDE_FROM_RECENT_APPS to hideFromRecentApps",
            constants.contains("""const val HIDE_FROM_RECENT_APPS = "hideFromRecentApps""""))

        val store = File("src/main/java/io/nekohasekai/sagernet/database/DataStore.kt").readText()
        assertTrue("DataStore must keep persistAcrossReboot delegation",
            store.contains("val persistAcrossReboot by configurationStore.boolean(Key.PERSIST_ACROSS_REBOOT) { false }"))
        assertTrue("DataStore must keep enableClashAPI delegation",
            store.contains("var enableClashAPI by configurationStore.boolean(Key.ENABLE_CLASH_API) { true }"))
        assertTrue("DataStore must keep hideFromRecentApps delegation",
            store.contains("var hideFromRecentApps by configurationStore.boolean(Key.HIDE_FROM_RECENT_APPS)"))
    }

    @Test
    fun eightGroupHierarchyAndRetainedSettingsArePresent() {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(File("src/main/res/xml/global_preferences.xml"))
        val root = document.documentElement
        val topCategoryKeys = mutableListOf<String>()
        val childNodes = root.childNodes
        for (i in 0 until childNodes.length) {
            val node = childNodes.item(i)
            if (node is Element && node.tagName == "moe.matsuri.nb4a.ui.ExpandablePreferenceCategory") {
                topCategoryKeys.add(node.getAttributeNS("http://schemas.android.com/apk/res-auto", "key"))
            }
        }
        assertEquals(
            listOf(
                "categoryRuntime",
                "categoryHttpTls",
                "categoryDns",
                "categoryRouting",
                "categorySpeedTest",
                "categoryAppearance",
                "categoryLogsDiagnostics",
                "categoryOther"
            ),
            topCategoryKeys
        )

        val elements = document.getElementsByTagName("*")
        val allKeys = mutableSetOf<String>()
        for (i in 0 until elements.length) {
            val element = elements.item(i) as Element
            val key = element.getAttributeNS("http://schemas.android.com/apk/res-auto", "key")
            if (key.isNotEmpty()) allKeys.add(key)
        }
        for (retained in listOf("perAppProxyEntry", "proxyApps", "enableFakeDns", "dualNetworkAcceleration", "globalCustomConfig")) {
            assertTrue("Retained preference key missing: $retained", allKeys.contains(retained))
        }
    }
}
