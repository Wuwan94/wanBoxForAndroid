package io.nekohasekai.sagernet

import android.content.Intent
import android.graphics.Bitmap
import android.widget.*
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.ui.*
import io.nekohasekai.sagernet.utils.BackupHelper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ReferenceUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun allViews(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { allViews(view.getChildAt(it)) } else emptyList()
    private fun waitFor(condition: () -> Boolean) {
        val limit = System.currentTimeMillis() + 10000
        while (System.currentTimeMillis() < limit) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) { instrumentation.waitForIdleSync(); return }
            Thread.sleep(50)
        }
        fail("UI did not reach expected state")
    }
    private fun screenshot(name: String, main: MainActivity) {
        // Allow pending layout and compositor frames to reflect the asserted UI state.
        instrumentation.waitForIdleSync()
        Thread.sleep(250)
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            val fragment = main.supportFragmentManager.findFragmentById(R.id.fragment_holder) as ToolbarFragment
            val insets = checkNotNull(main.window.decorView.rootWindowInsets)
            val bars = insets.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout())
            val position = IntArray(2)
            fragment.toolbar.getLocationOnScreen(position)
            assertTrue("$name toolbar must remain below the status bar", position[1] >= bars.top)
        }
        val root = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
            ?: instrumentation.targetContext.getExternalFilesDir(null)!!
        val directory = File(root, "reference-ui").apply { mkdirs() }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val colors = mutableSetOf<Int>()
        for (x in 0 until bitmap.width step 16) for (y in 0 until bitmap.height step 16) colors.add(bitmap.getPixel(x, y))
        assertTrue("Screenshot must contain rendered content", colors.size > 12)
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun fourScreensUseRealDataAndDisplayControlsPersist() = runBlocking {
        val original = BackupRestore.parse(PortableBackup.document(BackupHelper.doBackup()))
        val oldColumns = DataStore.nodeColumnCount
        val oldMode = DataStore.dashboardMode
        var activity: MainActivity? = null
        try {
            val groups = (1..6).map { index -> ProxyGroup(id = 98000L + index,
                name = "订阅 " + ('A' + index - 1), type = GroupType.SUBSCRIPTION,
                subscription = SubscriptionBean().apply {
                    initializeDefaultValues(); link = "https://example.invalid/$index"; autoUpdate = false
                    lastUpdated = (System.currentTimeMillis() / 1000).toInt()
                }) }
            val names = listOf("日本 01", "香港 02", "新加坡 01", "台湾 01", "日本 02", "新加坡 02",
                "香港 03", "日本 03", "新加坡 03", "香港 04", "日本 04", "台湾 02")
            val profiles = names.mapIndexed { index, name ->
                ProxyEntity(id = 98100L + index, groupId = groups[index / 2].id, ping = 28 + index * 3,
                    status = 1, userOrder = index.toLong()).putBean(ShadowsocksBean().apply {
                        initializeDefaultValues(); this.name = name; serverAddress = "192.0.2.1"; serverPort = 443
                    })
            }
            BackupRestore.apply(BackupRestore.Plan(profiles, groups, emptyList(), null), true, true, false)
            DataStore.selectedProxy = profiles.first().id
            DataStore.nodeColumnCount = 2
            activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val main = activity
            waitFor { main.findViewById<TextView>(R.id.nodeName)?.text?.toString() == names.first() }
            screenshot("01-dashboard", main)
            instrumentation.runOnMainSync {
                main.findViewById<View>(R.id.modeDirect).performClick()
            }
            waitFor { DataStore.dashboardMode == 2 }
            instrumentation.runOnMainSync {
                main.binding.bottomNav.selectedItemId = R.id.nav_nodes
                main.supportFragmentManager.executePendingTransactions()
            }
            waitFor { allViews(main.binding.fragmentHolder).filterIsInstance<TextView>().any { it.text.toString() == "日本 01" } }
            instrumentation.runOnMainSync {
                val fragment = main.supportFragmentManager.findFragmentById(R.id.fragment_holder) as NodesFragment
                assertEquals("6 个订阅 · 12 个节点", fragment.toolbar.subtitle)
                fragment.toolbar.findViewById<View>(R.id.action_display_settings).performClick()
            }
            instrumentation.waitForIdleSync()
            screenshot("02-nodes", main)
            instrumentation.runOnMainSync {
                val choices = allViews(main.binding.fragmentHolder).filterIsInstance<RadioButton>()
                choices.first { it.text.toString() == "多列" }.performClick()
                val recycler = allViews(main.binding.fragmentHolder).filterIsInstance<RecyclerView>().single()
                assertEquals(3, (recycler.layoutManager as GridLayoutManager).spanCount)
                assertEquals(3, DataStore.nodeColumnCount)
                choices.first { it.text.toString() == "双列" }.performClick()
                assertEquals(2, (recycler.layoutManager as GridLayoutManager).spanCount)
                main.binding.bottomNav.selectedItemId = R.id.nav_subscriptions
                main.supportFragmentManager.executePendingTransactions()
            }
            waitFor { allViews(main.binding.fragmentHolder).filterIsInstance<CheckBox>().size == 6 }
            instrumentation.runOnMainSync {
                val checks = allViews(main.binding.fragmentHolder).filterIsInstance<CheckBox>()
                checks.take(2).forEach { it.performClick() }
                val selected = main.findViewById<Button>(R.id.action_update_selected)
                assertTrue(selected.isEnabled)
                assertEquals("更新选中 (2)", selected.text.toString())
            }
            screenshot("03-subscriptions", main)
            instrumentation.runOnMainSync {
                main.binding.bottomNav.selectedItemId = R.id.nav_settings
                main.supportFragmentManager.executePendingTransactions()
            }
            waitFor { allViews(main.binding.fragmentHolder).filterIsInstance<TextView>().any { it.text.toString() == "关于 wanBox" } }
            screenshot("04-settings", main)
            instrumentation.runOnMainSync {
                val dns = allViews(main.binding.fragmentHolder).filterIsInstance<TextView>().first { it.text.toString() == "DNS 设置" }
                (dns.parent.parent as View).performClick()
                main.supportFragmentManager.executePendingTransactions()
            }
            waitFor { main.supportFragmentManager.findFragmentById(R.id.fragment_holder) is ReferenceSettingsFragment }
            instrumentation.runOnMainSync { main.onBackPressedDispatcher.onBackPressed() }
            waitFor { main.supportFragmentManager.findFragmentById(R.id.fragment_holder) is SettingsFragment }
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            instrumentation.waitForIdleSync()
            BackupRestore.apply(original, true, true, true)
            DataStore.nodeColumnCount = oldColumns
            DataStore.dashboardMode = oldMode
        }
    }
}
