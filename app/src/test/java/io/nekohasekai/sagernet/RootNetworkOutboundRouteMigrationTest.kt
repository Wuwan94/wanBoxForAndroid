// @author 雾晚
package io.nekohasekai.sagernet

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.bg.projectRootRouteNetworkOutboundRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RootNetworkOutboundRouteMigrationTest {
    @Test fun sharedFixtureProjectsOnlyExactNetworkOutboundRulesAndPreservesIndexAndSelection() {
        val fixture = sharedFixture()
        val input = fixture.getAsJsonObject("generatedConfig").deepCopy()
        val projected = projectRootRouteNetworkOutboundRules(input, domainRegexProjected = false)
        assertEquals(fixture.getAsJsonObject("spec"), projected)
        assertEquals(
            fixture.getAsJsonObject("projectedConfig").getAsJsonObject("route").getAsJsonArray("rules"),
            input.getAsJsonObject("route").getAsJsonArray("rules")
        )
    }

    @Test fun unsupportedInputsRemainOnTheOriginalLegacyPath() {
        val fixture = sharedFixture()
        for (case in fixture.getAsJsonArray("unsupportedCases")) {
            val item = case.asJsonObject
            val config = JsonObject().apply {
                add("route", JsonObject().apply { add("rules", com.google.gson.JsonArray().apply { add(item.getAsJsonObject("rule").deepCopy()) }) })
            }
            val before = config.deepCopy()
            assertNull(item.get("name").asString, projectRootRouteNetworkOutboundRules(config, domainRegexProjected = false))
            assertEquals(before, config)
        }
    }

    @Test fun domainRegexRejectStaysLegacyWhileIndependentV44OutboundRuleProjects() {
        val fixture = sharedFixture()
        val config = fixture.getAsJsonObject("regexConflictConfig").deepCopy()
        val originalRules = config.getAsJsonObject("route").getAsJsonArray("rules").deepCopy()
        val projected = projectRootRouteNetworkOutboundRules(config, domainRegexProjected = false)
        assertNotNull(projected)
        assertEquals(originalRules[1], config.getAsJsonObject("route").getAsJsonArray("rules")[1])
        val retainedRegexRule = config.getAsJsonObject("route").getAsJsonArray("rules")[1].asJsonObject
        assertEquals("reject", retainedRegexRule.get("action").asString)
        assertTrue(retainedRegexRule.has("domain_regex"))
    }

    private fun sharedFixture(): JsonObject {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
        while (directory != null) {
            val candidate = File(directory, "rootmodule/testdata/root_route_network_outbound_v44.json")
            if (candidate.isFile) return JsonParser.parseString(candidate.readText(Charsets.UTF_8)).asJsonObject
            directory = directory.parentFile
        }
        throw AssertionError("shared Root network-outbound fixture not found from test working directory")
    }
}
