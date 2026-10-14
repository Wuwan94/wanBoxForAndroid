// @author 雾晚
package io.nekohasekai.sagernet

import android.view.ContextThemeWrapper
import androidx.preference.PreferenceManager
import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.utils.Theme
import moe.matsuri.nb4a.ui.ExpandablePreferenceCategory
import org.junit.Assert.*
import org.junit.Test

/** Inflates real preferences and exercises nested HTTP/TLS fragment visibility and 8-group hierarchy. @author 雾晚 */
class SettingsHierarchyTest {
    @Test fun nestedFragmentAndMovedControlsRemainReachable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, Theme.getTheme())
            val manager = PreferenceManager(context)
            val screen = manager.inflateFromResource(context, R.xml.global_preferences, null)
            // @author 雾晚：八组收拢后取消独立 categoryAdvancedGroup，categoryFragment 归属 categoryHttpTls
            assertNull(screen.findPreference<ExpandablePreferenceCategory>("categoryAdvancedGroup"))
            val httpTls = screen.findPreference<ExpandablePreferenceCategory>("categoryHttpTls")!!
            val fragment = screen.findPreference<ExpandablePreferenceCategory>("categoryFragment")!!
            assertSame(httpTls, fragment.parent)
            httpTls.setExpanded(true)
            assertTrue(fragment.isVisible)
            fragment.setExpanded(true)
            assertTrue(screen.findPreference<androidx.preference.Preference>(Key.ENABLE_TLS_FRAGMENT)!!.isVisible)
            fragment.setExpanded(false)
            assertFalse(screen.findPreference<androidx.preference.Preference>(Key.ENABLE_TLS_FRAGMENT)!!.isVisible)
            httpTls.setExpanded(false); assertFalse(fragment.isVisible)
            httpTls.setExpanded(true); assertTrue(fragment.isVisible)
            assertFalse(screen.findPreference<androidx.preference.Preference>(Key.ENABLE_TLS_FRAGMENT)!!.isVisible)
            // @author 雾晚：八组收拢下保留项归属对应分组
            listOf(
                "perAppProxyEntry",
                Key.PERFORMANCE_PRIORITY_MODE,
                Key.PROXY_APPS,
                Key.STRICT_ROUTE,
                Key.TUN_IMPLEMENTATION,
                Key.DUAL_NETWORK_ACCELERATION
            ).forEach {
                assertEquals("categoryRuntime", screen.findPreference<androidx.preference.Preference>(it)!!.parent!!.key)
            }
            listOf(Key.REMOTE_DNS, Key.DIRECT_DNS, Key.ENABLE_FAKEDNS).forEach {
                assertEquals("categoryDns", screen.findPreference<androidx.preference.Preference>(it)!!.parent!!.key)
            }
            assertEquals(
                "categoryOther",
                screen.findPreference<androidx.preference.Preference>(Key.GLOBAL_CUSTOM_CONFIG)!!.parent!!.key
            )
            // Root-only manager delegates network lifecycle to the module; obsolete and authorized removed UI controls stay absent. @author 雾晚
            listOf(
                Key.ACQUIRE_WAKE_LOCK,
                Key.WAKE_RESET_CONNECTIONS,
                Key.NETWORK_CHANGE_RESET_CONNECTIONS,
                Key.SERVICE_MODE,
                Key.DISABLE_MIXED_INBOUND,
                Key.ENABLE_CLASH_API,
                Key.HIDE_FROM_RECENT_APPS,
                Key.PERSIST_ACROSS_REBOOT,
                "clashApiFixed"
            ).forEach {
                assertNull(screen.findPreference<androidx.preference.Preference>(it))
            }
            // 底层单一 Root 模式与默认 TLS 证书校验契约保持不变
            assertEquals(Key.MODE_ROOT, DataStore.serviceMode)
            assertFalse(DataStore.globalAllowInsecure)
            httpTls.setExpanded(false)
        }
    }
}
