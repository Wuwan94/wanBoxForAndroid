// @author 雾晚
package io.nekohasekai.sagernet

import android.view.ContextThemeWrapper
import androidx.preference.PreferenceManager
import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sagernet.utils.Theme
import moe.matsuri.nb4a.ui.ExpandablePreferenceCategory
import org.junit.Assert.*
import org.junit.Test

/** Inflates real preferences and exercises nested advanced visibility. @author 雾晚 */
class SettingsHierarchyTest {
    @Test fun nestedFragmentAndMovedControlsRemainReachable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, Theme.getTheme())
            val manager = PreferenceManager(context)
            val screen = manager.inflateFromResource(context, R.xml.global_preferences, null)
            // @author 雾晚：四组重组后 categoryCore 已合并为 categoryAdvancedGroup
            val core = screen.findPreference<ExpandablePreferenceCategory>("categoryAdvancedGroup")!!
            val fragment = screen.findPreference<ExpandablePreferenceCategory>("categoryFragment")!!
            assertSame(core, fragment.parent)
            core.setExpanded(true)
            assertTrue(fragment.isVisible)
            fragment.setExpanded(true)
            assertTrue(screen.findPreference<androidx.preference.Preference>(Key.ENABLE_TLS_FRAGMENT)!!.isVisible)
            fragment.setExpanded(false)
            assertFalse(screen.findPreference<androidx.preference.Preference>(Key.ENABLE_TLS_FRAGMENT)!!.isVisible)
            core.setExpanded(false); assertFalse(fragment.isVisible)
            core.setExpanded(true); assertTrue(fragment.isVisible)
            assertFalse(screen.findPreference<androidx.preference.Preference>(Key.ENABLE_TLS_FRAGMENT)!!.isVisible)
            // @author 雾晚：四组重组后 categoryVPN 已合并为 categoryRuntime
            listOf(Key.PERFORMANCE_PRIORITY_MODE, Key.PROXY_APPS, Key.STRICT_ROUTE,
                Key.TUN_IMPLEMENTATION).forEach {
                assertEquals("categoryRuntime", screen.findPreference<androidx.preference.Preference>(it)!!.parent!!.key)
            }
            // Root-only manager delegates network lifecycle to the module; the
            // obsolete App wake/reset controls must stay absent. @author 雾晚
            listOf(Key.ACQUIRE_WAKE_LOCK, Key.WAKE_RESET_CONNECTIONS, Key.NETWORK_CHANGE_RESET_CONNECTIONS).forEach {
                assertNull(screen.findPreference<androidx.preference.Preference>(it))
            }
            // @author 雾晚：Codex 重设计已删除 HIDE_FROM_RECENT_APPS（从任务中隐藏）
            core.setExpanded(false)
        }
    }
}
