package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.group.editor.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class GroupDraftRecoveryTest {
    private class Host : GroupRecoveryHost {
        var journal = true
        var restored = false
        var revision = "before"
        var confirm = true
        var failure: Exception? = null
        val commands = mutableListOf<String>()
        override fun pending() = journal
        override fun baseline() = """{"config":{"outbounds":[]}}"""
        override fun restoreLocal() { restored = true }
        override fun clearJournal() { journal = false }
        override suspend fun status() = CandidateStatus(revision, "connected", if (confirm) revision else "stale")
        override suspend fun command(command: String, candidate: CandidateSnapshot): CandidateStatus {
            failure?.let { throw it }
            assertEquals("before", candidate.expectedRevision)
            commands += command
            if (command == "config apply") revision = "restored"
            return CandidateStatus(revision, "connected", revision)
        }
    }
    @Test fun pendingJournalRestoresLocalAndModuleThenClearsBlockingJournal() = runTest {
        val host = Host()
        assertEquals("restored", GroupDraftRecovery.recover(host).revision)
        assertTrue(host.restored)
        assertFalse(host.journal)
        assertEquals(listOf("config validate", "config apply"), host.commands)
    }
    @Test fun unknownModuleOutcomeNeverClearsJournal() = runTest {
        val host = Host().apply { confirm = false }
        try { GroupDraftRecovery.recover(host); fail() } catch (error: IOException) {
            assertEquals("rollback_not_confirmed", error.message)
        }
        assertTrue(host.journal)
    }
    @Test fun validationFailureLeavesLocalAndJournalIntact() = runTest {
        val host = Host().apply { failure = IOException("invalid") }
        try { GroupDraftRecovery.recover(host); fail() } catch (_: IOException) {}
        assertFalse(host.restored)
        assertTrue(host.journal)
    }
    @Test fun cancellationIsNotConvertedToSuccessOrSwallowed() = runTest {
        val host = Host().apply { failure = CancellationException("cancelled") }
        try { GroupDraftRecovery.recover(host); fail() } catch (_: CancellationException) {}
        assertTrue(host.journal)
    }
    @Test fun noJournalIsReadOnly() = runTest {
        val host = Host().apply { journal = false }
        GroupDraftRecovery.recover(host)
        assertFalse(host.restored)
        assertTrue(host.commands.isEmpty())
    }
    private class AbandonHost(private val failRestore: Boolean = false, private val failRebuild: Boolean = false) : GroupAbandonHost {
        val steps = mutableListOf<String>()
        var journal = true
        override fun pending() = journal
        override fun restoreLocal() { if (failRestore) throw IOException("restore_failed"); steps += "restore" }
        override fun clearJournal() { journal = false; steps += "clear" }
        override suspend fun rebuild() { steps += "rebuild"; if (failRebuild) throw IOException("rebuild_failed") }
    }
    @Test fun abandonRetainsRecoveryAfterModuleRebuildFailure() = runTest {
        val host = AbandonHost(failRebuild = true)
        try { GroupDraftAbandon.abandon(host); fail() } catch (_: IOException) {}
        assertTrue("Rebuild failure must retain a durable retry", host.journal)
    }
    @Test fun abandonClearsJournalOnlyAfterRebuildCompletes() = runTest {
        val host = AbandonHost()
        GroupDraftAbandon.abandon(host)
        assertEquals(listOf("restore", "rebuild", "clear"), host.steps)
        assertFalse(host.journal)
    }
    @Test fun abandonKeepsJournalWhenLocalRestoreFails() = runTest {
        val host = AbandonHost(failRestore = true)
        try { GroupDraftAbandon.abandon(host); fail() } catch (error: IOException) {
            assertEquals("group_editor_recovery_required", error.message)
        }
        assertTrue(host.journal)
        assertTrue(host.steps.isEmpty())
    }
    @Test fun abandonWithoutJournalDoesNothing() = runTest {
        val host = AbandonHost().apply { journal = false }
        GroupDraftAbandon.abandon(host)
        assertTrue(host.steps.isEmpty())
    }
    @Test fun baselineReadChecksRevisionAndInjectsCas() = runTest {
        val rev = "a".repeat(64)
        val result = EditorBaselineReader.read(rev, { "{}".toByteArray() }, { rev })
        assertEquals(rev, result["expectedRevision"].asString)
        try { EditorBaselineReader.read(rev, { "{}".toByteArray() }, { "b".repeat(64) }); fail() }
        catch (error: IOException) { assertEquals("revision_conflict", error.message) }
        try { EditorBaselineReader.read("invalid", { fail("must not read"); byteArrayOf() }, { rev }); fail() }
        catch (error: IOException) { assertEquals("baseline_revision_invalid", error.message) }
    }
}
