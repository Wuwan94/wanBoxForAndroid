package io.nekohasekai.sagernet.fmt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootEbpfInboundTest {
    @Test
    fun uidPolicyMatchesRootTunAllowlistAndBypassSemantics() {
        assertEquals(
            RootEbpfUidFilter(includeUids = listOf(10001, 10002, 1000, 0)),
            rootEbpfUidFilter(true, false, listOf(10001, 10002, 10001), 1000, 1),
        )
        assertEquals(
            RootEbpfUidFilter(excludeUids = listOf(10001, 10002, 0)),
            rootEbpfUidFilter(true, true, listOf(10001, 10002, 10001), 1000, 2),
        )
        assertEquals(RootEbpfUidFilter(), rootEbpfUidFilter(false, false, emptyList(), 1000, 0))
        assertEquals(RootEbpfUidFilter(excludeUids = listOf(0)), rootEbpfUidFilter(false, false, emptyList(), 1000, 2))
    }

    @Test
    fun inboundSerializesForkOptionsWithoutLosingLogicalRouteTag() {
        val map = buildRootEbpfInbound(
            includeUids = listOf(10001, 1000),
            excludeUids = null,
            ipv6 = false,
            bypassPrivateAddress = true,
        ).asMap()

        assertEquals("ebpf", map["type"])
        assertEquals("tun-in", map["tag"])
        assertEquals(listOf("tcp", "udp"), map["network"])
        val local = map["local"] as Map<*, *>
        assertEquals(true, local["enabled"])
        assertEquals("cgroup", local["data_plane"])
        assertEquals("respect_policy", local["dns_mode"])
        assertEquals(false, local["ipv6"])
        assertEquals(true, local["bypass_private_address"])
        assertEquals(listOf(10001, 1000), (local["include_uid"] as List<*>).map { (it as Number).toInt() })
        assertFalse(map.containsKey("auto_route"))
        assertNull(local["exclude_uid"])
    }

    // B6（N5）：空白名单护栏——白名单为空时只设 exclude_uid_range，不生成空 include。
    @Test
    fun emptyWhitelistAppliesExcludeAllRangeGuard() {
        assertEquals(
            RootEbpfUidFilter(excludeUidRange = listOf(EXCLUDE_ALL_UID_RANGE)),
            rootEbpfUidFilter(true, false, emptyList(), 1000, 0),
        )
        assertEquals(
            RootEbpfUidFilter(excludeUidRange = listOf(EXCLUDE_ALL_UID_RANGE)),
            rootEbpfUidFilter(true, false, emptyList(), 1000, 1),
        )
        // 黑名单模式不触发护栏；白名单非空不触发护栏。
        assertEquals(RootEbpfUidFilter(), rootEbpfUidFilter(true, true, emptyList(), 1000, 0))
        assertEquals(
            RootEbpfUidFilter(includeUids = listOf(10001, 1000)),
            rootEbpfUidFilter(true, false, listOf(10001), 1000, 0),
        )
    }

    // B6（N4）：共享路径默认关闭；开启时写 sing-box 原生 shared 字段。
    @Test
    fun sharedPathDefaultsDisabledAndSerializesWhenEnabled() {
        val disabled = buildRootEbpfInbound(null, null, true, false).asMap()
        val sharedOff = disabled["shared"] as Map<*, *>
        assertEquals(false, sharedOff["enabled"])
        assertFalse(sharedOff.containsKey("interface"))

        val enabled = buildRootEbpfInbound(
            includeUids = null,
            excludeUids = null,
            ipv6 = true,
            bypassPrivateAddress = false,
            shared = RootEbpfSharedConfig(
                enabled = true,
                interfaces = listOf("wlan1"),
                dataPlane = "packet_rewrite",
                dnsMode = "hijack",
                defaultInterface = false,
                udpTimeout = "30s",
                includeSourceCidr = listOf("192.168.43.0/24"),
                excludeMacAddress = listOf("02:11:22:33:44:55"),
            ),
        ).asMap()
        val shared = enabled["shared"] as Map<*, *>
        assertEquals(true, shared["enabled"])
        assertEquals(listOf("wlan1"), shared["interface"])
        assertEquals("packet_rewrite", shared["data_plane"])
        assertEquals("hijack", shared["dns_mode"])
        assertEquals(false, shared["default_interface"])
        assertEquals("30s", shared["udp_timeout"])
        assertEquals(listOf("192.168.43.0/24"), shared["include_source_cidr"])
        assertEquals(listOf("02:11:22:33:44:55"), shared["exclude_mac_address"])
    }

    // B6（N5）：护栏范围值与 Go 侧一致。
    @Test
    fun excludeAllRangeMatchesGoSide() {
        assertEquals("0:4294967294", EXCLUDE_ALL_UID_RANGE)
    }
}
