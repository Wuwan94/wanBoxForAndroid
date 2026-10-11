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
    override fun createNotification(profileName: String): ServiceNotification = error("Module observer does not own a foreground service")
    override fun acquireWakeLock() = Unit
    override fun currentProfileName() = snapshot?.profileName ?: ""
    override fun onBind(intent: Intent): android.os.IBinder? {
        if (observer?.isActive != true) observer = data.serviceScope.launch {
            val networkOwner = Any()
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
                try {
                    val status = RootModuleClient.call("status")
                    val changedError = snapshot?.error != status.error
                    val changedProfile = status.connected && (snapshot?.connected != true || snapshot?.profileId != status.profileId)
                    snapshot = status; DataStore.currentProfile = if (status.connected) status.profileId else 0
                    // @author 雾晚：一开始就用完整 dashboard 结果，后续全部用它
                    // 不先 status 再 dashboard，避免状态与数据显示两代次
                    val dashStatus = try {
                        RootModuleClient.call("dashboard")
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        null
                    } ?: return@launch
                    val status = dashStatus // 统一使用 dashboard 结果
                    DataStore.mixedInboundAuthed = status.connected && DataStore.mixedInboundNeedsAuth
                    data.changeState(status.state, if (changedError && status.error.isNotBlank())
                        RootModuleErrors.message(this@RootTunService, status.error) else null)
                    if (changedProfile) data.binder.broadcast { it.cbSelectorUpdate(status.profileId) }
                    val telemetry = status.telemetry
                    // 速度数据：优先用 dashboard 的 stats，缺失时用 0
                    val stats = dashStatus?.stats
                    val tx = stats?.tx ?: 0L
                    val rx = stats?.rx ?: 0L
                    val directTx = stats?.directTx ?: 0L
                    val directRx = stats?.directRx ?: 0L
                    // @author 雾晚：仅使用已验证的 runningProfileName
                    val runningName = dashStatus?.profileName?.takeIf { dashStatus.connected } ?: ""
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
                } catch (e: CancellationException) { throw e }
                  catch (_: Exception) {
                    snapshot = null; DataStore.currentProfile = 0; DataStore.mixedInboundAuthed = false
                    data.changeState(BaseService.State.Stopped)
                }
            }, isInteractive = { SagerNet.power.isInteractive })
            // Bound UI/tile only, never an App background watchdog.
        }
        return super<BaseService.Interface>.onBind(intent)
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
