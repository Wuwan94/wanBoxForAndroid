package io.nekohasekai.sagernet

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.route.ROOT_SOURCE_DESTINATION_IP_OUTBOUND_ROUTE_MARKER
import io.nekohasekai.sagernet.route.rootSourceDestinationIPOutboundRouteInput
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RootSourceDestinationIPOutboundMigrationTest {
    @Test fun sharedFixtureProducesExactTypedSourceDestinationConjunction() {
        val fixture = fixture()
        val rule = rule(fixture.getAsJsonObject("rule"))
        val input = rootSourceDestinationIPOutboundRouteInput(rule, fixture.get("outbound").asString)
        assertNotNull(input)
        assertEquals(60001L, input!!.ruleId)
        assertEquals(rule.source, input.sourceText)
        assertEquals(rule.ip, input.ipText)
        assertEquals(fixture.get("outbound").asString, input.outbound)
        val projected = JsonObject().apply {
            add(ROOT_SOURCE_DESTINATION_IP_OUTBOUND_ROUTE_MARKER, JsonParser.parseString("""{"index":1,"ruleId":${input.ruleId},"sourceText":"${input.sourceText}","ipText":"${input.ipText}","outbound":"${input.outbound}"}""").asJsonObject)
        }
        assertEquals(fixture.getAsJsonObject("projectedRouteRule"), projected)
    }

    @Test fun unsupportedOrExtendedRulesRemainOnLegacyBuilderPath() {
        val rule = rule(fixture().getAsJsonObject("rule"))
        assertNull(rootSourceDestinationIPOutboundRouteInput(rule.copy(source = ""), "direct"))
        assertNull(rootSourceDestinationIPOutboundRouteInput(rule.copy(domains = "full:example.com"), "direct"))
        assertNull(rootSourceDestinationIPOutboundRouteInput(rule.copy(port = "443"), "direct"))
        assertNull(rootSourceDestinationIPOutboundRouteInput(rule.copy(config = "{\"invert\":true}"), "direct"))
        assertNull(rootSourceDestinationIPOutboundRouteInput(rule.copy(ip = "geoip:private"), "direct"))
        assertNull(rootSourceDestinationIPOutboundRouteInput(rule, "block"))
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
            val candidate = File(directory, "rootmodule/testdata/root_source_destination_ip_outbound_v60.json")
            if (candidate.isFile) return JsonParser.parseString(candidate.readText(Charsets.UTF_8)).asJsonObject
            directory = directory.parentFile ?: break
        }
        throw AssertionError("shared source/destination IP outbound fixture not found")
    }
}
