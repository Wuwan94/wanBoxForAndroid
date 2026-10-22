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
}
