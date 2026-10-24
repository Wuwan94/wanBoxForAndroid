// @author 雾晚
package io.nekohasekai.sagernet

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.bg.projectRootRouteSourcePortRejectRules
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.route.RootSourcePortRejectRouteInput
import io.nekohasekai.sagernet.route.parseRootPortRejectPorts
import io.nekohasekai.sagernet.route.rootSourcePortRejectRouteInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RootSourcePortRejectRouteMigrationTest {
    @Test fun rawTypedFixtureMatchesGoProjectionAndPreservesOrder() {
        val fixture = fixture()
        val eligible = rule(fixture.getAsJsonObject("eligibleRule"))
        assertEquals(RootSourcePortRejectRouteInput(81L, "53, 123\n443"), rootSourcePortRejectRouteInput(eligible))
        val config = fixture.getAsJsonObject("inputConfig").deepCopy()
        val before = config.deepCopy()
        assertEquals(fixture.getAsJsonObject("spec"), projectRootRouteSourcePortRejectRules(config))
        assertEquals(before, config)
        val raw = fixture.getAsJsonObject("spec").getAsJsonArray("rules")[0].asJsonObject
        assertEquals(listOf(53, 123, 443), parseRootPortRejectPorts(raw.get("sourcePortText").asString))
    }

    @Test fun unsupportedAndMalformedRulesStayOnLegacyPath() {
        val fixture = fixture()
        for (case in fixture.getAsJsonArray("unsupportedCases")) {
            val item = case.asJsonObject
            assertNull(item.get("name").asString, rootSourcePortRejectRouteInput(rule(item.getAsJsonObject("rule"))))
        }
        for (case in fixture.getAsJsonArray("parserCases")) {
            val item = case.asJsonObject
            val ports = parseRootPortRejectPorts(item.get("sourcePorts").asString)
            if (item.get("valid").asBoolean) {
                assertEquals(item.getAsJsonArray("ports").map { it.asInt }, ports)
            } else {
                assertTrue(ports == null)
            }
        }
        val fallback = JsonParser.parseString("""{"route":{"rules":[{"source_port":[53],"action":"reject"}]}}""").asJsonObject
        val before = fallback.deepCopy()
        assertNull(projectRootRouteSourcePortRejectRules(fallback))
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
        var dir = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (true) {
            val candidate = File(dir, "rootmodule/testdata/root_route_source_port_reject.json")
            if (candidate.isFile) return JsonParser.parseString(candidate.readText(Charsets.UTF_8)).asJsonObject
            dir = dir.parentFile ?: break
        }
        throw AssertionError("shared source-port reject fixture not found")
    }
}
