// @author 雾晚
package io.nekohasekai.sagernet

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.route.ROOT_SOURCE_PORT_OUTBOUND_ROUTE_MARKER
import io.nekohasekai.sagernet.route.rootSourcePortOutboundRouteInput
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RootSourcePortOutboundRouteMigrationTest {
    @Test fun sharedFixtureProducesTypedSourcePortOnlyInput() {
        val fixture = fixture()
        val rule = rule(fixture.getAsJsonObject("rule"))
        val input = rootSourcePortOutboundRouteInput(rule, fixture.get("outbound").asString)
        assertNotNull(input)
        assertEquals(63001L, input!!.ruleId)
        assertEquals("53, 853\n5353", input.sourcePortText)
        assertEquals("direct", input.outbound)
        val marker = JsonObject().apply {
            add(ROOT_SOURCE_PORT_OUTBOUND_ROUTE_MARKER, JsonParser.parseString(
                """{"index":1,"ruleId":${input.ruleId},"sourcePortText":"${input.sourcePortText.replace("\n", "\\n")}","outbound":"${input.outbound}"}"""
            ).asJsonObject)
        }
        assertEquals(fixture.getAsJsonObject("projectedRouteRule"), marker)
    }

    @Test fun richerAndUnsupportedInputsStayOnLegacyPath() {
        val base = rule(fixture().getAsJsonObject("rule"))
        assertNotNull(rootSourcePortOutboundRouteInput(base.copy(), "direct"))
        assertNull(rootSourcePortOutboundRouteInput(base.copy(domains = "full:example.com"), "direct"))
        assertNull(rootSourcePortOutboundRouteInput(base.copy(ip = "192.0.2.0/24"), "direct"))
        assertNull(rootSourcePortOutboundRouteInput(base.copy(port = "443"), "direct"))
        assertNull(rootSourcePortOutboundRouteInput(base.copy(network = "tcp"), "direct"))
        assertNull(rootSourcePortOutboundRouteInput(base.copy(source = "192.0.2.0/24"), "direct"))
        assertNull(rootSourcePortOutboundRouteInput(base.copy(protocol = "dns"), "direct"))
        assertNull(rootSourcePortOutboundRouteInput(base.copy(ruleset = "https://example.com/rules.srs"), "direct"))
        assertNull(rootSourcePortOutboundRouteInput(base.copy(packages = setOf("example.app")), "direct"))
        assertNull(rootSourcePortOutboundRouteInput(base.copy(config = "{\"invert\":true}"), "direct"))
        assertNull(rootSourcePortOutboundRouteInput(base.copy(sourcePort = "53-54"), "direct"))
        assertNull(rootSourcePortOutboundRouteInput(base, "block"))
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
            val candidate = File(directory, "rootmodule/testdata/root_source_port_outbound_v63.json")
            if (candidate.isFile) return JsonParser.parseString(candidate.readText(Charsets.UTF_8)).asJsonObject
            directory = directory.parentFile ?: break
        }
        throw AssertionError("shared source-port outbound fixture not found")
    }
}
