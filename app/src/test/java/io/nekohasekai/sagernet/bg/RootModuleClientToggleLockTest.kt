package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RootModuleClientToggleLockTest {
    @Test
    fun toggleTransactionIsRejectedWhileAnotherModuleChangeOwnsTheLock() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val currentChange = async {
            RootModuleClient.dataUpdate {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()

        var toggleActionRan = false
        try {
            val error = runCatching {
                RootModuleClient.tryWithChangesLock { toggleActionRan = true }
            }.exceptionOrNull()
            assertEquals("module_busy", error?.message)
            assertFalse(toggleActionRan)
        } finally {
            release.complete(Unit)
            currentChange.await()
        }
    }
}
