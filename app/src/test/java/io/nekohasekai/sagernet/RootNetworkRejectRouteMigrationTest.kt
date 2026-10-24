// @author 雾晚
package io.nekohasekai.sagernet

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.bg.projectRootRouteNetworkRejectRules
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.route.RootNetworkRejectRouteInput
import io.nekohasekai.sagernet.route.parseRootNetworkRejectNetworks
import io.nekohasekai.sagernet.route.rootNetworkRejectRouteInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class RootNetworkRejectRouteMigrationTest {
    @Test fun sharedFixtureProjectsRawInputAndPreservesRouteOrderAndDuplicates() {
        val fixture = sharedFixture()
        val selected = rootNetworkRejectRouteInput(rule(fixture.getAsJsonObject("eligibleRule")))
        assertEquals(RootNetworkRejectRouteInput(61L, "tcp, udp\nudp"), selected)

        val input = fixture.getAsJsonObject("inputConfig").deepCopy()
        val projected = projectRootRouteNetworkRejectRules(input)
        assertNotNull(projected)
        assertEquals(fixture.getAsJsonObject("spec"), projected)

        val expectedRules = fixture.getAsJsonObject("expectedConfig")
            .getAsJsonObject("route").getAsJsonArray("rules")
        val expectedNetworks = input.getAsJsonObject("route").getAsJsonArray("rules")
            .mapIndexedNotNull { index, item ->
                if (!item.asJsonObject.has("_wanbox_root_route_network_reject_v31")) null
                else expectedRules[index].asJsonObject.getAsJsonArray("network").map { it.asString }
            }
        val rawNetworks = fixture.getAsJsonObject("spec").getAsJsonArray("rules")
            .map { parseRootNetworkRejectNetworks(it.asJsonObject.get("networkText").asString) }
        assertEquals(expectedNetworks, rawNetworks)
        assertEquals(
            fixture.getAsJsonObject("expectedConfig").getAsJsonObject("route").getAsJsonArray("rules")[4],
            input.getAsJsonObject("route").getAsJsonArray("rules")[4]
        )
    }

    @Test fun unsupportedRulesRemainOnLegacyPath() {
        val fixture = sharedFixture()
        for (case in fixture.getAsJsonArray("unsupportedCases")) {
            val item = case.asJsonObject
            assertNull(item.get("name").asString, rootNetworkRejectRouteInput(rule(item.getAsJsonObject("rule"))))
        }
        val fallback = fixture.getAsJsonObject("legacyFallbackConfig").deepCopy()
        val beforeProjection = fallback.deepCopy()
        assertNull(projectRootRouteNetworkRejectRules(fallback))
        assertEquals(beforeProjection, fallback)
    }

    @Test fun parserMatchesSharedBoundaryCases() {
        val fixture = sharedFixture()
        val cases = fixture.getAsJsonArray("parserCases")
        assertNotNull(cases)
        for (element in cases) {
            val item = element.asJsonObject
            val parsed = parseRootNetworkRejectNetworks(item.get("raw").asString)
            if (item.get("valid").asBoolean) {
                assertEquals(item.getAsJsonArray("networks").map { it.asString }, parsed)
            } else {
                assertNull(parsed)
            }
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
            val candidate = File(directory, "rootmodule/testdata/root_route_network_reject.json")
            if (candidate.isFile) return JsonParser.parseString(candidate.readText(Charsets.UTF_8)).asJsonObject
            directory = directory.parentFile
        }
        throw AssertionError("shared root network-reject fixture not found from test working directory")
    }
}
