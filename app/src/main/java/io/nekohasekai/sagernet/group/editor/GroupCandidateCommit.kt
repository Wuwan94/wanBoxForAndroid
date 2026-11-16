package io.nekohasekai.sagernet.group.editor

import java.io.IOException
import java.security.MessageDigest

/** Immutable text is the transport bytes' sole source for BOTH commands. */
data class CandidateSnapshot(val json: String, val inputVersion: String, val expectedRevision: String, val baselineJson: String? = null) {
    val digest: String = MessageDigest.getInstance("SHA-256").digest(json.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
data class CandidateStatus(val revision: String, val phase: String, val runningRevision: String)
interface CandidateHost {
    suspend fun prepare(candidate: CandidateSnapshot) {}
    suspend fun restore() {}
    suspend fun finish() {}
    suspend fun inputVersion(): String
    suspend fun freshStatus(): CandidateStatus
    suspend fun capture(expectedRevision: String, inputVersion: String): CandidateSnapshot
    suspend fun command(command: String, candidate: CandidateSnapshot): CandidateStatus
}

/** Caller must hold the existing module mutation lock throughout this operation. */
object GroupCandidateCommit {
    suspend fun apply(host: CandidateHost): CandidateStatus {
        val before = host.freshStatus()
        if (before.phase !in setOf("connected", "stopped", "suspended")) throw IOException("module_not_ready")
        val inputVersion = host.inputVersion()
        val candidate = host.capture(before.revision, inputVersion)
        if (candidate.expectedRevision != before.revision || candidate.inputVersion != inputVersion)
            throw IOException("candidate_version_conflict")
        if (host.inputVersion() != inputVersion) throw IOException("input_version_conflict")
        var prepareAttempted = false
        var applyAttempted = false
        var appliedRevision: String? = null
        try {
            prepareAttempted = true // prepare can fail after partially writing local state
            host.prepare(candidate)
            val preparedVersion = host.inputVersion()
            host.command("config validate", candidate)
            if (host.inputVersion() != preparedVersion) throw IOException("input_version_conflict")
            if (host.freshStatus().revision != candidate.expectedRevision) throw IOException("revision_conflict")
            applyAttempted = true // a lost apply response may still have changed current
            val applied = host.command("config apply", candidate)
            appliedRevision = applied.revision
            val confirmed = host.freshStatus()
            if (!confirmed.matches(applied, before.phase)) throw IOException("apply_not_confirmed")
            host.finish()
            return confirmed
        } catch (error: Exception) {
            // Cancellation must not interrupt recovery after a possibly successful apply.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                var restored = true
                if (prepareAttempted) try { host.restore() } catch (_: Exception) { restored = false }
                if (applyAttempted) try {
                    val baseline = candidate.baselineJson ?: throw IOException("baseline_unavailable")
                    val current = host.freshStatus()
                    // A third revision belongs to an external or unconfirmed commit.
                    // Keep the journal rather than overwrite it with our old baseline.
                    if (current.revision != before.revision && current.revision != appliedRevision)
                        throw IOException("external_revision_intervened")
                    val json = com.google.gson.JsonParser.parseString(baseline).asJsonObject.apply {
                        addProperty("expectedRevision", current.revision)
                    }.toString()
                    val rollback = CandidateSnapshot(json, inputVersion, current.revision)
                    host.command("config validate", rollback)
                    val applied = host.command("config apply", rollback)
                    if (!host.freshStatus().matches(applied, before.phase)) throw IOException("rollback_not_confirmed")
                } catch (_: Exception) { restored = false }
                if (!restored) throw IOException("cleanup_required", error)
                try {
                    host.finish()
                } catch (finishError: Exception) {
                    throw IOException("cleanup_required", finishError)
                }
            }
            throw error
        }
    }

    private fun CandidateStatus.matches(applied: CandidateStatus, expectedPhase: String): Boolean =
        revision == applied.revision && phase == expectedPhase &&
            (expectedPhase != "connected" || runningRevision == applied.revision)
}
