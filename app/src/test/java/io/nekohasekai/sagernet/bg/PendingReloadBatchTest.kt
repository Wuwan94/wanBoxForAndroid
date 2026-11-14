package io.nekohasekai.sagernet.bg

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class PendingReloadBatchTest {
    @Test fun closeCoversEditsAfterQuietTimeoutWithoutStealingNextBatchHandler() {
        val reports = mutableListOf<String>()
        val pending = PendingReloadBatch { reports += "default:${it.message}" }
        pending.request()
        assertTrue(pending.poll()) // Worker consumed initial signal and its quiet timeout expired.
        pending.request(immediate = true, onError = { reports += "covered:${it.message}" })
        // This edit arrived after timeout but before close: current Apply reads its state.
        val currentFailure = pending.closeBatch()
        currentFailure(IOException("first"))
        assertEquals(listOf("covered:first"), reports)
        assertFalse("Covered edit must not schedule an Apply with no handler", pending.poll())
        assertFalse("Covered urgency must not skip the next quiet window", pending.takeUrgency())

        pending.request(onError = { reports += "next:${it.message}" })
        assertTrue(pending.poll())
        val nextFailure = pending.closeBatch()
        nextFailure(IOException("second"))
        assertEquals(listOf("covered:first", "next:second"), reports)
        pending.request()
        assertTrue(pending.poll())
        pending.closeBatch()(IOException("third"))
        assertEquals(listOf("covered:first", "next:second", "default:third"), reports)
    }
}
