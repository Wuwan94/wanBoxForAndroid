// @author 雾晚
package io.nekohasekai.sagernet

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.bg.projectRootRouteSourceDestinationIPRejectRules
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.route.RootSourceDestinationIPRejectRouteInput
import io.nekohasekai.sagernet.route.parseRootIPRejectCIDRs
import io.nekohasekai.sagernet.route.parseRootSourceIPRejectCIDRs
import io.nekohasekai.sagernet.route.rootSourceDestinationIPRejectRouteInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RootSourceDestinationIPRejectRouteMigrationTest {
    @Test fun rawFixtureMatchesKotlinProjectionAndKeepsConfigUnchanged() {
        val fixture = fixture()
        val eligible = rule(fixture.getAsJsonObject("eligibleRule"))
        val source = eligible.source
        val destination = eligible.ip
        assertEquals(RootSourceDestinationIPRejectRouteInput(201L, source, destination), rootSourceDestinationIPRejectRouteInput(eligible))
        val config = fixture.getAsJsonObject("inputConfig").deepCopy()
        val before = config.deepCopy()
        assertEquals(fixture.getAsJsonObject("spec"), projectRootRouteSourceDestinationIPRejectRules(config))
        assertEquals(before, config)
    }

    @Test fun unsupportedRulesStayOnLegacyPathAndBothParsersAgreeWithFixture() {
        val fixture = fixture()
        for (case in fixture.getAsJsonArray("unsupportedCases")) {
            val item = case.asJsonObject
            assertNull(item.get("name").asString, rootSourceDestinationIPRejectRouteInput(rule(item.getAsJsonObject("rule"))))
        }
        for (case in fixture.getAsJsonArray("sourceParserCases")) {
            val item = case.asJsonObject
            val parsed = parseRootSourceIPRejectCIDRs(item.get("ip").asString)
            if (item.get("valid").asBoolean) assertEquals(item.getAsJsonArray("values").map { it.asString }, parsed)
            else assertTrue(parsed == null)
        }
        for (case in fixture.getAsJsonArray("destinationParserCases")) {
            val item = case.asJsonObject
            val parsed = parseRootIPRejectCIDRs(item.get("ip").asString)
            if (item.get("valid").asBoolean) assertEquals(item.getAsJsonArray("values").map { it.asString }, parsed)
            else assertTrue(parsed == null)
        }
        val legacy = JsonParser.parseString("""{"route":{"rules":[{"source_ip_cidr":["192.0.2.0/24"],"ip_cidr":["203.0.113.0/24"],"action":"reject"}]}}""").asJsonObject
        val before = legacy.deepCopy()
        assertNull(projectRootRouteSourceDestinationIPRejectRules(legacy))
        assertEquals(before, legacy)
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
            val candidate = File(dir, "rootmodule/testdata/root_route_source_destination_ip_reject.json")
            if (candidate.isFile) return JsonParser.parseString(candidate.readText(Charsets.UTF_8)).asJsonObject
            dir = dir.parentFile ?: break
        }
        throw AssertionError("shared source/destination IP reject fixture not found")
    }
}
