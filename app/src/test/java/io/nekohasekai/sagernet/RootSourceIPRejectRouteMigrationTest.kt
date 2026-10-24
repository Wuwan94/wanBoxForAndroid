// @author 雾晚
package io.nekohasekai.sagernet

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.bg.projectRootRouteSourceIPRejectRules
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.route.RootSourceIPRejectRouteInput
import io.nekohasekai.sagernet.route.parseRootSourceIPRejectCIDRs
import io.nekohasekai.sagernet.route.rootSourceIPRejectRouteInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RootSourceIPRejectRouteMigrationTest {
    @Test fun rawTypedFixtureMatchesGoProjectionAndPreservesOrder() {
        val fixture = fixture()
        val eligible = rule(fixture.getAsJsonObject("eligibleRule"))
        assertEquals(RootSourceIPRejectRouteInput(101L, "192.0.2.0/24, 2001:db8:1::/48\n198.51.100.7/32"), rootSourceIPRejectRouteInput(eligible))
        val config = fixture.getAsJsonObject("inputConfig").deepCopy()
        val before = config.deepCopy()
        assertEquals(fixture.getAsJsonObject("spec"), projectRootRouteSourceIPRejectRules(config))
        assertEquals(before, config)
    }

    @Test fun unsupportedAndMalformedRulesStayOnLegacyPath() {
        val fixture = fixture()
        for (case in fixture.getAsJsonArray("unsupportedCases")) {
            val item = case.asJsonObject
            assertNull(item.get("name").asString, rootSourceIPRejectRouteInput(rule(item.getAsJsonObject("rule"))))
        }
        for (case in fixture.getAsJsonArray("parserCases")) {
            val item = case.asJsonObject
            val parsed = parseRootSourceIPRejectCIDRs(item.get("source").asString)
            if (item.get("valid").asBoolean) assertEquals(item.getAsJsonArray("values").map { it.asString }, parsed)
            else assertTrue(parsed == null)
        }
        val fallback = JsonParser.parseString("""{"route":{"rules":[{"source_ip_cidr":["192.0.2.0/24"],"action":"reject"}]}}""").asJsonObject
        val before = fallback.deepCopy()
        assertNull(projectRootRouteSourceIPRejectRules(fallback))
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
            val candidate = File(dir, "rootmodule/testdata/root_route_source_ip_reject.json")
            if (candidate.isFile) return JsonParser.parseString(candidate.readText(Charsets.UTF_8)).asJsonObject
            dir = dir.parentFile ?: break
        }
        throw AssertionError("shared source-IP reject fixture not found")
    }
}
