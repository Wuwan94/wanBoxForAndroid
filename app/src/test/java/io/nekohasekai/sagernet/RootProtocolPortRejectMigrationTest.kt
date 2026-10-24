// @author 雾晚
package io.nekohasekai.sagernet

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.bg.projectRootRouteProtocolPortRejectRules
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.route.parseRootPortRejectPorts
import io.nekohasekai.sagernet.route.parseRootProtocolRejectProtocols
import io.nekohasekai.sagernet.route.rootProtocolPortRejectRouteInput
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RootProtocolPortRejectMigrationTest {
    @Test
    fun sharedFixtureMatchesEligibilityProjectionAndOrderedRawInput() {
        val fixture = fixture()
        val eligible = rule(fixture.getAsJsonObject("eligibleRule"))
        assertEquals(391L, rootProtocolPortRejectRouteInput(eligible)?.ruleId)
        assertEquals(
            fixture.getAsJsonObject("spec"),
            projectRootRouteProtocolPortRejectRules(fixture.getAsJsonObject("inputConfig").deepCopy())
        )
        val config = fixture.getAsJsonObject("inputConfig").deepCopy()
        val before = config.deepCopy()
        projectRootRouteProtocolPortRejectRules(config)
        assertEquals(before, config)
    }

    @Test
    fun unsupportedAndParserCasesRemainOnLegacyPath() {
        val fixture = fixture()
        for (case in fixture.getAsJsonArray("unsupportedCases")) {
            val item = case.asJsonObject
            assertNull(
                item.get("name").asString,
                rootProtocolPortRejectRouteInput(rule(item.getAsJsonObject("rule")))
            )
        }
        for (case in fixture.getAsJsonArray("parserCases")) {
            val item = case.asJsonObject
            val protocols = parseRootProtocolRejectProtocols(item.get("protocolText").asString)
            val ports = parseRootPortRejectPorts(item.get("portText").asString)
            if (item.get("valid").asBoolean) {
                assertEquals(item.getAsJsonArray("protocols").map { it.asString }, protocols)
                assertEquals(item.getAsJsonArray("ports").map { it.asInt }, ports)
            } else {
                assertTrue(protocols == null || ports == null)
            }
        }
    }

    private fun rule(json: JsonObject): RuleEntity {
        fun text(name: String) = json.get(name)?.takeUnless { it.isJsonNull }?.asString ?: ""
        fun number(name: String, default: Long = 0L) = json.get(name)?.takeUnless { it.isJsonNull }?.asLong ?: default
        val packages = json.get("packages")?.takeUnless { it.isJsonNull}
            ?.asJsonArray?.map { it.asString }?.toSet() ?: emptySet()
        return RuleEntity(
            id = number("id"), enabled = json.get("enabled")?.asBoolean ?: false,
            config = text("config"), domains = text("domains"), ip = text("ip"),
            port = text("port"), sourcePort = text("sourcePort"), network = text("network"),
            source = text("source"), protocol = text("protocol"), ruleset = text("ruleset"),
            outbound = number("outbound"), packages = packages
        )
    }

    private fun fixture(): JsonObject {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (true) {
            val candidate = File(directory, "rootmodule/testdata/root_route_protocol_port_reject.json")
            if (candidate.isFile) {
                return JsonParser.parseString(candidate.readText(Charsets.UTF_8)).asJsonObject
            }
            directory = directory.parentFile ?: break
        }
        throw AssertionError("shared protocol/port fixture not found")
    }
}
