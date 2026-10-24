// @author 雾晚
package io.nekohasekai.sagernet

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.route.ROOT_SOURCE_IP_OUTBOUND_ROUTE_MARKER
import io.nekohasekai.sagernet.route.rootSourceIPOutboundRouteInput
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RootSourceIPOutboundRouteMigrationTest {
    @Test fun sharedFixtureProducesExactSourceOnlyMarker() {
        val fixture = fixture()
        val rule = rule(fixture.getAsJsonObject("rule"))
        val input = rootSourceIPOutboundRouteInput(rule, fixture.get("outbound").asString)
        assertNotNull(input)
        assertEquals(61001L, input!!.ruleId)
        assertEquals(rule.source, input.sourceText)
        assertEquals("direct", input.outbound)
        val marker = JsonObject().apply {
            add(ROOT_SOURCE_IP_OUTBOUND_ROUTE_MARKER, JsonParser.parseString(
                """{"index":1,"ruleId":${input.ruleId},"sourceText":"${input.sourceText}","outbound":"${input.outbound}"}"""
            ).asJsonObject)
        }
        assertEquals(fixture.getAsJsonObject("projectedRouteRule"), marker)
    }

    @Test fun richerOrUnsupportedRulesRemainOnLegacyBuilderPath() {
        val base = rule(fixture().getAsJsonObject("rule"))
        assertNull(rootSourceIPOutboundRouteInput(base.copy(source = ""), "direct"))
        assertNull(rootSourceIPOutboundRouteInput(base.copy(domains = "full:example.com"), "direct"))
        assertNull(rootSourceIPOutboundRouteInput(base.copy(ip = "192.0.2.0/24"), "direct"))
        assertNull(rootSourceIPOutboundRouteInput(base.copy(port = "443"), "direct"))
        assertNull(rootSourceIPOutboundRouteInput(base.copy(sourcePort = "443"), "direct"))
        assertNull(rootSourceIPOutboundRouteInput(base.copy(network = "tcp"), "direct"))
        assertNull(rootSourceIPOutboundRouteInput(base.copy(protocol = "dns"), "direct"))
        assertNull(rootSourceIPOutboundRouteInput(base.copy(ruleset = "geo"), "direct"))
        assertNull(rootSourceIPOutboundRouteInput(base.copy(config = "{\"invert\":true}"), "direct"))
        assertNull(rootSourceIPOutboundRouteInput(base.copy(source = "geoip:private"), "direct"))
        assertNull(rootSourceIPOutboundRouteInput(base, "block"))
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
            val candidate = File(directory, "rootmodule/testdata/root_source_ip_outbound_v61.json")
            if (candidate.isFile) return JsonParser.parseString(candidate.readText(Charsets.UTF_8)).asJsonObject
            directory = directory.parentFile ?: break
        }
        throw AssertionError("shared source-IP outbound fixture not found")
    }
}
