// @author 雾晚
package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.bg.RootModuleClient
import io.nekohasekai.sagernet.core.CoreRuntime
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.buildConfig
import kotlinx.coroutines.sync.Mutex
import java.io.IOException

// P2：停核测速会在模块目录里拉起短生命周期 rootbox；多个测速并发时会同时
// 起多个 rootbox 共享同一目录。用非阻塞互斥把 Root 测试会话串行化，占用时
// 明确失败，而不是排队后一起压垮模块。
private val rootTestSessionGate = Mutex()

/** Node-core probes, bounded and retried by NodeTestRunner. @author 雾晚 */
class UrlTest internal constructor(
    private val overrideLink: String? = null,
    private val runningProfileTags: RunningProfileTagResolver = RunningProfileTagResolver(),
    private val controllerDelayClient: ThinControllerDelayClient = ThinControllerDelayClient(),
    // B5（N1）：停止态 Root 测试会话。默认实现用 App 生成的最小测试配置
    // （forTest=true）调 wanboxctl；插件出站（external chains）本批不支持，
    // 直接以 url_test_plugin_unsupported 拒绝。
    private val rootTestSession: suspend (ProxyEntity, String) -> Int = { profile, link ->
        val built = buildConfig(profile, true)
        if (built.externalIndex.isNotEmpty()) throw IOException("url_test_plugin_unsupported")
        RootModuleClient.urlTest(profile.id, built.config, link, NodeTestRunner.resolveTimeoutMs())
    },
) {
    fun resolveLink(profile: ProxyEntity): String {
        if (!overrideLink.isNullOrBlank()) return overrideLink
        return DataStore.resolvedGroupUrlTestUrl(profile.groupId)
    }

    suspend fun doTest(profile: ProxyEntity, useCache: Boolean = true): Int {
        // @author 雾晚: reuse fresh results (TTL cache) instead of re-probing.
        // Single-node manual tests pass useCache=false for a guaranteed fresh probe.
        if (useCache) UrlTestCache.get(profile.id)?.let { return it }
        val link = resolveLink(profile)
        val result = if (!CoreRuntime.available) {
            thinTest(profile, link)
        } else {
            NodeTestRunner.measure(link, retryable = {
                it !is io.nekohasekai.sagernet.plugin.PluginManager.PluginNotFoundException &&
                    it !is IllegalArgumentException
            }) { target, timeout ->
                // Each retry owns a fresh core; TestInstance closes it before the next attempt.
                TestInstance(profile, target, timeout).doTest()
            }
        }
        UrlTestCache.put(profile.id, result)
        return result
    }

    // B5（N1）：瘦管理器先走运行态 Controller 快路径；核心未连接或节点不在
    // 运行配置里时（停止态），改走 Root 侧短生命周期测试会话，不碰运行态。
    private suspend fun thinTest(profile: ProxyEntity, link: String): Int {
        return try {
            NodeTestRunner.measure(link, retryable = {
                it !is ProfileNotInRunningConfigException && it !is IllegalArgumentException
            }) { target, timeout ->
                val tag = runningProfileTags.tagFor(profile.id)
                controllerDelayClient.measure(tag, target, timeout)
            }
        } catch (e: IOException) {
            if (e.message == "core_not_connected" || e is ProfileNotInRunningConfigException) {
                if (!rootTestSessionGate.tryLock()) {
                    throw IOException("url_test_busy")
                }
                try {
                    rootTestSession(profile, link)
                } finally {
                    rootTestSessionGate.unlock()
                }
            } else throw e
        }
    }
}
