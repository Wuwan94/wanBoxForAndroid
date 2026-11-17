package io.nekohasekai.sagernet.group.editor

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException

/** Seams for the explicit recovery action, independently testable without root or Android. */
internal interface GroupRecoveryHost {
    fun pending(): Boolean
    fun baseline(): String?
    fun restoreLocal()
    fun clearJournal()
    suspend fun status(): CandidateStatus
    suspend fun command(command: String, candidate: CandidateSnapshot): CandidateStatus
}

internal object GroupDraftRecovery {
    /** Must run under the same mutation lock as apply. Never starts the core. */
    suspend fun recover(host: GroupRecoveryHost): CandidateStatus {
        if (!host.pending()) return host.status()
        val before = host.status()
        if (before.phase !in setOf("connected", "stopped", "suspended")) throw IOException("module_not_ready")
        val baseline = JsonParser.parseString(host.baseline() ?: throw IOException("baseline_unavailable"))
            .asJsonObject.apply { addProperty("expectedRevision", before.revision) }
        // Validate the complete saved baseline before touching local state.
        val candidate = CandidateSnapshot(baseline.toString(), "", before.revision)
        host.command("config validate", candidate)
        host.restoreLocal()
        val applied = host.command("config apply", candidate)
        val confirmed = host.status()
        if (confirmed.revision != applied.revision || confirmed.phase != before.phase ||
            (before.phase == "connected" && confirmed.runningRevision != applied.revision))
            throw IOException("rollback_not_confirmed")
        host.clearJournal()
        return confirmed
    }
}

internal interface GroupAbandonHost {
    fun pending(): Boolean
    fun restoreLocal()
    fun clearJournal()
    suspend fun rebuild()
}

/** Explicit "discard unfinished edit": local state first, then the marker, then a normal rebuild. */
internal object GroupDraftAbandon {
    /** Must run under the module mutation lock. A failed local restore keeps the journal. */
    suspend fun abandon(host: GroupAbandonHost) {
        if (!host.pending()) return
        try {
            host.restoreLocal()
            host.clearJournal()
        } catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (error: Exception) { throw IOException("group_editor_recovery_required", error) }
        host.rebuild()
    }
}

/** A second status read closes the race between snapshot transport and capture. */
internal object EditorBaselineReader {
    suspend fun read(revision: String, bytes: suspend () -> ByteArray,
        currentRevision: suspend () -> String): JsonObject {
        if (!revision.matches(Regex("[a-f0-9]{64}"))) throw IOException("baseline_revision_invalid")
        val payload = bytes()
        if (payload.size > 192 * 1024 * 1024) throw IOException("snapshot_too_large")
        if (currentRevision() != revision) throw IOException("revision_conflict")
        return JsonParser.parseString(String(payload, Charsets.UTF_8)).asJsonObject.apply {
            addProperty("expectedRevision", revision)
        }
    }
}
