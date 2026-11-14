package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.CancellationException

internal val smartAutoStopPhases = setOf("suspended", "connected", "stopped", "failed", "disabled")

interface SmartAutoStopOperations {
    suspend fun resume()
    suspend fun stop()
    suspend fun status(): String
}

/** Shared receiver policy; an unreadable status must preserve the last notification. */
class SmartAutoStopController(
    private val operations: SmartAutoStopOperations,
    private val reconcile: (String) -> Unit,
) {
    fun onPhase(phase: String?) {
        if (phase in smartAutoStopPhases) reconcile(phase!!)
    }

    suspend fun onAction(action: String?) {
        if (action != "resume" && action != "stop") return
        try {
            if (action == "resume") operations.resume() else operations.stop()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The command may have succeeded despite a lost reply. Read authoritative state.
        }
        val phase = try {
            operations.status()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Preserve the last known suspended notification on communication failure.
            return
        }
        onPhase(phase)
    }
}
