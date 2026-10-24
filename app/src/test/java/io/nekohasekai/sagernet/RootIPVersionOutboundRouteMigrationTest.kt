// @author 雾晚
package io.nekohasekai.sagernet

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.route.ROOT_IP_VERSION_OUTBOUND_ROUTE_MARKER
import io.nekohasekai.sagernet.route.rootIPVersionOutboundRouteInput
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RootIPVersionOutboundRouteMigrationTest {
    @Test fun sharedFixtureProducesExactIPv6RouteMarker() {
        val fixture = fixture()
        val rule = rule(fixture.getAsJsonObject("rule"))
        val input = rootIPVersionOutboundRouteInput(rule, fixture.get("outbound").asString)
        assertNotNull(input)
        assertEquals(62001L, input!!.ruleId)
        assertEquals(6, input.ipVersion)
        assertEquals("direct", input.outbound)
        val marker = JsonObject().apply {
            add(ROOT_IP_VERSION_OUTBOUND_ROUTE_MARKER, JsonParser.parseString(
                """{"index":1,"ruleId":${input.ruleId},"ipVersion":${input.ipVersion},"outbound":"${input.outbound}"}"""
            ).asJsonObject)
        }
        assertEquals(fixture.getAsJsonObject("projectedRouteRule"), marker)
    }

    @Test fun richerAndUnsupportedRulesStayOnLegacyBuilderPath() {
        val base = rule(fixture().getAsJsonObject("rule"))
        assertNull(rootIPVersionOutboundRouteInput(base.copy(config = ""), "direct"))
        assertNull(rootIPVersionOutboundRouteInput(base.copy(config = "{\"ip_version\":5}"), "direct"))
        assertNull(rootIPVersionOutboundRouteInput(base.copy(config = "{\"ip_version\":6,\"invert\":true}"), "direct"))
        assertNull(rootIPVersionOutboundRouteInput(base.copy(domains = "full:example.com"), "direct"))
        assertNull(rootIPVersionOutboundRouteInput(base.copy(ip = "192.0.2.0/24"), "direct"))
        assertNull(rootIPVersionOutboundRouteInput(base.copy(port = "443"), "direct"))
        assertNull(rootIPVersionOutboundRouteInput(base.copy(network = "tcp"), "direct"))
        assertNull(rootIPVersionOutboundRouteInput(base.copy(packages = setOf("example.app")), "direct"))
        assertNull(rootIPVersionOutboundRouteInput(base, "block"))
    }

    private fun rule(json: JsonObject): RuleEntity {
        fun text(name: String) = json.get(name)?.takeUnless { it.isJsonNull }?.asString ?: ""
        fun number(name: String, default: Long = 0L) = json.get(name)?.takeUnless { it.isJsonNull }?.asLong ?: default
        val packages = json.get("packages")?.takeUnless { it.isJsonNull }?.asJsonArray?.map { it.asString }?.toSet() ?: emptySet()
        return RuleEntity(id = number("id"), enabled = json.get("enabled")?.asBoolean ?: false,
            config = text("config"), domains = text("domains"), ip = text("ip"), port = text("port"),
            sourcePort = text("sourcePort"), network = text("network"), source = text("source"),
            protocol = text("protocol"), ruleset = text("ruleset"), outbound = number("outbound"), packages = packages)
    }

    private fun fixture(): JsonObject {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (true) {
            val candidate = File(directory, "rootmodule/testdata/root_ip_version_outbound_v62.json")
            if (candidate.isFile) return JsonParser.parseString(candidate.readText(Charsets.UTF_8)).asJsonObject
            directory = directory.parentFile ?: break
        }
        throw AssertionError("shared IP-version outbound fixture not found")
    }
}
