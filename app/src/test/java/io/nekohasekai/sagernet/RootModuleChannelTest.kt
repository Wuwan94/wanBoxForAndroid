// @author 雾晚
package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.bg.RootModuleChannelGate
import io.nekohasekai.sagernet.bg.RootModuleReadQuery
import io.nekohasekai.sagernet.bg.channelUrl
import io.nekohasekai.sagernet.bg.parseChannelInfo
import org.junit.Assert.*
import org.junit.Test

/**
 * The loopback read channel is discovered from a `wanboxctl channel`
 * response whose exact nesting is owned by the module side; the parser is
 * deliberately tolerant about location and strict about shape. The gate
 * pins the failure contract: any failure drops the coordinates and cools
 * rediscovery down, reads fall back to the su bridge meanwhile.
 */
class RootModuleChannelTest {
    private val token = "0123456789abcdef0123456789abcdef"

    @Test fun parsesChannelJsonShape() {
        val info = parseChannelInfo("""{"port": 18080, "token": "$token", "pid": 4242}""")
        assertEquals(18080, info?.port)
        assertEquals(token, info?.token)
    }

    @Test fun parsesCoordinatesNestedInSchemaEnvelope() {
        val info = parseChannelInfo(
            """{"schemaVersion":1,"ok":true,"state":{"phase":"connected",""" +
                """"channel":{"port":9091,"token":"$token"}}}""")
        assertEquals(9091, info?.port)
        assertEquals(token, info?.token)
    }

    @Test fun parsesCoordinatesSplitAcrossNestedObjectsAndArrays() {
        val info = parseChannelInfo(
            """{"state":{"listeners":[{"port":7777}],"auth":{"token":"$token"}}}""")
        assertEquals(7777, info?.port)
        assertEquals(token, info?.token)
    }

    @Test fun rejectsErrorEnvelopeFromModuleWithoutChannel() {
        assertNull(parseChannelInfo("""{"schemaVersion":1,"ok":false,"error":"unknown_command"}"""))
    }

    @Test fun rejectsNonJsonAndPartialPayloads() {
        assertNull(parseChannelInfo(""))
        assertNull(parseChannelInfo("not json"))
        assertNull(parseChannelInfo("null"))
        assertNull(parseChannelInfo("""{"port": 8080}"""))
        assertNull(parseChannelInfo("""{"token": "$token"}"""))
    }

    @Test fun rejectsMalformedCoordinates() {
        // Token must be exactly 32 hex characters.
        assertNull(parseChannelInfo("""{"port":8080,"token":"0123456789abcdef"}"""))
        assertNull(parseChannelInfo("""{"port":8080,"token":"0123456789abcdef0123456789abcdeg"}"""))
        assertNull(parseChannelInfo("""{"port":8080,"token":12345678901234567890123456789012}"""))
        // Port must be a JSON number in 1..65535, not a string or a float.
        assertNull(parseChannelInfo("""{"port":0,"token":"$token"}"""))
        assertNull(parseChannelInfo("""{"port":65536,"token":"$token"}"""))
        assertNull(parseChannelInfo("""{"port":-1,"token":"$token"}"""))
        assertNull(parseChannelInfo("""{"port":"8080","token":"$token"}"""))
        assertNull(parseChannelInfo("""{"port":8080.5,"token":"$token"}"""))
        // Boundary ports are valid.
        assertEquals(1, parseChannelInfo("""{"port":1,"token":"$token"}""")?.port)
        assertEquals(65535, parseChannelInfo("""{"port":65535,"token":"$token"}""")?.port)
    }

    @Test fun gateServesStoredInfoUntilFailure() {
        var now = 1_000L
        val gate = RootModuleChannelGate(30_000L) { now }
        assertNull(gate.info())
        assertTrue(gate.attemptAllowed())

        val info = parseChannelInfo("""{"port":8080,"token":"$token"}""")!!
        gate.store(info)
        assertEquals(info, gate.info())
        assertTrue(gate.attemptAllowed())
    }

    @Test fun failureDropsInfoAndCooldownThrottlesRediscovery() {
        var now = 0L
        val gate = RootModuleChannelGate(30_000L) { now }
        gate.store(parseChannelInfo("""{"port":8080,"token":"$token"}""")!!)

        gate.fail()
        assertNull(gate.info())
        assertFalse(gate.attemptAllowed())

        now += 29_999L
        assertFalse(gate.attemptAllowed())
        now += 1L
        assertTrue(gate.attemptAllowed())

        // A fresh failure restarts the full cooldown window.
        gate.fail()
        now += 29_999L
        assertFalse(gate.attemptAllowed())
        now += 1L
        assertTrue(gate.attemptAllowed())
    }

    @Test fun dropInfoForgetsCoordinatesWithoutCooldown() {
        var now = 5_000L
        val gate = RootModuleChannelGate(30_000L) { now }
        gate.store(parseChannelInfo("""{"port":8080,"token":"$token"}""")!!)

        gate.dropInfo()
        assertNull(gate.info())
        // Rediscovery is allowed immediately: invalidation is not a failure.
        assertTrue(gate.attemptAllowed())
    }

    @Test fun channelUrlsMatchTheContract() {
        val info = parseChannelInfo("""{"port":18080,"token":"$token"}""")!!
        assertEquals("http://127.0.0.1:18080/v1/status",
            channelUrl(info, RootModuleReadQuery.STATUS, 0, null))
        assertEquals("http://127.0.0.1:18080/v1/dashboard",
            channelUrl(info, RootModuleReadQuery.DASHBOARD, 0, null))
        assertEquals("http://127.0.0.1:18080/v1/status?wait=25&since=rev+1%262",
            channelUrl(info, RootModuleReadQuery.STATUS, 25, "rev 1&2"))
    }
}
