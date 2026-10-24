// @author 雾晚
package io.nekohasekai.sagernet

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Complements behavioral routing/state tests with checks of their Android integration points. */
class ConnectedLatencyWiringTest {
    private fun source(path: String) = File("src/main/java/io/nekohasekai/sagernet/$path.kt").readText()
    // @author 雾晚：StatsBar 已删除，相关测试移除，诊断移至 Dashboard 诊断卡
    @Test fun binderDispatchesFromOwningServiceBeforeLazyBoxAndKeepsDefaultCustomPaths() {
        val binder = source("bg/BaseService").substringAfter("private fun measureUrl(")
            .substringBefore("override fun postNotificationSpeed")
        assertTrue(binder.contains("current.service as? RootTunService"))
        assertFalse(binder.contains("proxy?.service"))
        assertTrue(binder.indexOf("ConnectedUrlTest.measure") < binder.indexOf("current.proxy?.box"))
        assertTrue(binder.contains("current.state.connected, url, timeoutMs"))
        assertTrue(binder.contains("Libcore.urlTestFull(box, target, timeout)"))
        assertTrue(binder.contains("Libcore.urlTest(box, target, timeout)"))
        assertTrue(source("bg/BaseService").contains("DataStore.connectionTestURL, DataStore.connectionTestTimeout, false"))
        assertTrue(source("bg/BaseService").contains("measureUrl(url, timeoutMs, true)"))
    }
    @Test fun rootReadinessGuardsBothMixedProbeAndIsolatedFallback() {
        val root = source("bg/RootTunService").substringAfter("fun urlTest(").substringBefore("override fun reload()")
        assertTrue(root.contains("RootModuleClient.call(\"status\")"))
        assertTrue(root.contains("TEST_ERR_NOT_CONNECTED"))
        assertTrue(root.contains("TEST_ERR_SWITCHING"))
        assertTrue(root.contains("TEST_ERR_SWITCHED_DURING_TEST"))
        assertTrue(root.contains("after.profileId != targetProfileId"))
        assertTrue(root.contains("after.runningRevision != targetRevision"))
        assertFalse(root.contains("TestInstance("))
        assertTrue(root.contains("if (DataStore.mixedInboundDisabled)"))
        assertTrue(root.contains("ProxyUrlProbe.measure("))
        assertFalse(root.contains("proxy?.box"))
    }
}
