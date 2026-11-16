package io.nekohasekai.sagernet

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.group.editor.*
import io.nekohasekai.sagernet.ui.GroupEditorActivity
import org.junit.Assert.*
import org.junit.Test

/** Real page and durable journal; no module command is needed for a blocked submit. */
class GroupEditorBlockedUiTest {
    @Test fun emptyMembersCannotReplaceCleanupWithNotApplied() = blockedResubmit(emptyMembers = true)
    @Test fun invalidIntervalCannotReplaceCleanupWithNotApplied() = blockedResubmit(emptyMembers = false)

    private fun blockedResubmit(emptyMembers: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val foreignJournalExisted = GroupLocalPreparation.pending()
        check(!foreignJournalExisted) { "Requires a clean editor transaction" }
        val original = BackupRestore.parse(org.json.JSONObject(
            io.nekohasekai.sagernet.utils.BackupHelper.doBackup().toString(Charsets.UTF_8)))
        val draft = GroupDraft(99124, "blocked-ui", GroupMode.URL_TEST, listOf(99125),
            UrlTestParameters("", 600, 100, "30m", false))
        var preparation: GroupLocalPreparation? = null
        var activity: GroupEditorActivity? = null
        fun views(view: View): List<View> = listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
        fun page() = views(activity!!.window.decorView)
        fun button(resource: Int) = page().filterIsInstance<Button>().first { it.text == context.getString(resource) }
        fun awaitPage(predicate: () -> Boolean) {
            val deadline = android.os.SystemClock.uptimeMillis() + 5000
            while (android.os.SystemClock.uptimeMillis() < deadline) {
                var ready = false
                instrumentation.runOnMainSync { ready = predicate() }
                if (ready) return
                android.os.SystemClock.sleep(20)
            }
            fail("Editor did not reach expected UI state")
        }
        var failure: Throwable? = null
        try {
            val group = ProxyGroup(id = 99124, name = "blocked-ui", isSelector = true)
            val node = ProxyEntity(id = 99125, groupId = group.id).putBean(
                io.nekohasekai.sagernet.fmt.socks.SOCKSBean().apply {
                    initializeDefaultValues(); serverAddress = "192.0.2.1"; serverPort = 1080
                })
            BackupRestore.apply(BackupRestore.Plan(listOf(node), listOf(group), emptyList(), null), true, true, false)
            GroupEditorStore.write(draft)
            activity = instrumentation.startActivitySync(Intent(context, GroupEditorActivity::class.java)
                .putExtra("id", group.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as GroupEditorActivity
            awaitPage { page().filterIsInstance<Button>().any { it.text == context.getString(R.string.group_editor_apply) } }
            var staleRemove: Button? = null
            instrumentation.runOnMainSync {
                if (emptyMembers) {
                    staleRemove = button(R.string.group_editor_remove)
                    staleRemove!!.performClick()
                }
                else page().filterIsInstance<EditText>()[2].setText("0")
            }
            // A durable interrupted transaction is the production cleanup_required boundary.
            preparation = GroupLocalPreparation(draft)
            preparation.prepare("{}")
            instrumentation.runOnMainSync { button(R.string.group_editor_apply).performClick() }
            awaitPage { page().filterIsInstance<TextView>().any { it.text == context.getString(R.string.group_editor_cleanup) } }
            instrumentation.runOnMainSync {
                assertFalse(button(R.string.group_editor_apply).isEnabled)
                assertTrue(button(R.string.group_editor_recover).isEnabled)
                // Programmatic stale callbacks must be safe even though user input is disabled.
                if (emptyMembers) staleRemove!!.performClick()
                else page().filterIsInstance<EditText>()[2].setText("0")
                button(R.string.group_editor_apply).performClick()
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val texts = page().filterIsInstance<TextView>().map { it.text.toString() }
                assertTrue(texts.contains(context.getString(R.string.group_editor_cleanup)))
                assertFalse(texts.any { it.contains(context.getString(R.string.group_editor_not_applied)) })
            }
            assertTrue(GroupLocalPreparation.pending())
        } catch (error: Throwable) {
            failure = error
        } finally {
            fun cleanup(step: () -> Unit) {
                try { step() } catch (error: Throwable) {
                    if (failure == null) failure = error else failure!!.addSuppressed(error)
                }
            }
            cleanup { instrumentation.runOnMainSync { activity?.finish() } }
            cleanup {
                preparation?.restore()
                preparation?.finish()
            }
            cleanup { BackupRestore.apply(original, true, true, true) }
        }
        failure?.let { throw it }
    }
}
