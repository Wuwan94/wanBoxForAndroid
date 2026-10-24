// @author 雾晚
package io.nekohasekai.sagernet

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.route.rootNetworkPortOutboundRouteInput
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RootNetworkPortOutboundRouteMigrationTest {
    @Test fun fixtureCapturesNetworkPortConjunctionAndOriginalText() {
        val f = fixture()
        val input = rootNetworkPortOutboundRouteInput(rule(f.getAsJsonObject("rule")), f.get("outbound").asString)
        assertNotNull(input)
        assertEquals(57001L, input!!.ruleId)
        assertEquals("tcp,udp", input.networkText)
        assertEquals("80, 443\n8080", input.portText)
        assertEquals("direct", input.outbound)
    }

    @Test fun unsupportedPredicatesAndPortRangesStayLegacy() {
        val base = rule(fixture().getAsJsonObject("rule"))
        assertNull(rootNetworkPortOutboundRouteInput(base.copy(port = "80:90"), "direct"))
        assertNull(rootNetworkPortOutboundRouteInput(base.copy(domains = "example.com"), "direct"))
        assertNull(rootNetworkPortOutboundRouteInput(base.copy(ip = "192.0.2.0/24"), "direct"))
        assertNull(rootNetworkPortOutboundRouteInput(base.copy(config = "{\"invert\":true}"), "direct"))
        assertNull(rootNetworkPortOutboundRouteInput(base.copy(network = "icmp"), "direct"))
        assertNull(rootNetworkPortOutboundRouteInput(base, "block"))
    }

    private fun rule(json: JsonObject): RuleEntity {
        fun text(name: String) = json.get(name)?.takeUnless { it.isJsonNull }?.asString ?: ""
        fun number(name: String, default: Long = 0L) = json.get(name)?.takeUnless { it.isJsonNull }?.asLong ?: default
        val packages = json.get("packages")?.takeUnless { it.isJsonNull }?.asJsonArray?.map { it.asString }?.toSet() ?: emptySet()
        return RuleEntity(id=number("id"), enabled=json.get("enabled")?.asBoolean ?: false, config=text("config"),
            domains=text("domains"), ip=text("ip"), port=text("port"), sourcePort=text("sourcePort"), network=text("network"),
            source=text("source"), protocol=text("protocol"), ruleset=text("ruleset"), outbound=number("outbound"), packages=packages)
    }

    private fun fixture(): JsonObject {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (true) {
            val candidate = File(directory, "rootmodule/testdata/root_network_port_outbound_v57.json")
            if (candidate.isFile) return JsonParser.parseString(candidate.readText(Charsets.UTF_8)).asJsonObject
            directory = directory.parentFile ?: break
        }
        throw AssertionError("shared network-port outbound fixture not found")
    }
}
