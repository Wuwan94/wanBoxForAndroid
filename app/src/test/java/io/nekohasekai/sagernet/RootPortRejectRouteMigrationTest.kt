// @author 雾晚
package io.nekohasekai.sagernet

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.bg.projectRootRoutePortRejectRules
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.route.RootPortRejectRouteInput
import io.nekohasekai.sagernet.route.parseRootPortRejectPorts
import io.nekohasekai.sagernet.route.rootPortRejectRouteInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

class RootPortRejectRouteMigrationTest {
    @Test fun sharedFixtureKeepsOriginalPortTextAndProjectsRawModuleInput() {
        val fixture = sharedFixture()
        val eligible = rule(fixture.getAsJsonObject("eligibleRule"))
        val selected = rootPortRejectRouteInput(eligible)
        assertEquals(RootPortRejectRouteInput(41L, "80, 443\n8443"), selected)

        val projected = projectRootRoutePortRejectRules(fixture.getAsJsonObject("inputConfig").deepCopy())
        assertNotNull(projected)
        assertEquals(fixture.getAsJsonObject("spec"), projected)

        val expectedPorts = fixture.getAsJsonObject("expectedConfig")
            .getAsJsonObject("route").getAsJsonArray("rules")
            .mapNotNull { item ->
                item.asJsonObject.takeIf { it.get("action")?.asString == "reject" }
                    ?.getAsJsonArray("port")?.map { it.asInt }
            }
        val rawPorts = fixture.getAsJsonObject("spec").getAsJsonArray("rules")
            .map { parseRootPortRejectPorts(it.asJsonObject.get("portText").asString) }
        assertEquals(expectedPorts, rawPorts)
    }

    @Test fun unsupportedFixtureRulesStayOnTheLegacyPath() {
        val fixture = sharedFixture()
        for (case in fixture.getAsJsonArray("unsupportedCases")) {
            val item = case.asJsonObject
            assertNull(item.get("name").asString, rootPortRejectRouteInput(rule(item.getAsJsonObject("rule"))))
        }
    }

    private fun rule(json: JsonObject): RuleEntity {
        fun text(name: String) = json.get(name)?.takeUnless { it.isJsonNull }?.asString ?: ""
        fun number(name: String, default: Long = 0L) = json.get(name)?.takeUnless { it.isJsonNull }?.asLong ?: default
        val packages = json.get("packages")?.takeUnless { it.isJsonNull }
            ?.asJsonArray?.map { it.asString }?.toSet() ?: emptySet()
        return RuleEntity(
            id = number("id"),
            enabled = json.get("enabled")?.asBoolean ?: false,
            config = text("config"),
            domains = text("domains"),
            ip = text("ip"),
            port = text("port"),
            sourcePort = text("sourcePort"),
            network = text("network"),
            source = text("source"),
            protocol = text("protocol"),
            ruleset = text("ruleset"),
            outbound = number("outbound"),
            packages = packages,
        )
    }

    private fun sharedFixture(): JsonObject {
        var directory: File? = File(System.getProperty("user.dir")).canonicalFile
        while (directory != null) {
            val candidate = File(directory, "rootmodule/testdata/root_route_port_reject.json")
            if (candidate.isFile) return JsonParser.parseString(candidate.readText(Charsets.UTF_8)).asJsonObject
            directory = directory.parentFile
        }
        throw AssertionError("shared root port-reject fixture not found from test working directory")
    }
}
