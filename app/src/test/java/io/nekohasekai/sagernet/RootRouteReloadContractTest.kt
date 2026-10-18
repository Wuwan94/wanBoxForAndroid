// @author 雾晚
package io.nekohasekai.sagernet

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Configuration ownership regression; native apply/rollback has separate executable tests. @author 雾晚 */
class RootRouteReloadContractTest {
    private fun source(path: String) = File("src/main/java/io/nekohasekai/sagernet/$path").readText()
    @Test fun ruleSaveAndDeleteRebuildSnapshotInsteadOfRestartingOldConfiguration() {
        val restart = source("SagerNet.kt").substringAfter("fun restartService()").substringBefore("fun stopService()")
        assertTrue(restart.contains("configurationReload.request()"))
        assertTrue(source("SagerNet.kt").contains("startOrReload(startIfStopped = false)"))
        assertFalse(restart.contains("call(\"restart\")"))
        val client = source("bg/RootModuleClient.kt").substringAfter("suspend fun startOrReload(").substringBefore("suspend fun stop()")
        assertTrue(client.contains("if (!startIfStopped) changes.lock()"))
        assertFalse(client.contains("if (onlyIfRunning && !before.state.canStop) return"))
        assertTrue(client.substringAfter("ModuleApiClient.applyConfig(").contains("if (startIfStopped)"))
        assertTrue(client.contains("ModuleConfigIntent.build(profile)"))
        for (path in listOf("ui/RouteFragment.kt", "ui/RouteSettingsActivity.kt")) {
            val source = source(path)
            assertTrue(source.contains("SagerNet.restartService()"))
            assertFalse(source.contains("if (DataStore.serviceState.started)"))
        }
    }
    // @author 雾晚：StatsBar 已删除，相关测试移除
}
