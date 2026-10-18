// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.bg
import android.app.Service
import android.content.Intent
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.proto.TestInstance
import kotlinx.coroutines.*
/** Bound UI observer only; destroy/unbind never stops the module. @author 雾晚 */
class RootTunService : Service(), BaseService.Interface {
    override val data = BaseService.Data(this)
    override val tag = "WanBoxModuleObserver"
    override val powerLocks = ServicePowerLocks()
    override var upstreamInterfaceName: String? = null
    private var observer: Job? = null
    @Volatile private var snapshot: RootModuleClient.Status? = null
    // @author 雾晚: 单次采样失败不等于服务断开。节点热切换会并发拉起 su，
    // 偶发超时若立刻置 Stopped，按钮与节点名会先灭后亮，看起来像重启。
    private val samplingFailures = SamplingFailurePolicy(threshold = 3)

    /** 失败原因写日志的节流计数：首次必写，之后每 20 次写一条。 */
    private var failureLogCount = 0
    override fun createNotification(profileName: String): ServiceNotification = error("Module observer does not own a foreground service")
    override fun acquireWakeLock() = Unit
    override fun currentProfileName() = snapshot?.profileName ?: ""
    @Volatile private var sseActive = false
    override fun onBind(intent: Intent): android.os.IBinder? {
        if (observer?.isActive != true) observer = data.serviceScope.launch {
            val networkOwner = Any()
            // @author 雾晚：SSE 是首选状态通道，模块会推送完整快照；连接失败或模块
            // 仍是旧版时无感回退到 su 轮询，不改变既有行为。
            val stream = launch { collectStatusEvents() }
            try {
                RootModuleObservation.run(network = {
                    try {
                        io.nekohasekai.sagernet.utils.DefaultNetworkListener.start(networkOwner) { network ->
                            SagerNet.underlyingNetwork = network
                        }
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) { io.nekohasekai.sagernet.utils.DefaultNetworkListener.stop(networkOwner) }
                    }
                }, sample = {
                    // SSE 在线时由推送驱动，轮询只做兜底，避免两条通道重复写入状态。
                    if (!sseActive) sampleStatus()
                }, intervalMs = 5000, isInteractive = { SagerNet.power.isInteractive })
            } finally {
                stream.cancel()
            }
            // Bound UI/tile only, never an App background watchdog.
        }
        return super<BaseService.Interface>.onBind(intent)
    }

    /** 经模块 HTTPS 事件流接收状态；断开或不可用时退出，由轮询接管。 */
    private suspend fun collectStatusEvents() {
        try {
            ModuleApiClient.events().collect { status ->
                sseActive = true
                samplingFailures.onSuccess()
                applyStatus(status)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 模块未升级、凭据缺失或流断开：保持轮询路径。
        } finally {
            sseActive = false
        }
    }

    private suspend fun sampleStatus() {
        try {
            val status = RootModuleClient.call("dashboard")
            samplingFailures.onSuccess()
            applyStatus(status)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 失败原因必须进日志：否则导出的日志里只剩「模块服务暂时不可用」，
            // 无法区分「凭据文件缺失 / 连接被拒 / 证书不匹配 / 接口 404」。
            failureLogCount++
            if (failureLogCount == 1 || failureLogCount % 20 == 0) {
                io.nekohasekai.sagernet.ktx.Logs.w(
                    "module status sample failed (#$failureLogCount): " +
                        (e.message ?: e.javaClass.simpleName), e
                )
            }
            // @author 雾晚：连续失败才降级。真实停服由 status 的 phase=stopped
            // 正常返回，不会走到这里，因此延迟降级不会掩盖服务已停止。
            if (samplingFailures.onFailure()) {
                snapshot = null; DataStore.currentProfile = 0; DataStore.mixedInboundAuthed = false
                data.changeState(BaseService.State.Stopped)
            }
        }
    }

    /** 状态快照的唯一应用点：轮询与 SSE 共用，保证两条通道行为一致。 */
    private suspend fun applyStatus(status: RootModuleClient.Status) {
        // @author 雾晚：只在最开头用完整结果
        // changedError/changedProfile/snapshot/currentProfile/mixedInboundAuthed
        // changeState/cbSelectorUpdate/telemetry/stats/runningName 全部从这个 status 取值
        // 不先 status 再 dashboard，避免两代次混合
        val changedError = snapshot?.error != status.error
        val changedProfile = status.connected && (snapshot?.connected != true || snapshot?.profileId != status.profileId)
        snapshot = status; DataStore.currentProfile = if (status.connected) status.profileId else 0
        DataStore.mixedInboundAuthed = status.connected && DataStore.mixedInboundNeedsAuth
        data.changeState(status.state, if (changedError && status.error.isNotBlank())
            RootModuleErrors.message(this, status.error) else null)
        if (changedProfile) data.binder.broadcast { it.cbSelectorUpdate(status.profileId) }
        val telemetry = status.telemetry
        // 速度数据：用模块上报的 stats，缺失时用 0
        val stats = status.stats
        val tx = stats?.tx ?: 0L
        val rx = stats?.rx ?: 0L
        val directTx = stats?.directTx ?: 0L
        val directRx = stats?.directRx ?: 0L
        // @author 雾晚：仅使用已验证的 runningProfileName
        val runningName = status.profileName.takeIf { status.connected } ?: ""
        data.binder.broadcast {
            it.cbSpeedUpdate(io.nekohasekai.sagernet.aidl.SpeedDisplayData(
                txRateProxy = tx,
                rxRateProxy = rx,
                txRateDirect = directTx,
                rxRateDirect = directRx,
                txTotal = 0L,
                rxTotal = 0L,
                vmRSSBytes = telemetry?.vmRSSBytes ?: -1L,
                uptimeSeconds = telemetry?.uptimeSeconds ?: -1L,
                txTotalModule = telemetry?.txBytes ?: -1L,
                rxTotalModule = telemetry?.rxBytes ?: -1L,
                connectionCount = telemetry?.connectionCount ?: -1,
                runningProfileName = runningName
            ))
        }
    }
    // preview.17: 连接测试详细错误状态（负数返回码）
    // UI 层根据返回码显示具体错误信息，而非一律"网络不可用"
    companion object {
        const val TEST_OK_MIN = 1 // >=1 表示成功，值为延迟 ms
        const val TEST_FAIL_GENERIC = 0
        const val TEST_ERR_NOT_CONNECTED = -1 // 模块未连接
        const val TEST_ERR_SWITCHING = -2 // 节点切换中
        const val TEST_ERR_SWITCHED_DURING_TEST = -3 // 测试期间节点已切换
        const val TEST_ERR_PROFILE_NOT_FOUND = -4 // 节点配置不存在
        const val TEST_ERR_PROXY_ENTRY = -5 // 本地代理入口不可用
        const val TEST_ERR_HANDSHAKE_TIMEOUT = -6 // 代理握手超时
        const val TEST_ERR_DNS_FAILED = -7 // DNS 解析失败
        const val TEST_ERR_EGRESS_UNREACHABLE = -8 // 网络出口不可达
        const val TEST_ERR_OTHER = -9 // 其他错误
    }
    fun urlTest(url: String, timeoutMs: Int): Int = runBlocking(Dispatchers.IO) {
        RootModuleClient.invalidateStatusCache()
        val before = try {
            RootModuleClient.call("status")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return@runBlocking TEST_ERR_OTHER
        }
        if (!before.connected) return@runBlocking TEST_ERR_NOT_CONNECTED
        if (before.profileId <= 0L) return@runBlocking TEST_ERR_PROFILE_NOT_FOUND
        if (before.profileId != DataStore.selectedProxy) return@runBlocking TEST_ERR_SWITCHING
        val targetProfileId = before.profileId
        val targetRevision = before.runningRevision
        val result = try {
            if (DataStore.mixedInboundDisabled) {
                val profile = SagerDatabase.proxyDao.getById(targetProfileId)
                    ?: return@runBlocking TEST_ERR_PROFILE_NOT_FOUND
                TestInstance(profile, url, timeoutMs).doTest()
            } else {
                if (DataStore.mixedPort <= 0) return@runBlocking TEST_ERR_PROXY_ENTRY
                io.nekohasekai.sagernet.utils.ProxyUrlProbe.measure(
                    url, DataStore.mixedPort, timeoutMs,
                    DataStore.mixedUsername.takeIf { DataStore.mixedInboundNeedsAuth },
                    DataStore.mixedPassword
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: java.net.SocketTimeoutException) {
            return@runBlocking TEST_ERR_HANDSHAKE_TIMEOUT
        } catch (_: java.net.UnknownHostException) {
            return@runBlocking TEST_ERR_DNS_FAILED
        } catch (_: java.net.ConnectException) {
            return@runBlocking TEST_ERR_PROXY_ENTRY
        } catch (e: java.io.IOException) {
            val message = e.message.orEmpty()
            return@runBlocking when {
                message.contains("dns", ignoreCase = true) -> TEST_ERR_DNS_FAILED
                message.contains("timeout", ignoreCase = true) -> TEST_ERR_HANDSHAKE_TIMEOUT
                message.contains("refused", ignoreCase = true) -> TEST_ERR_PROXY_ENTRY
                else -> TEST_ERR_EGRESS_UNREACHABLE
            }
        } catch (_: Exception) {
            return@runBlocking TEST_ERR_OTHER
        }
        RootModuleClient.invalidateStatusCache()
        val after = try {
            RootModuleClient.call("status")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return@runBlocking TEST_ERR_OTHER
        }
        if (!after.connected) return@runBlocking TEST_ERR_NOT_CONNECTED
        if (after.profileId != targetProfileId || after.runningRevision != targetRevision) {
            return@runBlocking TEST_ERR_SWITCHED_DURING_TEST
        }
        if (after.profileId != DataStore.selectedProxy) return@runBlocking TEST_ERR_SWITCHING
        if (result > 0) result else TEST_FAIL_GENERIC
    }
    override fun reload() { SagerNet.reloadService() }
    override fun startRunner() { SagerNet.startService() }
    override fun stopRunner(restart: Boolean, msg: String?) { if (restart) SagerNet.reloadService() else SagerNet.stopService() }
    override fun onUnbind(intent: Intent?): Boolean { observer?.cancel(); observer = null; stopSelf(); return super.onUnbind(intent) }
    override fun onDestroy() { observer?.cancel(); data.serviceScope.cancel(); data.binder.close(); super.onDestroy() }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY
}
