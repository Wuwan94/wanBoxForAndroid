// @author 雾晚
package io.nekohasekai.sagernet

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.bg.projectRootRouteNetworkPortRejectRules
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.route.RootNetworkPortRejectRouteInput
import io.nekohasekai.sagernet.route.parseRootNetworkRejectNetworks
import io.nekohasekai.sagernet.route.parseRootPortRejectPorts
import io.nekohasekai.sagernet.route.rootNetworkPortRejectRouteInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RootNetworkPortRejectRouteMigrationTest {
    @Test fun rawTypedFixtureMatchesGoProjectionAndPreservesOrder() {
        val fixture = fixture()
        val eligible = rule(fixture.getAsJsonObject("eligibleRule"))
        assertEquals(RootNetworkPortRejectRouteInput(71L, "tcp, udp\nudp", "80,443\n443"), rootNetworkPortRejectRouteInput(eligible))
        val config = fixture.getAsJsonObject("inputConfig").deepCopy()
        val before = config.deepCopy()
        assertEquals(fixture.getAsJsonObject("spec"), projectRootRouteNetworkPortRejectRules(config))
        assertEquals(before, config)
        val raw = fixture.getAsJsonObject("spec").getAsJsonArray("rules")[0].asJsonObject
        assertEquals(listOf("tcp", "udp", "udp"), parseRootNetworkRejectNetworks(raw.get("networkText").asString))
        assertEquals(listOf(80, 443, 443), parseRootPortRejectPorts(raw.get("portText").asString))
    }

    @Test fun unsupportedAndMalformedRulesStayOnLegacyPath() {
        val fixture = fixture()
        for (case in fixture.getAsJsonArray("unsupportedCases")) {
            val item = case.asJsonObject
            assertNull(item.get("name").asString, rootNetworkPortRejectRouteInput(rule(item.getAsJsonObject("rule"))))
        }
        for (case in fixture.getAsJsonArray("parserCases")) {
            val item = case.asJsonObject
            val networks = parseRootNetworkRejectNetworks(item.get("network").asString)
            val ports = parseRootPortRejectPorts(item.get("ports").asString)
            if (item.get("valid").asBoolean) {
                assertEquals(item.getAsJsonArray("networks").map { it.asString }, networks)
                assertEquals(item.getAsJsonArray("parsedPorts").map { it.asInt }, ports)
            } else {
                assertTrue(networks == null || ports == null)
            }
        }
        val fallback = JsonParser.parseString("""{"route":{"rules":[{"network":["tcp"],"port":[80],"action":"reject"}]}}""").asJsonObject
        val before = fallback.deepCopy()
        assertNull(projectRootRouteNetworkPortRejectRules(fallback))
        assertEquals(before, fallback)
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
        var dir: File? = File(System.getProperty("user.dir")).canonicalFile
        while (dir != null) {
            val candidate = File(dir, "rootmodule/testdata/root_route_network_port_reject.json")
            if (candidate.isFile) return JsonParser.parseString(candidate.readText(Charsets.UTF_8)).asJsonObject
            dir = dir.parentFile
        }
        throw AssertionError("shared network+port reject fixture not found")
    }
}
