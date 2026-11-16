package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.group.editor.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class GroupCandidateCommitTest {
    private class Host : CandidateHost {
        val commands = mutableListOf<Pair<String, String>>()
        var version = "db1"
        var revision = "r1"
        var phase = "stopped"
        var failValidate = false
        var failApply = false
        var changeInputs = false
        var raceAtApply = false
        var changeRevision = false
        var staleConfirmation = false
        var captured = 0
        var local = "old"
        var failPrepare = false
        var failRollback = false
        var failConfirmation = false
        var failFinish = false
        var failRestore = false
        var failRollbackConfirmation = false
        var journalPending = false
        var confirmationFailed = false
        var moduleConfig = "old"
        override suspend fun finish() {
            if (failFinish) { failFinish = false; throw IOException("disk_full") }
            journalPending = false
        }
        override suspend fun prepare(candidate: CandidateSnapshot) {
            journalPending = true; local = "new"
            if (failPrepare) throw IOException("disk_full")
        }
        override suspend fun restore() {
            if (failRestore) throw IOException("restore_failed")
            local = "old"
        }

        override suspend fun inputVersion() = version
        override suspend fun freshStatus(): CandidateStatus {
            if (failRollbackConfirmation && revision == "restored") throw IOException("status_unavailable")
            if (failConfirmation && revision == "r2" && !confirmationFailed) {
                confirmationFailed = true
                throw IOException("status_unavailable")
            }
            return CandidateStatus(revision, phase, if (phase == "connected") revision else "")
        }
        override suspend fun capture(expectedRevision: String, inputVersion: String): CandidateSnapshot {
            captured++
            return CandidateSnapshot("{\"expectedRevision\":\"$expectedRevision\",\"config\":{}}", inputVersion, expectedRevision, "{\"config\":{\"old\":true}}")
        }
        override suspend fun command(command: String, candidate: CandidateSnapshot): CandidateStatus {
            commands += command to candidate.json
            val rollback = com.google.gson.JsonParser.parseString(candidate.json)
                .asJsonObject["config"].asJsonObject["old"]?.asBoolean == true
            if (rollback) {
                if (failRollback) throw IOException("rollback_failed")
                if (command == "config apply") { revision = "restored"; moduleConfig = "old" }
                return CandidateStatus(revision, phase, if (phase == "connected") revision else "")
            }
            if (command == "config validate") {
                if (failValidate) throw IOException("core_config_invalid")
                if (changeInputs) version = "db2"
                if (changeRevision) revision = "external"
            } else {
                if (raceAtApply) {
                    revision = "external"; moduleConfig = "external-new"
                    throw IOException("revision_conflict")
                }
                moduleConfig = "new"
                if (!staleConfirmation) revision = "r2"
                if (failApply) throw IOException("apply_response_lost")
            }
            return CandidateStatus(if (command == "config apply") "r2" else revision, phase, if (phase == "connected") "r2" else "")
        }
    }

    @Test fun externalCommitBetweenFinalCheckAndApplyIsNeverOverwritten() = runTest {
        val host = Host().apply { raceAtApply = true }
        try { GroupCandidateCommit.apply(host); fail("external revision accepted") }
        catch (error: IOException) { assertEquals("cleanup_required", error.message) }
        assertEquals("external", host.revision)
        assertEquals("external-new", host.moduleConfig)
        assertEquals(listOf("config validate", "config apply"), host.commands.map { it.first })
        assertTrue(host.journalPending)
        assertEquals("old", host.local)
    }

    @Test fun sameCandidateBytesAndExpectedRevisionAreValidatedAndAppliedOnce() = runTest {
        val host = Host()
        val result = GroupCandidateCommit.apply(host)
        assertEquals("r2", result.revision)
        assertEquals(1, host.captured)
        assertEquals(listOf("config validate", "config apply"), host.commands.map { it.first })
        assertEquals(host.commands[0].second, host.commands[1].second)
        assertTrue(host.commands[0].second.contains("\"expectedRevision\":\"r1\""))
        assertEquals("stopped", host.phase)
        assertFalse(host.journalPending)
        assertEquals("new", host.local)
        assertEquals("new", host.moduleConfig)
    }

    @Test fun preflightFailureAndInputOrRevisionConflictsNeverApply() = runTest {
        for (configure in listOf<(Host) -> Unit>({ it.failValidate = true }, { it.changeInputs = true }, { it.changeRevision = true })) {
            val host = Host().also(configure)
            try { GroupCandidateCommit.apply(host); fail("unapplied candidate reported successful") }
            catch (_: IOException) { }
            assertEquals(listOf("config validate"), host.commands.map { it.first })
        }
    }

    @Test fun everyFailureRestoresLocalAndAppliedFailuresRestoreCurrent() = runTest {
        for (configure in listOf<(Host) -> Unit>(
            { it.failPrepare = true }, { it.failValidate = true },
            { it.staleConfirmation = true },
            { it.failConfirmation = true }, { it.failFinish = true })) {
            val host = Host().also(configure)
            try { GroupCandidateCommit.apply(host); fail("failure reported applied") }
            catch (_: IOException) { }
            assertEquals("old", host.local)
            assertTrue(host.revision in setOf("r1", "restored"))
            assertEquals("old", host.moduleConfig)
            assertFalse(host.journalPending)
            if (host.commands.any { it.first == "config apply" }) assertEquals("restored", host.revision)
        }
    }

    @Test fun lostApplyResponseRetainsJournalWhenRevisionOwnershipIsUnknown() = runTest {
        val host = Host().apply { failApply = true }
        try { GroupCandidateCommit.apply(host); fail("unknown apply accepted") }
        catch (error: IOException) { assertEquals("cleanup_required", error.message) }
        assertEquals("new", host.moduleConfig)
        assertTrue(host.journalPending)
    }

    @Test fun unconfirmedRollbackBlocksWithCleanupError() = runTest {
        val host = Host().apply { staleConfirmation = true; failRollback = true }
        try { GroupCandidateCommit.apply(host); fail("unsafe failure") }
        catch (error: IOException) { assertEquals("cleanup_required", error.message) }
        assertEquals("old", host.local)
        assertEquals("new", host.moduleConfig)
        assertTrue(host.journalPending)
    }

    @Test fun failedLocalRestoreOrRollbackConfirmationRetainsRecoveryBlock() = runTest {
        for (configure in listOf<(Host) -> Unit>(
            { it.failValidate = true; it.failRestore = true },
            { it.failConfirmation = true; it.failRollbackConfirmation = true })) {
            val host = Host().also(configure)
            try { GroupCandidateCommit.apply(host); fail("unconfirmed cleanup accepted") }
            catch (error: IOException) { assertEquals("cleanup_required", error.message) }
            assertTrue(host.journalPending)
            assertEquals(if (host.failRestore) "new" else "old", host.local)
            assertEquals("old", host.moduleConfig)
        }
    }

    @Test fun runningApplyConfirmsFreshRunningRevisionWithoutStartingStoppedCore() = runTest {
        val host = Host().apply { phase = "connected" }
        assertEquals("r2", GroupCandidateCommit.apply(host).runningRevision)
        assertEquals(listOf("config validate", "config apply"), host.commands.map { it.first })
    }
}
