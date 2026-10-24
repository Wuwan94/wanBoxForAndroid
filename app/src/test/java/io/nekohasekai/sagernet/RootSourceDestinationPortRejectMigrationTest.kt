// @author 雾晚
package io.nekohasekai.sagernet

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.bg.projectRootRouteSourceDestinationPortRejectRules
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.route.rootSourceDestinationPortRejectRouteInput
import io.nekohasekai.sagernet.route.parseRootPortRejectPorts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RootSourceDestinationPortRejectMigrationTest {
    @Test fun rawFixtureMatchesKotlinProjectionAndPreservesConfig() {
        val fixture = fixture()
        val eligible = rule(fixture.getAsJsonObject("eligibleRule"))
        assertEquals(301L, rootSourceDestinationPortRejectRouteInput(eligible)?.ruleId)
        assertEquals(fixture.getAsJsonObject("spec"), projectRootRouteSourceDestinationPortRejectRules(fixture.getAsJsonObject("inputConfig").deepCopy()))
        val config = fixture.getAsJsonObject("inputConfig").deepCopy(); val before = config.deepCopy()
        assertEquals(fixture.getAsJsonObject("spec"), projectRootRouteSourceDestinationPortRejectRules(config))
        assertEquals(before, config)
    }
    @Test fun unsupportedInputsRemainLegacyAndPortParserMatchesFixture() {
        val fixture = fixture()
        for (case in fixture.getAsJsonArray("unsupportedCases")) {
            val item = case.asJsonObject
            assertNull(item.get("name").asString, rootSourceDestinationPortRejectRouteInput(rule(item.getAsJsonObject("rule"))))
        }
        for (case in fixture.getAsJsonArray("parserCases")) {
            val item = case.asJsonObject
            val source = parseRootPortRejectPorts(item.get("sourcePortText").asString)
            val destination = parseRootPortRejectPorts(item.get("portText").asString)
            if (item.get("valid").asBoolean) {
                assertEquals(item.getAsJsonArray("sourcePorts").map { it.asInt }, source)
                assertEquals(item.getAsJsonArray("ports").map { it.asInt }, destination)
            } else assertTrue(source == null || destination == null)
        }
        val legacy = JsonParser.parseString("""{"route":{"rules":[{"source_port":[53],"port":[80],"action":"reject"}]}}""").asJsonObject
        val before = legacy.deepCopy(); assertNull(projectRootRouteSourceDestinationPortRejectRules(legacy)); assertEquals(before, legacy)
    }
    private fun rule(json: JsonObject): RuleEntity {
        fun text(name: String) = json.get(name)?.takeUnless { it.isJsonNull }?.asString ?: ""
        fun number(name: String, default: Long = 0L) = json.get(name)?.takeUnless { it.isJsonNull }?.asLong ?: default
        val packages = json.get("packages")?.takeUnless { it.isJsonNull }?.asJsonArray?.map { it.asString }?.toSet() ?: emptySet()
        return RuleEntity(id = number("id"), enabled = json.get("enabled")?.asBoolean ?: false, config = text("config"), domains = text("domains"), ip = text("ip"), port = text("port"), sourcePort = text("sourcePort"), network = text("network"), source = text("source"), protocol = text("protocol"), ruleset = text("ruleset"), outbound = number("outbound"), packages = packages)
    }
    private fun fixture(): JsonObject {
        var dir = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (true) {
            val candidate = File(dir, "rootmodule/testdata/root_route_source_destination_port_reject.json")
            if (candidate.isFile) return JsonParser.parseString(candidate.readText(Charsets.UTF_8)).asJsonObject
            dir = dir.parentFile ?: break
        }
        throw AssertionError("shared source/destination port reject fixture not found")
    }
}
