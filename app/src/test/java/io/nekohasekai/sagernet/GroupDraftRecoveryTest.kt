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
