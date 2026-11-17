package io.nekohasekai.sagernet

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Rect
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.appbar.MaterialToolbar
import io.nekohasekai.sagernet.bg.RootModuleClient
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.group.editor.*
import io.nekohasekai.sagernet.ui.PolicyGroupsActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Public abandon action, real Room/preferences/journal and Activity lifecycle; fake only the external CLI. */
class GroupDraftAbandonPersistenceTest {
    private enum class Outcome { APPLY_FAILURE, UNKNOWN_STATUS, TIMEOUT_STATUS, STALE_STATUS, SUCCESS }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun rebuildFailureSurvivesActivityDestructionAndUserCanRetry() = fixture(Outcome.APPLY_FAILURE, recreate = true)
    @Test fun unknownFreshStatusKeepsDurableRecovery() = fixture(Outcome.UNKNOWN_STATUS)
    @Test fun freshStatusTimeoutKeepsDurableRecovery() = fixture(Outcome.TIMEOUT_STATUS)
    @Test fun staleFreshRevisionKeepsDurableRecovery() = fixture(Outcome.STALE_STATUS)
    @Test fun confirmedRebuildAloneClearsJournal() = fixture(Outcome.SUCCESS)
    @Test fun stoppedModuleOnlyRestoresLocalStateAndClearsMarker() = fixture(Outcome.SUCCESS, phase = "stopped")
    @Test fun suspendedModuleIsNotStartedByDiscard() = fixture(Outcome.SUCCESS, phase = "suspended")

    private fun fixture(initialOutcome: Outcome, phase: String = "connected", recreate: Boolean = false) = runBlocking {
        check(!GroupLocalPreparation.pending())
        val original = BackupRestore.parse(org.json.JSONObject(
            io.nekohasekai.sagernet.utils.BackupHelper.doBackup().toString(Charsets.UTF_8)))
        val oldLauncher = RootModuleClient.commandProcess
        var activity: PolicyGroupsActivity? = null
        var outcome = initialOutcome
        var applied = false
        var confirmationSawJournal = false
        val beforeRevision = "a".repeat(64)
        val appliedRevision = "b".repeat(64)
        val mutations = java.util.Collections.synchronizedList(mutableListOf<String>())
        fun response(p: String, revision: String) = """{"schemaVersion":1,"ok":true,"state":{"phase":"$p","revision":"$revision","runningRevision":"$revision"}}"""
        fun recoveryVisible(): Boolean {
            val menu = activity!!.findViewById<MaterialToolbar>(R.id.policy_toolbar).menu
            return (0 until menu.size()).any {
                menu.getItem(it).title == context.getString(R.string.group_editor_recover) && menu.getItem(it).isVisible
            }
        }
        fun openPage(): PolicyGroupsActivity = instrumentation.startActivitySync(
            Intent(context, PolicyGroupsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PolicyGroupsActivity
        fun awaitCondition(predicate: () -> Boolean) {
            val deadline = android.os.SystemClock.uptimeMillis() + 8000
            while (android.os.SystemClock.uptimeMillis() < deadline) {
                if (predicate()) return
                android.os.SystemClock.sleep(40)
            }
            fail("Recovery UI did not reach the expected durable state")
        }
        fun clickVisibleText(text: String) {
            awaitCondition {
                val candidates = instrumentation.uiAutomation.rootInActiveWindow
                    ?.findAccessibilityNodeInfosByText(text).orEmpty()
                candidates.any { node ->
                    if (node.text?.toString()?.trim()?.equals(text.trim(), ignoreCase = true) != true) {
                        false
                    } else {
                        // Popup menu text nodes are not consistently clickable across Android
                        // versions. Prefer the nearest clickable row, then tap the visible label.
                        var clickable: AccessibilityNodeInfo? = node
                        var depth = 0
                        var clicked = false
                        while (clickable != null && depth < 3 && !clicked) {
                            clicked = clickable.isClickable && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            clickable = clickable.parent
                            depth++
                        }
                        if (clicked) true else {
                            val bounds = Rect()
                            node.getBoundsInScreen(bounds)
                            if (bounds.isEmpty) false else {
                                val x = bounds.exactCenterX()
                                val y = bounds.exactCenterY()
                                val downTime = android.os.SystemClock.uptimeMillis()
                                val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
                                val up = MotionEvent.obtain(downTime, downTime + 1, MotionEvent.ACTION_UP, x, y, 0)
                                try {
                                    val injectedDown = instrumentation.uiAutomation.injectInputEvent(down, true)
                                    val injectedUp = instrumentation.uiAutomation.injectInputEvent(up, true)
                                    injectedDown && injectedUp
                                } finally {
                                    down.recycle()
                                    up.recycle()
                                }
                            }
                        }
                    }
                }
            }
        }
        val fixtureGroupId = 99124L
        // A unique persisted marker distinguishes this test's journal from any record that happens
        // to use the same fixture group ID; teardown must never infer ownership from the ID alone.
        val fixtureBaseline = """{"testJournalOwner":"${java.util.UUID.randomUUID()}"}"""
        try {
            val group = ProxyGroup(id = fixtureGroupId, name = "abandon-fixture", isSelector = true)
            val node = ProxyEntity(id = 99125, groupId = group.id).putBean(
                io.nekohasekai.sagernet.fmt.socks.SOCKSBean().apply {
                    initializeDefaultValues(); name = "fixture-node"; serverAddress = "192.0.2.1"; serverPort = 1080
                })
            BackupRestore.apply(BackupRestore.Plan(listOf(node), listOf(group), emptyList(), null), true, true, false)
            DataStore.serviceMode = Key.MODE_ROOT
            DataStore.globalCustomConfig = ""; DataStore.globalMode = false; DataStore.enableClashAPI = false
            DataStore.selectedProxy = node.id
            val initial = GroupEditorService.load(group.id)
            GroupEditorStore.write(initial)
            GroupLocalPreparation(initial.copy(parameters = initial.parameters.copy(toleranceMs = 321))).prepare(fixtureBaseline)
            assertEquals(321, GroupEditorService.load(group.id).parameters.toleranceMs)
            RootModuleClient.invalidateStatusCache()
            RootModuleClient.commandProcess = { command ->
                when (command) {
                    "channel" -> throw IOException("channel_unavailable")
                    "status" -> {
                        if (applied) {
                            confirmationSawJournal = GroupLocalPreparation.pending()
                            when (outcome) {
                                Outcome.TIMEOUT_STATUS -> ReplyProcess(response("connected", appliedRevision), timeout = true)
                                Outcome.UNKNOWN_STATUS -> ReplyProcess(response("starting", appliedRevision))
                                Outcome.STALE_STATUS -> ReplyProcess(response("connected", beforeRevision))
                                else -> ReplyProcess(response("connected", appliedRevision))
                            }
                        } else ReplyProcess(response(phase, beforeRevision))
                    }
                    "config apply" -> {
                        mutations += command
                        if (outcome == Outcome.APPLY_FAILURE) throw IOException("revision_conflict")
                        applied = true
                        ReplyProcess(response("connected", appliedRevision))
                    }
                    else -> throw AssertionError("Discard must not issue $command")
                }
            }
            if (outcome == Outcome.SUCCESS) {
                val status = RootModuleClient.abandonGroupDraft()
                assertEquals(phase, status.phase)
                assertFalse(GroupLocalPreparation.pending())
                if (phase == "connected") assertTrue("Fresh confirmation must precede journal deletion", confirmationSawJournal)
                else assertTrue("Non-running modules must not be rebuilt or started", mutations.isEmpty())
            } else {
                try { RootModuleClient.abandonGroupDraft(); fail("Discard must not report success") }
                catch (_: IOException) { }
                assertTrue(GroupLocalPreparation.pending())
                assertEquals(initial, GroupEditorService.load(group.id))
                // A new reader can still decode and restore the persisted record after failure.
                assertEquals(group.id, GroupLocalPreparation.undoRecord().id)
                activity = openPage()
                instrumentation.runOnMainSync { assertTrue(recoveryVisible()) }
                if (recreate) {
                    instrumentation.runOnMainSync { activity!!.finish() }
                    instrumentation.waitForIdleSync()
                    activity = openPage()
                    instrumentation.runOnMainSync { assertTrue(recoveryVisible()) }
                    outcome = Outcome.SUCCESS
                    instrumentation.runOnMainSync {
                        activity!!.findViewById<MaterialToolbar>(R.id.policy_toolbar).showOverflowMenu()
                    }
                    clickVisibleText(context.getString(R.string.group_editor_abandon))
                    // Confirm the real dialog, rather than bypassing the user's retry path.
                    clickVisibleText(context.getString(R.string.group_editor_abandon))
                    awaitCondition { !GroupLocalPreparation.pending() }
                    instrumentation.waitForIdleSync()
                    instrumentation.runOnMainSync { assertFalse(recoveryVisible()) }
                }
            }
            if (GroupLocalPreparation.pending()) {
                outcome = Outcome.SUCCESS
                RootModuleClient.abandonGroupDraft()
                assertFalse("A successful public retry clears this fixture recovery", GroupLocalPreparation.pending())
            }
            assertEquals(initial, GroupEditorService.load(group.id))
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            instrumentation.waitForIdleSync()
            RootModuleClient.commandProcess = oldLauncher
            RootModuleClient.invalidateStatusCache()
            // If an assertion interrupts the UI retry, clean only the journal whose unique marker
            // this fixture persisted. A matching group ID by itself is not proof of ownership.
            val pendingRecord = if (GroupLocalPreparation.pending())
                runCatching { GroupLocalPreparation.undoRecord() }.getOrNull() else null
            if (pendingRecord?.id == fixtureGroupId && pendingRecord.baseline == fixtureBaseline) {
                GroupLocalPreparation.restoreRecord(pendingRecord)
                GroupLocalPreparation.clearRecord()
            }
            if (!GroupLocalPreparation.pending()) BackupRestore.apply(original, true, true, true)
        }
    }

    private class ReplyProcess(response: String, private val timeout: Boolean = false) : Process() {
        private val input = response.byteInputStream()
        override fun getInputStream() = input
        override fun getErrorStream() = java.io.ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = java.io.ByteArrayOutputStream()
        override fun waitFor(): Int { if (timeout) Thread.sleep(60_000); return 0 }
        override fun exitValue() = 0
        override fun destroy() = Unit
    }
}
