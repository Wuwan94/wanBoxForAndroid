package io.nekohasekai.sagernet

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.CheckBox
import android.widget.RadioGroup
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

        val baseOutputDir = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { File(it) }
            ?: context.externalMediaDirs.firstOrNull()?.resolve("additional_test_output")
            ?: context.getExternalFilesDir(null)
            ?: context.filesDir

        val output = File(baseOutputDir, "minimal-ui").apply {
            if (!exists()) mkdirs()
            assertTrue("Output directory invalid: $absolutePath", isDirectory)
        }

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

            fun screenshot(name: String) {
                idle()
                val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "takeScreenshot returned null for $name" }
                try {
                    File(output, "$name.webp").outputStream().use { out ->
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out))
                    }
                } finally {
                    bitmap.recycle()
                }
            }

            fun awaitReady(step: String, predicate: () -> Boolean) {
                repeat(100) {
                    var ready = false
                    instrumentation.runOnMainSync { ready = predicate() }
                    if (ready) return
                    Thread.sleep(100)
                }
                runCatching { screenshot("failure-$step") }
                var diag = ""
                instrumentation.runOnMainSync {
                    runCatching {
                        val curFrag = host.supportFragmentManager.findFragmentById(R.id.fragment_holder)
                        val fragName = curFrag?.javaClass?.simpleName ?: "null"
                        val panelVis = host.findViewById<View>(R.id.node_display_panel)?.visibility ?: -1
                        val nodesSummary = host.findViewById<TextView>(R.id.nodes_summary)?.text?.toString() ?: "null"
                        if (curFrag is ConfigurationFragment) {
                            val pagerItem = runCatching { curFrag.groupPager.currentItem }.getOrDefault(-1)
                            val groupIds = runCatching { curFrag.adapter.groupList.map { it.id } }.getOrDefault(emptyList())
                            val allFrag = curFrag.adapter.groupFragments[ConfigurationFragment.ALL_GROUPS_SENTINEL_ID]
                            val allItemCount = allFrag?.adapter?.itemCount ?: -1
                            diag = "frag=$fragName, panelVis=$panelVis, pagerItem=$pagerItem, groupIds=$groupIds, allItemCount=$allItemCount, nodesSummary=$nodesSummary"
                        } else if (curFrag is SettingsFragment) {
                            diag = "frag=$fragName, composeReady=${curFrag.isComposeHomeReady()}, sections=${curFrag.getRenderedSectionKeys()}"
                        } else {
                            diag = "frag=$fragName, panelVis=$panelVis, nodesSummary=$nodesSummary"
                        }
                    }.onFailure { e ->
                        diag = "diag_error=${e.javaClass.simpleName}:${e.message}"
                    }
                }
                fail("Screen did not become ready at step: $step. Diagnostics: [${diag.take(1024)}]")
            }

            fun awaitFrameRendered(step: String) {
                idle()
                repeat(100) {
                    var settled = false
                    instrumentation.runOnMainSync {
                        val decor = host.window?.decorView
                        val decorReady = decor != null && decor.isLaidOut && !decor.isLayoutRequested
                        val curFrag = host.supportFragmentManager.findFragmentById(R.id.fragment_holder)
                        val fragView = curFrag?.view
                        val fragReady = fragView != null && fragView.isShown && fragView.isLaidOut && !fragView.isLayoutRequested

                        val rv = when (curFrag) {
                            is ConfigurationFragment -> curFrag.getCurrentGroupFragment()?.configurationListView
                            is GroupFragment -> fragView?.findViewById<RecyclerView>(R.id.group_list)
                            else -> null
                        }
                        val rvReady = if (rv != null) {
                            rv.isShown && rv.isLaidOut && !rv.isLayoutRequested &&
                                !rv.isComputingLayout && !rv.hasPendingAdapterUpdates() &&
                                rv.itemAnimator?.isRunning != true
                        } else {
                            true
                        }

                        val composeView = if (curFrag is SettingsFragment) fragView?.findViewById<View>(R.id.settings_compose) else null
                        val composeReady = if (composeView != null) {
                            composeView.isShown && composeView.isLaidOut && !composeView.isLayoutRequested &&
                                (curFrag as SettingsFragment).isComposeHomeReady()
                        } else {
                            true
                        }

                        settled = decorReady && fragReady && rvReady && composeReady
                    }
                    if (settled) {
                        val latch = CountDownLatch(1)
                        val callback = Runnable { latch.countDown() }
                        var registered = false
                        try {
                            instrumentation.runOnMainSync {
                                val decor = host.window?.decorView
                                if (decor != null && decor.isAttachedToWindow && decor.isHardwareAccelerated) {
                                    decor.viewTreeObserver.registerFrameCommitCallback(callback)
                                    decor.invalidate()
                                    registered = true
                                }
                            }
                            if (!registered) {
                                fail("Window decorView is not attached or hardware accelerated at step: $step")
                            }
                            if (latch.await(2000, TimeUnit.MILLISECONDS)) {
                                idle()
                                return
                            } else {
                                runCatching { screenshot("failure-$step") }
                                fail("Frame commit timeout at step: $step")
                            }
                        } finally {
                            if (registered) {
                                instrumentation.runOnMainSync {
                                    host.window?.decorView?.viewTreeObserver?.unregisterFrameCommitCallback(callback)
                                }
                            }
                        }
                    }
                    Thread.sleep(100)
                }
                runCatching { screenshot("failure-$step") }
                fail("UI settled frame timeout at step: $step")
            }

            fun captureScreen(name: String) {
                awaitFrameRendered(name)
                screenshot(name)
            }

            awaitReady("dashboard") { host.findViewById<TextView>(R.id.nodeName)?.text.toString() == "日本 01" }
            captureScreen("01-dashboard")

            instrumentation.runOnMainSync { host.binding.bottomNav.selectedItemId = R.id.nav_nodes }
            awaitReady("nodes") {
                val fragment = host.supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ConfigurationFragment
                fragment?.isToolbarInitialized == true && fragment.adapter.groupList.isNotEmpty()
            }

            instrumentation.runOnMainSync {
                val fragment = host.supportFragmentManager.findFragmentById(R.id.fragment_holder) as ConfigurationFragment
                val all = fragment.adapter.groupList.indexOfFirst { it.id == ConfigurationFragment.ALL_GROUPS_SENTINEL_ID }
                if (all >= 0) fragment.groupPager.setCurrentItem(all, false)
                fragment.toolbar.menu.findItem(R.id.action_display_settings).let { fragment.toolbar.menu.performIdentifierAction(it.itemId, 0) }
            }
            awaitReady("display-panel") {
                val fragment = host.supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ConfigurationFragment
                host.findViewById<View>(R.id.node_display_panel)?.visibility == View.VISIBLE &&
                    fragment?.adapter?.groupFragments?.get(ConfigurationFragment.ALL_GROUPS_SENTINEL_ID)?.adapter?.itemCount == 12
            }
            captureScreen("02-nodes")

            instrumentation.runOnMainSync {
                val fragment = host.supportFragmentManager.findFragmentById(R.id.fragment_holder) as ConfigurationFragment
                fragment.onQueryTextChange("日本")
                fragment.onQueryTextSubmit("香港")
            }
            awaitReady("latest-node-search") {
                val fragment = host.supportFragmentManager.findFragmentById(R.id.fragment_holder) as ConfigurationFragment
                val current = fragment.getCurrentGroupFragment()?.adapter
                current?.itemCount == 2 && current.configurationIdList.all {
                    current.configurationList[it]?.displayName()?.contains("香港") == true
                }
            }
            instrumentation.runOnMainSync {
                val fragment = host.supportFragmentManager.findFragmentById(R.id.fragment_holder) as ConfigurationFragment
                fragment.onQueryTextSubmit("")
            }
            awaitReady("cleared-node-search") {
                val fragment = host.supportFragmentManager.findFragmentById(R.id.fragment_holder) as ConfigurationFragment
                fragment.getCurrentGroupFragment()?.adapter?.itemCount == 12
            }
            var removedProfile: ProxyEntity? = null
            var originalNodeIds: List<Long> = emptyList()
            instrumentation.runOnMainSync {
                val fragment = host.supportFragmentManager.findFragmentById(R.id.fragment_holder) as ConfigurationFragment
                val current = fragment.getCurrentGroupFragment()!!.adapter!!
                originalNodeIds = current.configurationIdList.toList()
                removedProfile = current.configurationList[originalNodeIds.first()]
                assertNotNull(removedProfile)
                current.remove(0)
                current.filter("")
                assertEquals(11, current.itemCount)
                assertFalse(current.configurationIdList.contains(removedProfile!!.id))
                current.undo(listOf(0 to removedProfile!!))
            }
            awaitReady("node-removal-undo") {
                val fragment = host.supportFragmentManager.findFragmentById(R.id.fragment_holder) as ConfigurationFragment
                fragment.getCurrentGroupFragment()?.adapter?.configurationIdList == originalNodeIds
            }
            val renamed = removedProfile!!
            val oldName = renamed.requireBean().name
            try {
                renamed.requireBean().name = "同 ID 内容更新"
                runBlocking { ProfileManager.updateProfile(renamed) }
                awaitReady("same-node-content-update") {
                    val fragment = host.supportFragmentManager.findFragmentById(R.id.fragment_holder) as ConfigurationFragment
                    val list = fragment.getCurrentGroupFragment()?.configurationListView
                    val row = list?.findViewHolderForItemId(renamed.id)?.itemView
                    row?.findViewById<TextView>(R.id.profile_name)?.text?.toString() == "同 ID 内容更新"
                }
            } finally {
                renamed.requireBean().name = oldName
                runBlocking { ProfileManager.updateProfile(renamed) }
            }
            awaitReady("restored-node-content") {
                val fragment = host.supportFragmentManager.findFragmentById(R.id.fragment_holder) as ConfigurationFragment
                val list = fragment.getCurrentGroupFragment()?.configurationListView
                val row = list?.findViewHolderForItemId(renamed.id)?.itemView
                row?.findViewById<TextView>(R.id.profile_name)?.text?.toString() == oldName
            }

            instrumentation.runOnMainSync { host.findViewById<RadioGroup>(R.id.columnCountGroup).check(R.id.radioMulti) }
            awaitReady("three-columns") {
                val fragment = host.supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ConfigurationFragment
                val hasAnySpan3 = fragment?.adapter?.groupFragments?.values?.any { (it.layoutManager as? GridLayoutManager)?.spanCount == 3 } == true
                val allGroupFrag = fragment?.adapter?.groupFragments?.get(ConfigurationFragment.ALL_GROUPS_SENTINEL_ID)
                val allGroupSpan3 = (allGroupFrag?.layoutManager as? GridLayoutManager)?.spanCount == 3
                val list = allGroupFrag?.configurationListView
                val firstProfileName = list?.getChildAt(0)?.findViewById<TextView>(R.id.profile_name)?.text?.toString()
                hasAnySpan3 && allGroupSpan3 && !firstProfileName.isNullOrBlank()
            }
            idle()
            instrumentation.runOnMainSync {
                val fragment = host.supportFragmentManager.findFragmentById(R.id.fragment_holder) as ConfigurationFragment
                val list = fragment.getCurrentGroupFragment()!!.configurationListView
                val name = list.getChildAt(0).findViewById<TextView>(R.id.profile_name)
                assertTrue("Three-column names need readable width", name.width >= (48 * context.resources.displayMetrics.density).toInt())
            }
            captureScreen("02b-nodes-three-columns")

            instrumentation.runOnMainSync {
                host.findViewById<RadioGroup>(R.id.columnCountGroup).check(R.id.radioDouble)
                host.binding.bottomNav.selectedItemId = R.id.nav_subscriptions
            }
            awaitReady("subscriptions") { host.findViewById<TextView>(R.id.subscriptions_summary)?.text?.contains("12") == true }
            instrumentation.runOnMainSync {
                val fragment = host.supportFragmentManager.findFragmentById(R.id.fragment_holder) as GroupFragment
                fragment.toggleGroupSelection(ids[0]); fragment.toggleGroupSelection(ids[1])
            }
            idle()
            instrumentation.runOnMainSync {
                assertTrue(host.findViewById<View>(R.id.subscriptions_update_selected).isEnabled)
                assertEquals("更新选中 (2)", host.findViewById<TextView>(R.id.subscriptions_update_selected).text.toString())
            }
            captureScreen("03-subscriptions")

            instrumentation.runOnMainSync { host.binding.bottomNav.selectedItemId = R.id.nav_settings }
            awaitReady("settings") {
                val fragment = host.supportFragmentManager.findFragmentById(R.id.fragment_holder) as? SettingsFragment
                val composeView = host.findViewById<View>(R.id.settings_compose)
                fragment?.toolbar?.title?.toString() == context.getString(R.string.settings) &&
                    composeView?.isShown == true &&
                    fragment.isComposeHomeReady()
            }
            instrumentation.runOnMainSync {
                val fragment = host.supportFragmentManager.findFragmentById(R.id.fragment_holder) as SettingsFragment
                val expectedSections = listOf("runtime", "network", "speedtest", "appearance", "advanced", "other")
                assertEquals(expectedSections, fragment.getRenderedSectionKeys())
                assertTrue(fragment.hasRenderedItem("per_app_proxy"))
                val orderedHomeRows = listOf("per_app_proxy") + expectedSections
                val rowBounds = orderedHomeRows.map { key ->
                    val bounds = checkNotNull(fragment.getComposeItemBounds(key)) { "Missing bounds for $key" }
                    assertTrue("Row $key must have positive area", bounds.width() > 0f && bounds.height() > 0f)
                    key to bounds
                }
                for (i in 0 until rowBounds.lastIndex) {
                    val (upperKey, upper) = rowBounds[i]
                    val (lowerKey, lower) = rowBounds[i + 1]
                    assertTrue(
                        "Row $upperKey (${upper.top}..${upper.bottom}) must be above $lowerKey (${lower.top}..${lower.bottom}) without overlap",
                        upper.top < lower.top && upper.bottom <= lower.top
                    )
                }
                assertEquals(context.getString(R.string.settings_group_network), fragment.getRenderedItemTitle("network"))
                for (removed in listOf("root", "serviceMode", "disableMixedInbound", "enableClashAPI", "clashApiFixed")) {
                    assertFalse("Removed entry must not be rendered on settings home: $removed", fragment.hasRenderedItem(removed))
                }
                assertNull(fragment.toolbar.menu.findItem(R.id.settings_advanced))
            }
            captureScreen("04-settings")

            var tapDownTime = 0L
            var tapX = 0f
            var tapY = 0f
            instrumentation.runOnMainSync {
                val fragment = host.supportFragmentManager.findFragmentById(R.id.fragment_holder) as SettingsFragment
                val composeView = checkNotNull(host.findViewById<View>(R.id.settings_compose)) { "settings_compose missing" }
                assertTrue("settings_compose must be shown", composeView.isShown)
                val dnsBounds = checkNotNull(fragment.getComposeItemBounds("network")) { "Compose network bounds missing" }
                assertTrue("Compose network bounds must be positive", dnsBounds.width() > 0f && dnsBounds.height() > 0f)
                tapX = dnsBounds.centerX()
                tapY = dnsBounds.centerY()
                assertTrue("DNS tap X within ComposeView", tapX in 0f..composeView.width.toFloat())
                assertTrue("DNS tap Y within ComposeView", tapY in 0f..composeView.height.toFloat())
                tapDownTime = SystemClock.uptimeMillis()
                val down = MotionEvent.obtain(tapDownTime, tapDownTime, MotionEvent.ACTION_DOWN, tapX, tapY, 0).apply {
                    source = InputDevice.SOURCE_TOUCHSCREEN
                }
                assertTrue("ComposeView must accept ACTION_DOWN", composeView.dispatchTouchEvent(down))
                down.recycle()
            }
            idle()
            instrumentation.runOnMainSync {
                val composeView = checkNotNull(host.findViewById<View>(R.id.settings_compose)) { "settings_compose missing on ACTION_UP" }
                val upTime = SystemClock.uptimeMillis()
                val up = MotionEvent.obtain(tapDownTime, upTime, MotionEvent.ACTION_UP, tapX, tapY, 0).apply {
                    source = InputDevice.SOURCE_TOUCHSCREEN
                }
                assertTrue("ComposeView must accept ACTION_UP", composeView.dispatchTouchEvent(up))
                up.recycle()
            }

            awaitReady("dns-open") {
                val fragment = host.supportFragmentManager.findFragmentById(R.id.fragment_holder) as? SettingsFragment
                val prefFrag = fragment?.childFragmentManager?.findFragmentById(R.id.settings) as? SettingsPreferenceFragment
                prefFrag != null &&
                    fragment.toolbar.title?.toString() == context.getString(R.string.settings_group_network) &&
                    prefFrag.findPreference<androidx.preference.Preference>(Key.REMOTE_DNS)?.isVisible == true
            }
            instrumentation.runOnMainSync {
                val fragment = host.supportFragmentManager.findFragmentById(R.id.fragment_holder) as SettingsFragment
                assertEquals(context.getString(R.string.settings_group_network), fragment.toolbar.title.toString())
                val prefFrag = fragment.childFragmentManager.findFragmentById(R.id.settings) as SettingsPreferenceFragment
                assertTrue(prefFrag.findPreference<androidx.preference.Preference>(Key.REMOTE_DNS)!!.isVisible)
                assertTrue(prefFrag.findPreference<androidx.preference.Preference>(Key.DIRECT_DNS)!!.isVisible)
                assertTrue(prefFrag.findPreference<androidx.preference.Preference>(Key.ENABLE_FAKEDNS)!!.isVisible)
                assertNull(prefFrag.findPreference<androidx.preference.Preference>(Key.SERVICE_MODE))
                assertNull(prefFrag.findPreference<androidx.preference.Preference>(Key.DISABLE_MIXED_INBOUND))
                assertTrue(fragment.onBackPressed())
            }
            awaitReady("dns-close") {
                val fragment = host.supportFragmentManager.findFragmentById(R.id.fragment_holder) as? SettingsFragment
                val composeView = host.findViewById<View>(R.id.settings_compose)
                fragment?.toolbar?.title?.toString() == context.getString(R.string.settings) &&
                    composeView?.isShown == true &&
                    fragment.isComposeHomeReady()
            }
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
