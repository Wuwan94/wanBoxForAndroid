package io.nekohasekai.sagernet.bg

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import android.util.AtomicFile
import io.nekohasekai.sagernet.route.OutboundReferenceValidator

/** Sanitized diagnostic snapshot, never a config or proxy entity. */
data class OutboundReferenceWarning(
    val requestToken: String,
    val startIfStopped: Boolean,
    val issues: List<OutboundReferenceValidator.Issue>,
) {
    data class PendingReview(val requestId: String, val startIfStopped: Boolean)

    companion object {
        const val ACTION_REQUIRED = "io.nekohasekai.sagernet.action.OUTBOUND_REFERENCE_WARNING_REQUIRED"
        const val EXTRA_START_IF_STOPPED = "start_if_stopped"
        const val EXTRA_REQUEST_ID = "review_request_id"

        /** Coalesce outstanding requests to the latest intent; persist no diagnostic payload. */
        fun queuePendingReview(context: Context, startIfStopped: Boolean): PendingReview =
            PendingReview(UUID.randomUUID().toString(), startIfStopped).also {
                persistPendingReview(context, it)
            }

        fun persistPendingReview(context: Context, pending: PendingReview) = withPendingReview(context) { file ->
            val output = file.startWrite()
            try {
                val data = java.io.DataOutputStream(output)
                data.writeUTF(pending.requestId)
                data.writeBoolean(pending.startIfStopped)
                data.flush()
                output.fd.sync()
                file.finishWrite(output)
            } catch (error: Exception) {
                file.failWrite(output)
                throw error
            }
        }

        fun peekPendingReview(context: Context): PendingReview? = withPendingReview(context) { readPending(it) }

        fun clearPendingReview(context: Context, requestId: String): Boolean = withPendingReview(context) { file ->
            if (readPending(file)?.requestId != requestId) false else {
                file.delete()
                true
            }
        }

        private fun readPending(file: AtomicFile): PendingReview? = try {
            java.io.DataInputStream(file.openRead()).use {
                PendingReview(it.readUTF(), it.readBoolean())
            }
        } catch (_: java.io.FileNotFoundException) { null }

        // Serialize threads before taking the OS lock (overlapping JVM locks throw rather than wait).
        // Both app processes open the same private, non-backed-up file, with no cached preferences.
        @Synchronized
        private fun <T> withPendingReview(context: Context, block: (AtomicFile) -> T): T =
            RandomAccessFile(File(context.noBackupFilesDir, "outbound_pending_review"), "rw").use { file ->
                file.channel.lock().use {
                    block(AtomicFile(File(context.noBackupFilesDir, "outbound_pending_review_value")))
                }
            }
    }
}
