// @author 雾晚
package io.nekohasekai.sagernet

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.route.ROOT_PORT_OUTBOUND_ROUTE_MARKER
import io.nekohasekai.sagernet.route.rootPortOutboundRouteInput
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RootPortOutboundRouteMigrationTest {
    @Test fun sharedFixtureProducesExactNumericPortRouteMarker() {
        val fixture = fixture()
        val rule = rule(fixture.getAsJsonObject("rule"))
        val input = rootPortOutboundRouteInput(rule, fixture.get("outbound").asString)
        assertNotNull(input)
        assertEquals(82001L, input!!.ruleId)
        assertEquals("80, 443\n8080", input.portText)
        assertEquals("direct", input.outbound)
        val marker = JsonObject().apply {
            add(ROOT_PORT_OUTBOUND_ROUTE_MARKER, JsonObject().apply {
                addProperty("index", 1)
                addProperty("ruleId", input.ruleId)
                addProperty("portText", input.portText)
                addProperty("outbound", input.outbound)
            })
        }
        assertEquals(fixture.getAsJsonObject("projectedRouteRule"), marker)
    }

    @Test fun richerAndUnsupportedRulesStayOnLegacyBuilderPath() {
        val base = rule(fixture().getAsJsonObject("rule"))
        assertNull(rootPortOutboundRouteInput(base.copy(config = "{\"network\":\"tcp\"}"), "direct"))
        assertNull(rootPortOutboundRouteInput(base.copy(domains = "full:example.com"), "direct"))
        assertNull(rootPortOutboundRouteInput(base.copy(ip = "192.0.2.0/24"), "direct"))
        assertNull(rootPortOutboundRouteInput(base.copy(sourcePort = "53"), "direct"))
        assertNull(rootPortOutboundRouteInput(base.copy(network = "tcp"), "direct"))
        assertNull(rootPortOutboundRouteInput(base.copy(source = "192.0.2.0/24"), "direct"))
        assertNull(rootPortOutboundRouteInput(base.copy(protocol = "dns"), "direct"))
        assertNull(rootPortOutboundRouteInput(base.copy(ruleset = "https://example.com/rules.srs"), "direct"))
        assertNull(rootPortOutboundRouteInput(base.copy(packages = setOf("example.app")), "direct"))
        assertNull(rootPortOutboundRouteInput(base.copy(port = "80-81"), "direct"))
        assertNull(rootPortOutboundRouteInput(base.copy(port = "65536"), "direct"))
        assertNull(rootPortOutboundRouteInput(base, "block"))
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
            val candidate = File(directory, "rootmodule/testdata/root_port_outbound_v82.json")
            if (candidate.isFile) return JsonParser.parseString(candidate.readText(Charsets.UTF_8)).asJsonObject
            directory = directory.parentFile ?: break
        }
        throw AssertionError("shared port-only outbound fixture not found")
    }
}
