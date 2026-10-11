package io.nekohasekai.sagernet

import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.widget.CheckBox
import android.widget.RadioGroup
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Run on a clean emulator; fixtures use reserved invalid hosts, never real subscriptions. */
class MinimalUiTest {
    @Test fun navigationSelectionsDisplayControlsAndSettingsWork() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val ids = ArrayList<Long>()
        val oldSelected = DataStore.selectedProxy
        val oldGroup = DataStore.selectedGroup
        val oldLayout = DataStore.groupLayoutMode
        val oldColumns = DataStore.nodeColumnCount
        val oldInfo = DataStore.showSubscriptionInfoCard
        var activity: MainActivity? = null
        val output = File(context.getExternalFilesDir(null), "minimal-ui").apply { mkdirs() }
        try {
            runBlocking {
                for (index in 0 until 6) {
                    val subscription = SubscriptionBean().apply {
                        initializeDefaultValues()
                        link = "https://ui-fixture.invalid/${index}"
                        lastUpdated = (System.currentTimeMillis() / 1000 - index * 600).toInt()
                        autoUpdate = false
                    }
                    val group = ProxyGroup(name = "订阅 ${('A'.code + index).toChar()}", type = GroupType.SUBSCRIPTION, subscription = subscription, userOrder = 1000L + index)
                    group.id = SagerDatabase.groupDao.createGroup(group)
                    ids.add(group.id)
                    repeat(2) { node ->
                        val names = listOf("日本 01", "香港 02", "新加坡 01", "台湾 01", "日本 02", "新加坡 02")
                        val bean = ShadowsocksBean().apply {
                            initializeDefaultValues(); name = names[(index * 2 + node) % names.size]
                            serverAddress = "node.invalid"; serverPort = 443
                            method = "aes-128-gcm"; password = "public-ui-fixture"
                        }
                        val profile = ProfileManager.createProfile(group.id, bean)
                        profile.status = 1; profile.ping = 28 + index * 7 + node * 14
                        ProfileManager.updateProfile(profile)
                        if (index == 0 && node == 0) DataStore.selectedProxy = profile.id
                    }
                }
            }
            DataStore.selectedGroup = ids.first()
            DataStore.groupLayoutMode = 1; DataStore.nodeColumnCount = 2
            DataStore.showSubscriptionInfoCard = false
            instrumentation.runOnMainSync { io.nekohasekai.sagernet.utils.AppLocale.apply("zh-CN") }
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val host = activity!!
            fun idle() { instrumentation.waitForIdleSync() }
            fun awaitReady(predicate: () -> Boolean) {
                repeat(100) {
                    var ready = false
                    instrumentation.runOnMainSync { ready = predicate() }
                    if (ready) return
                    Thread.sleep(100)
                }
                fail("Screen did not become ready")
            }
            fun screenshot(name: String) {
                idle()
                instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                    File(output, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    bitmap.recycle()
                }
            }
            awaitReady { host.findViewById<TextView>(R.id.nodeName)?.text.toString() == "日本 01" }
            screenshot("01-dashboard")
            instrumentation.runOnMainSync { host.binding.bottomNav.selectedItemId = R.id.nav_nodes }
            awaitReady {
                val fragment = host.supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ConfigurationFragment
                fragment?.isToolbarInitialized == true && fragment.adapter.groupList.isNotEmpty()
            }
            instrumentation.runOnMainSync {
                val fragment = host.supportFragmentManager.findFragmentById(R.id.fragment_holder) as ConfigurationFragment
                val all = fragment.adapter.groupList.indexOfFirst { it.id == ConfigurationFragment.ALL_GROUPS_SENTINEL_ID }
                if (all >= 0) fragment.groupPager.setCurrentItem(all, false)
                fragment.toolbar.menu.findItem(R.id.action_display_settings).let { fragment.toolbar.menu.performIdentifierAction(it.itemId, 0) }
            }
            awaitReady {
                val fragment = host.supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ConfigurationFragment
                host.findViewById<View>(R.id.node_display_panel)?.visibility == View.VISIBLE &&
                    fragment?.adapter?.groupFragments?.get(ConfigurationFragment.ALL_GROUPS_SENTINEL_ID)?.adapter?.itemCount == 12
            }
            screenshot("02-nodes")
            instrumentation.runOnMainSync { host.findViewById<RadioGroup>(R.id.columnCountGroup).check(R.id.radioMulti) }
            awaitReady {
                val fragment = host.supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ConfigurationFragment
                fragment?.adapter?.groupFragments?.values?.any { (it.layoutManager as? GridLayoutManager)?.spanCount == 3 } == true
            }
            idle()
            instrumentation.runOnMainSync {
                val fragment = host.supportFragmentManager.findFragmentById(R.id.fragment_holder) as ConfigurationFragment
                val list = fragment.getCurrentGroupFragment()!!.configurationListView
                val name = list.getChildAt(0).findViewById<TextView>(R.id.profile_name)
                assertTrue("Three-column names need readable width", name.width >= (48 * context.resources.displayMetrics.density).toInt())
            }
            screenshot("02b-nodes-three-columns")
            instrumentation.runOnMainSync {
                host.findViewById<RadioGroup>(R.id.columnCountGroup).check(R.id.radioDouble)
                host.binding.bottomNav.selectedItemId = R.id.nav_subscriptions
            }
            awaitReady { host.findViewById<TextView>(R.id.subscriptions_summary)?.text?.contains("12") == true }
            instrumentation.runOnMainSync {
                val fragment = host.supportFragmentManager.findFragmentById(R.id.fragment_holder) as GroupFragment
                fragment.toggleGroupSelection(ids[0]); fragment.toggleGroupSelection(ids[1])
            }
            idle()
            instrumentation.runOnMainSync {
                assertTrue(host.findViewById<View>(R.id.subscriptions_update_selected).isEnabled)
                assertEquals("更新选中 (2)", host.findViewById<TextView>(R.id.subscriptions_update_selected).text.toString())
            }
            screenshot("03-subscriptions")
            instrumentation.runOnMainSync { host.binding.bottomNav.selectedItemId = R.id.nav_settings }
            awaitReady { host.findViewById<View>(R.id.settings_dns) != null }
            screenshot("04-settings")
            instrumentation.runOnMainSync { host.findViewById<View>(R.id.settings_dns).performClick() }
            awaitReady {
                val fragment = host.supportFragmentManager.findFragmentById(R.id.fragment_holder) as? SettingsFragment
                fragment?.childFragmentManager?.findFragmentById(R.id.settings) is SettingsPreferenceFragment
            }
            instrumentation.runOnMainSync {
                val fragment = host.supportFragmentManager.findFragmentById(R.id.fragment_holder) as SettingsFragment
                assertEquals(context.getString(R.string.minimal_dns), fragment.toolbar.title.toString())
                assertTrue(fragment.onBackPressed())
            }
            awaitReady { host.findViewById<View>(R.id.settings_dns) != null }
            instrumentation.runOnMainSync {
                assertTrue(host.binding.bottomNav.menu.findItem(R.id.nav_settings).isChecked)
                assertTrue(host.binding.fragmentHolder.bottom <= host.binding.bottomNav.top)
            }
        } finally {
            activity?.let { host -> instrumentation.runOnMainSync { host.finish() } }
            ids.forEach { id -> runBlocking { GroupManager.deleteGroup(id) } }
            DataStore.selectedProxy = oldSelected; DataStore.selectedGroup = oldGroup
            DataStore.groupLayoutMode = oldLayout; DataStore.nodeColumnCount = oldColumns
            DataStore.showSubscriptionInfoCard = oldInfo
        }
    }
}
