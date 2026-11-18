package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedInterceptionTest {
    @Test
    fun defaultsAreClosed() {
        val cfg = SharedInterception.defaults()
        assertFalse(cfg.enabled)
        assertEquals("socket_assign", cfg.dataPlane)
        assertEquals("hijack", cfg.dnsMode)
        assertFalse(cfg.defaultInterface)
        assertEquals(300L, cfg.udpTimeoutSeconds)
        assertTrue(cfg.interfaces.isEmpty())
    }

    @Test
    fun parseAndSerializeRoundTrip() {
        val json = """{"enabled":true,"interface":["wlan1","rndis0"],
            "data_plane":"packet_rewrite","dns_mode":"hijack","default_interface":false,
            "udp_timeout":"30s","include_source_cidr":["192.168.43.0/24"],
            "exclude_mac_address":["02:11:22:33:44:55"]}"""
        val cfg = SharedInterception.parse(json).getOrThrow()
        assertTrue(cfg.enabled)
        assertEquals(listOf("wlan1", "rndis0"), cfg.interfaces)
        assertEquals("packet_rewrite", cfg.dataPlane)
        assertEquals(30L, cfg.udpTimeoutSeconds)
        val back = SharedInterception.parse(SharedInterception.serialize(cfg)).getOrThrow()
        assertEquals(cfg, back)
    }

    @Test
    fun rejectsInvalidConfigs() {
        listOf(
            // 开启但无下游接口
            """{"enabled":true,"interface":[]}""",
            // loopback 不得入选
            """{"enabled":true,"interface":["lo"]}""",
            // 非法数据平面
            """{"enabled":true,"interface":["wlan1"],"data_plane":"tc"}""",
            // 非法 dns_mode
            """{"enabled":true,"interface":["wlan1"],"dns_mode":"forward"}""",
            // udp_timeout 下限 5s
            """{"enabled":true,"interface":["wlan1"],"udp_timeout":"1s"}""",
            """{"enabled":true,"interface":["wlan1"],"udp_timeout":"soon"}""",
            // 非法 CIDR / MAC
            """{"enabled":true,"interface":["wlan1"],"include_source_cidr":["not-a-cidr"]}""",
            """{"enabled":true,"interface":["wlan1"],"exclude_mac_address":["zz:zz"]}""",
            // 重复接口
            """{"enabled":true,"interface":["wlan1","wlan1"]}""",
        ).forEach { assertTrue(it, SharedInterception.parse(it).isFailure) }
        // udp_timeout 恰好 5s 通过。
        assertTrue(SharedInterception.parse("""{"enabled":true,"interface":["wlan1"],"udp_timeout":"5s"}""").isSuccess)
    }

    @Test
    fun durationFormatRoundTrip() {
        assertEquals("5m", SharedInterception.formatDuration(300))
        assertEquals("30s", SharedInterception.formatDuration(30))
        assertEquals(300L, SharedInterception.parseDurationSeconds("5m"))
        assertEquals(30L, SharedInterception.parseDurationSeconds("30s"))
        assertEquals(90L, SharedInterception.parseDurationSeconds("90s"))
    }
}

class PerUserAppsTest {
    @Test
    fun parseRefAcceptsStrictFormOnly() {
        val ref = PerUserApps.parseRef("10:com.example.app")
        assertEquals(10, ref?.userId)
        assertEquals("com.example.app", ref?.packageName)
        assertEquals("10:com.example.app", ref?.key)
        listOf(
            "com.example.app", "0:", ":com.example.app", "99999:com.example.app",
            "-1:com.example.app", "0:Com.Example", "0:com.example.app:extra",
        ).forEach { assertEquals(it, null, PerUserApps.parseRef(it)) }
    }

    @Test
    fun migrateLegacyKeepsUnresolvableEntries() {
        val reverse: (Int) -> String? = { uid -> if (uid == 10123) "com.example.app" else null }
        val (migrated, retained) = PerUserApps.migrateLegacy(
            listOf("com.example.app", "10123", "10999", "10:com.clone.app", "com.example.app", "", "bad uid!!"),
            reverse,
        )
        assertEquals(
            listOf(PerUserApps.Ref(0, "com.example.app"), PerUserApps.Ref(10, "com.clone.app")),
            migrated,
        )
        assertEquals(listOf("10999", "bad uid!!"), retained)
    }

    @Test
    fun migrateLegacyWithoutReverseLookupRetainsUids() {
        val (migrated, retained) = PerUserApps.migrateLegacy(listOf("10123")) { null }
        assertTrue(migrated.isEmpty())
        assertEquals(listOf("10123"), retained)
    }

    @Test
    fun resolvedWhitelistMustNotSilentlyBecomeEmpty() {
        // 查询成功、但一个有效 UID 都没有（纯系统组件/已卸载）时不得静默变成
        // “不代理任何应用”：抛带本地化文案的异常，提示用户重新选择。
        val error = runCatching {
            PerUserApps.settleResolvedWhitelist(
                requestedCount = 2,
                unresolvedOtherUser = 0,
                resolvedUids = emptyList(),
                resolveError = null,
            )
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertFalse(error?.message.isNullOrBlank())
        // 没有请求条目时仍允许空表（分应用代理关闭/用户没选应用）。
        assertEquals(
            emptyList<Int>(),
            PerUserApps.settleResolvedWhitelist(0, 0, emptyList(), null),
        )
        assertEquals(
            listOf(10123),
            PerUserApps.settleResolvedWhitelist(1, 0, listOf(10123), null),
        )
    }

    @Test
    fun suTimeoutMustNotBlockStartup() {
        // P2-4：跨用户 su 查询超时/解析失败时，退回旧行为（只代理已解析到的
        // 部分），MUST NOT 因为“空白名单”拦截服务启动。
        assertEquals(
            emptyList<Int>(),
            PerUserApps.settleResolvedWhitelist(2, 2, emptyList(), "pm_timeout"),
        )
        assertEquals(
            listOf(10123),
            PerUserApps.settleResolvedWhitelist(2, 1, listOf(10123), "pm_failed"),
        )
    }

    @Test
    fun partialResolutionKeepsResolvedUids() {
        // 部分条目解析成功：照旧只代理解析到的部分，不因剩余条目报错。
        assertEquals(
            listOf(10123),
            PerUserApps.settleResolvedWhitelist(2, 1, listOf(10123), null),
        )
    }

    @Test
    fun serializeRefsRoundTrip() {
        val refs = listOf(PerUserApps.Ref(0, "com.a"), PerUserApps.Ref(10, "com.a"))
        assertEquals(
            refs,
            PerUserApps.parseRefs(PerUserApps.serializeRefs(refs)),
        )
    }
}
