// @author 雾晚
package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.core.CoreRuntime
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity

/** Node-core probes, bounded and retried by NodeTestRunner. @author 雾晚 */
class UrlTest(private val overrideLink: String? = null) {
    private val runningProfileTags = RunningProfileTagResolver()
    private val controllerDelayClient = ThinControllerDelayClient()

    fun resolveLink(profile: ProxyEntity): String {
        if (!overrideLink.isNullOrBlank()) return overrideLink
        return DataStore.groupUrlTestUrl(profile.groupId)
    }

    suspend fun doTest(profile: ProxyEntity, useCache: Boolean = true): Int {
        // @author 雾晚: reuse fresh results (TTL cache) instead of re-probing.
        // Single-node manual tests pass useCache=false for a guaranteed fresh probe.
        if (useCache) UrlTestCache.get(profile.id)?.let { return it }
        val link = resolveLink(profile)
        val result = if (!CoreRuntime.available) {
            NodeTestRunner.measure(link, retryable = {
                it !is ProfileNotInRunningConfigException && it !is IllegalArgumentException
            }) { target, timeout ->
                val tag = runningProfileTags.tagFor(profile.id)
                controllerDelayClient.measure(tag, target, timeout)
            }
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
}
