// @author 雾晚
package io.nekohasekai.sagernet

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.route.ROOT_PORT_RANGE_OUTBOUND_ROUTE_MARKER
import io.nekohasekai.sagernet.route.ROOT_SOURCE_PORT_RANGE_OUTBOUND_ROUTE_MARKER
import io.nekohasekai.sagernet.route.parseRootClosedPortRanges
import io.nekohasekai.sagernet.route.rootPortRangeOutboundRouteInput
import io.nekohasekai.sagernet.route.rootSourcePortRangeOutboundRouteInput
import io.nekohasekai.sagernet.bg.rebaseRootPortRangeOutboundMarkers
import io.nekohasekai.sagernet.bg.rebaseRootSourcePortRangeOutboundMarkers
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RootPortRangeOutboundRouteMigrationTest {
    @Test fun sharedFixturesProduceExactDestinationAndSourceRangeMarkers() {
        val destination = fixture("root_port_range_outbound_v85.json")
        val destinationInput = rootPortRangeOutboundRouteInput(rule(destination.getAsJsonObject("rule")), destination.get("outbound").asString)
        assertNotNull(destinationInput)
        assertEquals(85001L, destinationInput!!.ruleId)
        assertEquals(listOf("80:90", "1000:2000", "443:443"), destinationInput.ranges)
        assertEquals("direct", destinationInput.outbound)
        val destinationMarker = JsonObject().apply {
            add(ROOT_PORT_RANGE_OUTBOUND_ROUTE_MARKER, JsonObject().apply {
                addProperty("index", 1)
                addProperty("ruleId", destinationInput.ruleId)
                add("ranges", JsonParser.parseString("[\"80:90\",\"1000:2000\",\"443:443\"]").asJsonArray)
                addProperty("outbound", destinationInput.outbound)
            })
        }
        assertEquals(destination.getAsJsonObject("projectedRouteRule"), destinationMarker)

        val source = fixture("root_source_port_range_outbound_v85.json")
        val sourceInput = rootSourcePortRangeOutboundRouteInput(rule(source.getAsJsonObject("rule")), source.get("outbound").asString)
        assertNotNull(sourceInput)
        assertEquals(85002L, sourceInput!!.ruleId)
        assertEquals(listOf("2000:3000", "443:443"), sourceInput.ranges)
        val sourceMarker = JsonObject().apply {
            add(ROOT_SOURCE_PORT_RANGE_OUTBOUND_ROUTE_MARKER, JsonObject().apply {
                addProperty("index", 1)
                addProperty("ruleId", sourceInput.ruleId)
                add("ranges", JsonParser.parseString("[\"2000:3000\",\"443:443\"]").asJsonArray)
                addProperty("outbound", sourceInput.outbound)
            })
        }
        assertEquals(source.getAsJsonObject("projectedRouteRule"), sourceMarker)
    }

    @Test fun openMalformedMixedAndRicherRulesStayOnLegacyBuilderPath() {
        val destination = rule(fixture("root_port_range_outbound_v85.json").getAsJsonObject("rule"))
        val source = rule(fixture("root_source_port_range_outbound_v85.json").getAsJsonObject("rule"))
        assertNull(rootPortRangeOutboundRouteInput(destination.copy(port = ":90"), "direct"))
        assertNull(rootPortRangeOutboundRouteInput(destination.copy(port = "80:"), "direct"))
        assertNull(rootPortRangeOutboundRouteInput(destination.copy(port = "80:90, 100"), "direct"))
        assertNull(rootPortRangeOutboundRouteInput(destination.copy(port = "0:90"), "direct"))
        assertNull(rootPortRangeOutboundRouteInput(destination.copy(port = "90:80"), "direct"))
        assertNull(rootPortRangeOutboundRouteInput(destination.copy(sourcePort = "53:54"), "direct"))
        assertNull(rootPortRangeOutboundRouteInput(destination.copy(domains = "full:example.com"), "direct"))
        assertNull(rootPortRangeOutboundRouteInput(destination.copy(config = "{\"network\":\"tcp\"}"), "direct"))
        assertNull(rootPortRangeOutboundRouteInput(destination, "block"))
        assertNull(rootSourcePortRangeOutboundRouteInput(source.copy(sourcePort = "53:54-55"), "direct"))
        assertNull(rootSourcePortRangeOutboundRouteInput(source.copy(port = "80:90"), "direct"))
        assertNull(rootSourcePortRangeOutboundRouteInput(source.copy(network = "tcp"), "direct"))
        assertNull(rootSourcePortRangeOutboundRouteInput(source, ""))
        // ConfigBuilder.listByLineOrComma intentionally drops empty delimiter entries.
        assertEquals(listOf("1:2", "3:4"), parseRootClosedPortRanges("1:2,,3:4"))
    }

    @Test fun markerIndicesAreRebasedAfterEarlierProjectionChanges() {
        val fixture = fixture("root_port_range_outbound_v85.json")
        val config = JsonObject().apply {
            add("route", JsonObject().apply {
                add("rules", JsonParser.parseString("[{},${fixture.getAsJsonObject("projectedRouteRule")},{}]").asJsonArray)
            })
        }
        assertTrue(rebaseRootPortRangeOutboundMarkers(config))
        assertEquals(1, config.getAsJsonObject("route").getAsJsonArray("rules")[1].asJsonObject
            .getAsJsonObject(ROOT_PORT_RANGE_OUTBOUND_ROUTE_MARKER).get("index").asInt)

        val sourceFixture = fixture("root_source_port_range_outbound_v85.json")
        val sourceConfig = JsonObject().apply {
            add("route", JsonObject().apply {
                add("rules", JsonParser.parseString("[{},${sourceFixture.getAsJsonObject("projectedRouteRule")},{}]").asJsonArray)
            })
        }
        assertTrue(rebaseRootSourcePortRangeOutboundMarkers(sourceConfig))
        assertEquals(1, sourceConfig.getAsJsonObject("route").getAsJsonArray("rules")[1].asJsonObject
            .getAsJsonObject(ROOT_SOURCE_PORT_RANGE_OUTBOUND_ROUTE_MARKER).get("index").asInt)
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

    private fun fixture(name: String): JsonObject {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (true) {
            val candidate = File(directory, "rootmodule/testdata/$name")
            if (candidate.isFile) return JsonParser.parseString(candidate.readText(Charsets.UTF_8)).asJsonObject
            directory = directory.parentFile ?: break
        }
        throw AssertionError("shared port-range outbound fixture not found: $name")
    }
}
