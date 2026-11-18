// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.utils.LandingIpCache
import io.nekohasekai.sagernet.utils.LandingIpInfo
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** B1/O9a 落地 IP 缓存失效测试：缓存按实际在跑的节点键入，组内成员切换不得
 * 在 60 秒 TTL 内返回旧成员的出口 IP。@author 雾晚 */
class LandingIpCacheInvalidationTest {

    private fun info(ip: String) = LandingIpInfo(
        ip = ip, country = "US", countryCode = "US", countryFlag = "🇺🇸",
        city = "", region = "", isp = "", org = "", asn = "", durationMs = 0L,
    )

    @Test fun cacheIsKeyedByRunningProfile() {
        var now = 0L
        val cache = LandingIpCache(now = { now })
        // 成员 M1 的出口 IP 存入缓存
        val ticket = cache.ticket(11L)
        assertTrue(cache.put(ticket, info("1.1.1.1")))
        assertEquals("1.1.1.1", cache.get(11L)?.ip)
        // 组内切到成员 M2 后，按 M2 读必须 miss（不得返回 M1 的旧出口）
        assertNull(cache.get(12L))
        // 切回 M1 仍能读到（同一成员、未过期）
        assertEquals("1.1.1.1", cache.get(11L)?.ip)
        // 60 秒 TTL 过期后失效
        now = 60_000_000_000L
        assertNull(cache.get(11L))
    }

    @Test fun clearDropsEntryImmediately() {
        val cache = LandingIpCache()
        val ticket = cache.ticket(11L)
        assertTrue(cache.put(ticket, info("1.1.1.1")))
        cache.clear()
        assertNull(cache.get(11L))
    }

    @Test fun getCachedInfoReadsRunningProfile() {
        val source = File("src/main/java/io/nekohasekai/sagernet/utils/LandingIpManager.kt").readText()
        // 读缓存用 currentProfile（实际在跑的成员），不是 selectedProxy（组 id）
        assertTrue(source.contains("fun getCachedInfo(): LandingIpInfo? = cache.get(DataStore.currentProfile)"))
        assertFalse(source.contains("cache.get(DataStore.selectedProxy)"))
    }

    @Test fun selectorUpdateAlwaysClearsCache() {
        val source = File("src/main/java/io/nekohasekai/sagernet/ui/MainActivity.kt").readText()
        val body = source.substringAfter("override fun cbSelectorUpdate(id: Long)")
            .substringBefore("\n    override fun ", "")
        assertTrue(body.contains("LandingIpManager.clearCache()"))
        // 旧守卫（只在 id == selectedProxy 时清）漏掉了组内成员切换，必须去掉
        assertFalse(body.contains("if (id == DataStore.selectedProxy) LandingIpManager.clearCache()"))
    }
}
