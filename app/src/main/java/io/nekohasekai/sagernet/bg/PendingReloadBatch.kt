package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.channels.Channel

/** Bounded pending edits. Closing a batch assigns all accepted edits to one Apply. */
internal class PendingReloadBatch(private val defaultOnError: (Exception) -> Unit) {
    private val lock = Any()
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private var handler: ((Exception) -> Unit)? = null
    private var urgent = false

    fun request(immediate: Boolean = false, onError: ((Exception) -> Unit)? = null): Boolean = synchronized(lock) {
        // A receiver may resume inline (Unconfined); publish state before waking it.
        if (immediate) urgent = true
        if (onError != null) handler = onError
        if (!signals.trySend(Unit).isSuccess) {
            handler = null
            urgent = false
            return false
        }
        true
    }

    suspend fun receive() = signals.receiveCatching().isSuccess
    fun poll(): Boolean = signals.tryReceive().isSuccess
    fun takeUrgency(): Boolean = synchronized(lock) { urgent.also { urgent = false } }

    fun closeBatch(): (Exception) -> Unit = synchronized(lock) {
        // Requests accepted before this boundary are read by the imminent Apply.
        // Consume their signal, urgency and handler together; later requests keep all three.
        signals.tryReceive()
        urgent = false
        (handler ?: defaultOnError).also { handler = null }
    }

    fun cancel() = synchronized(lock) {
        signals.cancel()
        handler = null
        urgent = false
    }
}
