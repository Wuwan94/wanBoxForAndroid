package io.nekohasekai.sagernet

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.group.editor.*
import io.nekohasekai.sagernet.ui.GroupEditorActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException

/** Import -> editor UI/save boundary -> real subscription refresh and persisted route identity. */
class SubscriptionGroupOwnershipTest {
    @Test fun ordinaryEditorCannotTakeSubscriptionNodesOrPartiallySave() = runBlocking {
        check(!GroupLocalPreparation.pending())
        val original = BackupRestore.parse(org.json.JSONObject(
            io.nekohasekai.sagernet.utils.BackupHelper.doBackup().toString(Charsets.UTF_8)))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File.createTempFile("subscription-ownership-", ".txt", context.cacheDir)
        var activity: GroupEditorActivity? = null
        var preparation: GroupLocalPreparation? = null
        fun views(view: View): List<View> = listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
        fun page() = views(activity!!.window.decorView)
        fun awaitUi(predicate: () -> Boolean) {
            val deadline = android.os.SystemClock.uptimeMillis() + 8000
            while (android.os.SystemClock.uptimeMillis() < deadline) {
                var ready = false
                instrumentation.runOnMainSync { ready = predicate() }
                if (ready) return
                android.os.SystemClock.sleep(30)
            }
            fail("Editor did not show the subscription ownership rejection")
        }
        try {
            source.writeText("socks://192.0.2.1:1080#subscription-node")
            val subscription = SubscriptionBean().apply {
                initializeDefaultValues()
                link = FileProvider.getUriForFile(context, "${context.packageName}.cache", source).toString()
                forceResolve = false
            }
            val remote = ProxyGroup(id = 99124, name = "remote", type = GroupType.SUBSCRIPTION,
                subscription = subscription, isSelector = true)
            val local = ProxyGroup(id = 99140, name = "ordinary", isSelector = true)
            val localNode = ProxyEntity(id = 99141, groupId = local.id).putBean(
                io.nekohasekai.sagernet.fmt.socks.SOCKSBean().apply {
                    initializeDefaultValues(); name = "local"; serverAddress = "192.0.2.2"; serverPort = 1080
                })
            BackupRestore.apply(BackupRestore.Plan(listOf(localNode), listOf(remote, local), emptyList(), null),
                true, true, false)
            RawUpdater.doUpdate(remote, subscription, null, false)
            val imported = SagerDatabase.proxyDao.getByGroup(remote.id).single()
            SagerDatabase.rulesDao.createRule(RuleEntity(id = 99150, name = "stable route", outbound = imported.id))
            val initialVersion = GroupEditorService.inputVersion()
            activity = instrumentation.startActivitySync(Intent(context, GroupEditorActivity::class.java)
                .putExtra("id", local.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as GroupEditorActivity
            awaitUi { page().filterIsInstance<Button>().any { it.text == context.getString(R.string.group_editor_apply) } }
            instrumentation.runOnMainSync {
                page().filterIsInstance<Button>().single { it.text == context.getString(R.string.group_editor_add_member) }.performClick()
            }
            instrumentation.waitForIdleSync()
            val label = "${imported.displayName()} (#${imported.id})"
            val choice = instrumentation.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText(label).first()
            assertTrue(choice.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                choice.parent.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                page().filterIsInstance<Button>().single { it.text == context.getString(R.string.group_editor_apply) }.performClick()
            }
            awaitUi { page().filterIsInstance<TextView>().any {
                it.text.contains(context.getString(R.string.group_editor_subscription_member_locked))
            } }
            assertEquals(initialVersion, GroupEditorService.inputVersion())
            instrumentation.runOnMainSync { activity!!.finish() }; activity = null
            val draft = GroupEditorService.load(local.id).copy(members = listOf(imported.id))
            assertTrue(GroupEditorService.validate(draft).any {
                it.code == "subscription_member_locked" && it.member == EntityId.Node(imported.id)
            })
            // Also enforce at persistence, not only the UI preflight; dropping the local node must not partially save.
            try {
                preparation = GroupLocalPreparation(draft)
                preparation!!.prepare()
                fail("Subscription takeover must be rejected before writing either store")
            } catch (_: IOException) { }
            assertEquals(initialVersion, GroupEditorService.inputVersion())
            assertFalse(GroupLocalPreparation.pending())
            assertEquals(listOf(localNode.id), GroupEditorService.load(local.id).members)
            assertTrue(GroupEditorService.validate(draft.copy(id = 99160, name = "new ordinary")).any {
                it.code == "subscription_member_locked"
            })
            source.writeText("socks://192.0.2.1:1081#subscription-node")
            RawUpdater.doUpdate(remote, subscription, null, false)
            val refreshed = SagerDatabase.proxyDao.getByGroup(remote.id).single()
            assertEquals(imported.id, refreshed.id)
            assertEquals(remote.id, refreshed.groupId)
            assertEquals(1081, refreshed.requireBean().serverPort.toInt())
            assertEquals(2, SagerDatabase.proxyDao.getAll().size)
            assertEquals(imported.id, SagerDatabase.rulesDao.allRules().single().outbound)
            assertEquals(listOf(localNode.id), GroupEditorService.load(local.id).members)
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            instrumentation.waitForIdleSync()
            // Only the instance whose prepare started owns cleanup; a same-group journal is not proof.
            try {
                preparation?.restore()
                preparation?.finish()
            } finally {
                source.delete()
                BackupRestore.apply(original, true, true, true)
            }
        }
    }
}
