// @author 雾晚
package io.nekohasekai.sagernet

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.bg.projectRootRouteProtocolRejectRules
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.route.RootProtocolRejectRouteInput
import io.nekohasekai.sagernet.route.parseRootProtocolRejectProtocols
import io.nekohasekai.sagernet.route.rootProtocolRejectRouteInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RootProtocolRejectRouteMigrationTest {
    @Test fun rawTypedFixtureMatchesGoProjectionAndPreservesOrder() {
        val fixture = fixture()
        val eligible = rule(fixture.getAsJsonObject("eligibleRule"))
        assertEquals(RootProtocolRejectRouteInput(91L, "tls, dns\nhttp"), rootProtocolRejectRouteInput(eligible))
        val config = fixture.getAsJsonObject("inputConfig").deepCopy()
        val before = config.deepCopy()
        assertEquals(fixture.getAsJsonObject("spec"), projectRootRouteProtocolRejectRules(config))
        assertEquals(before, config)
        val raw = fixture.getAsJsonObject("spec").getAsJsonArray("rules")[0].asJsonObject
        assertEquals(listOf("tls", "dns", "http"), parseRootProtocolRejectProtocols(raw.get("protocolText").asString))
    }

    @Test fun unsupportedAndMalformedRulesStayOnLegacyPath() {
        val fixture = fixture()
        for (case in fixture.getAsJsonArray("unsupportedCases")) {
            val item = case.asJsonObject
            assertNull(item.get("name").asString, rootProtocolRejectRouteInput(rule(item.getAsJsonObject("rule"))))
        }
        for (case in fixture.getAsJsonArray("parserCases")) {
            val item = case.asJsonObject
            val protocols = parseRootProtocolRejectProtocols(item.get("protocols").asString)
            if (item.get("valid").asBoolean) assertEquals(item.getAsJsonArray("values").map { it.asString }, protocols)
            else assertTrue(protocols == null)
        }
        val fallback = JsonParser.parseString("""{"route":{"rules":[{"protocol":["tls"],"action":"reject"}]}}""").asJsonObject
        val before = fallback.deepCopy()
        assertNull(projectRootRouteProtocolRejectRules(fallback))
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
            val candidate = File(dir, "rootmodule/testdata/root_route_protocol_reject.json")
            if (candidate.isFile) return JsonParser.parseString(candidate.readText(Charsets.UTF_8)).asJsonObject
            dir = dir.parentFile ?: break
        }
        throw AssertionError("shared protocol reject fixture not found")
    }
}
