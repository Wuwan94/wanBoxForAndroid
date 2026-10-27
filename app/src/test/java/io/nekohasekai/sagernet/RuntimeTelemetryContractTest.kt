// @author 雾晚
package io.nekohasekai.sagernet

import java.io.File
import org.junit.Assert.*
import org.junit.Test
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.ModuleTelemetry
import io.nekohasekai.sagernet.bg.TelemetrySession

/** Protect runtime boundaries during selective upstream sync; not device measurements. @author 雾晚 */
class RuntimeTelemetryContractTest {
    private fun source(path: String) = File("src/main/java/io/nekohasekai/sagernet/$path").readText()

    @Test fun automaticLifecycleEventsDoNotForceGarbageCollection() {
        listOf("SagerNet.kt", "ui/MainActivity.kt", "bg/BaseService.kt", "bg/proto/TrafficLooper.kt").forEach {
            val code = source(it)
            assertFalse(it, code.contains("System.gc()"))
            assertFalse(it, code.contains("Libcore.forceGc()"))
        }
        val service = source("bg/BaseService.kt")
        assertTrue(service.contains("Intent.ACTION_SCREEN_OFF ->")) // must not fall through to stopRunner
        assertTrue(service.contains("proxy?.box }.getOrNull()?.wake()"))
        assertTrue(service.contains("DataStore.wakeResetConnections"))
        assertTrue(source("SagerNet.kt").contains("cleanWebview()"))
        assertTrue(source("ui/MainActivity.kt").contains("CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND"))
        val core = File("../libcore/box.go").readText()
        assertFalse(core.contains("runtime.GC()"))
        assertFalse(core.contains("debug.FreeOSMemory()"))
        assertTrue(core.contains("b.cancel()"))
        assertTrue(core.contains("b.Box.Close()"))
    }

    @Test fun unreadyCoreSuspendsAndStaticMembershipIsNotRebuiltEverySample() {
        val loop = source("bg/proto/TrafficLooper.kt")
        assertFalse(loop.contains("if (!proxy.isInitialized()) continue"))
        assertTrue(loop.contains("TrafficPollPolicy.ready("))
        assertTrue(loop.contains("TrafficPollPolicy.intervalMs("))
        assertTrue(loop.contains("balancerMemberIds = proxy.config.balancerMemberMap"))
        assertFalse(loop.contains("val balancerMemberIds = proxy.config.balancerMemberMap"))
        assertTrue(loop.contains("job?.cancelAndJoin()"))
        assertTrue(loop.contains("currentCoroutineContext().ensureActive()"))
    }

    @Test fun vpnReportsPerInstanceTunHostsForApplicationRouteLookups() {
        val bridge = File("../libcore/platform_box.go").readText()
        assertTrue(bridge.contains("myTunAddress      []netip.Addr"))
        assertTrue(bridge.contains("tunInterfaceAddresses(options.Inet4Address, options.Inet6Address)"))
        assertTrue(bridge.contains("return w.myTunAddress"))
        assertTrue(bridge.contains("UserId: uid, PackageNames: packageNames"))
        // Root continues to use its standalone core; this does not add app-process ownership.
        val root = source("bg/RootTunService.kt")
        assertTrue(root.contains("RootModuleClient.call(\"status\")"))
        assertFalse(root.contains("launchExternalOnly"))
    }

    @Test fun telemetryDataDistinguishesZeroFromUnknownAndPreservesValidMetrics() {
        // 1. 真实 0 连接数与已知指标
        val validZeroConns = SpeedDisplayData(
            vmRSSBytes = 32 * 1024 * 1024L,
            uptimeSeconds = 120L,
            connectionCount = 0,
            txTotalModule = 5000L,
            rxTotalModule = 6000L
        )
        assertTrue(validZeroConns.hasTelemetry())
        assertEquals("真实 0 连接数必须返回 0 而不是 null", 0, validZeroConns.connectionCountOrNull())
        assertEquals(5000L, validZeroConns.txTotalModuleOrNull())
        assertEquals(6000L, validZeroConns.rxTotalModuleOrNull())

        // 2. 旧模块缺字段与未知 -1
        val unknown = SpeedDisplayData(
            vmRSSBytes = -1L,
            uptimeSeconds = -1L,
            connectionCount = -1,
            txTotalModule = -1L,
            rxTotalModule = -1L
        )
        assertFalse(unknown.hasTelemetry())
        assertNull("未知 -1 连接数必须返回 null 以便 UI 渲染破折号", unknown.connectionCountOrNull())
        assertNull(unknown.txTotalModuleOrNull())
        assertNull(unknown.rxTotalModuleOrNull())

        // 3. ModuleTelemetry 默认构造全 null，未知不冒充 0
        val emptyTelemetry = ModuleTelemetry()
        assertNull(emptyTelemetry.vmRSSBytes)
        assertNull(emptyTelemetry.uptimeSeconds)
        assertNull(emptyTelemetry.connectionCount)
        assertFalse(emptyTelemetry.hasData())
    }

    @Test fun telemetrySessionBehavioralStateTransitionsAndLateCallbackRejection() {
        val session = TelemetrySession()
        assertEquals(BaseService.State.Idle, session.serviceState)
        assertNull(session.lastSpeedStats)
        assertEquals(0L, session.connectedSince)

        // 场景 1：服务连接成功并产生采样
        session.onStateChanged(BaseService.State.Connected, 10000L)
        assertEquals(BaseService.State.Connected, session.serviceState)
        assertEquals(10000L, session.connectedSince)
        assertNull("刚连上尚未收到新采样前必须为 null", session.lastSpeedStats)

        val sample1 = SpeedDisplayData(vmRSSBytes = 20 * 1024 * 1024L, uptimeSeconds = 10L, connectionCount = 2)
        val accepted1 = session.onSpeedUpdate(sample1)
        assertTrue(accepted1)
        assertSame(sample1, session.lastSpeedStats)

        // 场景 2：服务停止 (Stopped)，状态与旧样本必须立即清空
        session.onStateChanged(BaseService.State.Stopped, 15000L)
        assertEquals(BaseService.State.Stopped, session.serviceState)
        assertNull("停止后上一周期的样本必须被清理", session.lastSpeedStats)
        assertEquals(0L, session.connectedSince)

        // 场景 3：迟到的旧回调在 Stopped 状态到达，必须被拒绝并丢弃
        val lateSample = SpeedDisplayData(vmRSSBytes = 25 * 1024 * 1024L, uptimeSeconds = 15L, connectionCount = 5)
        val acceptedLate1 = session.onSpeedUpdate(lateSample)
        assertFalse("非 Connected 状态下的迟到旧回调必须被拒绝", acceptedLate1)
        assertNull("迟到旧回调绝不能写回已清空的会话状态", session.lastSpeedStats)

        // 场景 4：重新连接中 (Connecting)
        session.onStateChanged(BaseService.State.Connecting, 16000L)
        assertEquals(BaseService.State.Connecting, session.serviceState)
        assertNull("Connecting 状态下不能持有旧样本", session.lastSpeedStats)

        // 迟到回调在 Connecting 状态到达，同样必须被拒绝
        val acceptedLate2 = session.onSpeedUpdate(lateSample)
        assertFalse("Connecting 状态下的迟到回调必须被拒绝", acceptedLate2)
        assertNull(session.lastSpeedStats)

        // 场景 5：重连成功进入 Connected，但尚未产生新采样
        session.onStateChanged(BaseService.State.Connected, 20000L)
        assertEquals(BaseService.State.Connected, session.serviceState)
        assertEquals(20000L, session.connectedSince)
        assertNull("重连进入 Connected 但尚未收到当前代次采样前，必须保持为 null，不能展示上一代次旧值", session.lastSpeedStats)

        // 当前代次的新采样到达
        val sample2 = SpeedDisplayData(vmRSSBytes = 22 * 1024 * 1024L, uptimeSeconds = 1L, connectionCount = 0)
        val accepted2 = session.onSpeedUpdate(sample2)
        assertTrue(accepted2)
        assertSame(sample2, session.lastSpeedStats)
        assertEquals(0, session.lastSpeedStats?.connectionCountOrNull())

        // 场景 6：Binder 异常断开 (onDisconnect)
        session.onDisconnect()
        assertEquals(BaseService.State.Idle, session.serviceState)
        assertNull(session.lastSpeedStats)
        assertEquals(0L, session.connectedSince)
    }

    @Test fun dashboardUiWiringDoesNotHardcodeMetricsAndCleansUpViewScope() {
        val dashboard = source("ui/DashboardFragment.kt")
        assertFalse("coreMemory 严禁硬编码破折号", dashboard.contains("text(R.id.coreMemory, \"—\")"))
        assertFalse("connectionCount 严禁硬编码破折号", dashboard.contains("text(R.id.connectionCount, \"—\")"))
        assertTrue("coreMemory 必须通过 vmRSSBytes 判定有效内存", dashboard.contains("it.vmRSSBytes > 0L"))
        assertTrue("connectionCount 必须使用 connectionCountOrNull 区分真实 0 与未知", dashboard.contains("connectionCountOrNull()"))
        assertTrue("uptimeText 必须使用模块 uptimeSeconds 格式化", dashboard.contains("uptimeSeconds"))
        assertTrue("view 销毁必须显式清空 statsJob 避免生命周期泄漏", dashboard.contains("statsJob = null"))
        assertTrue("view 销毁必须显式清空 nodeJob", dashboard.contains("nodeJob = null"))

        val main = source("ui/MainActivity.kt")
        assertTrue("MainActivity 必须集成 TelemetrySession 托管会话生命周期", main.contains("telemetrySession"))
    }
}
