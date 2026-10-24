// @author 雾晚
package io.nekohasekai.sagernet

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.bg.projectRootRemoteRuleSets
import io.nekohasekai.sagernet.bg.projectRootRemoteRuleSetPortRangeOutboundRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RootRemoteRuleSetPortRangeOutboundRouteMigrationTest {
    @Test fun sharedFixtureProjectsRuleSetAndPortRangesAsOneConjunction() {
        val fixture = sharedFixture()
        val input = fixture.getAsJsonObject("generatedConfig").deepCopy()
        assertTrue(projectRootRemoteRuleSets(input))
        assertTrue(projectRootRemoteRuleSetPortRangeOutboundRoutes(input))
        assertEquals(fixture.getAsJsonObject("projectedConfig"), input)
    }

    @Test fun unsupportedOrRicherRulesRemainOnAppPath() {
        val fixture = sharedFixture()
        val richer = fixture.getAsJsonObject("generatedConfig").deepCopy()
        val rule = richer.getAsJsonObject("route").getAsJsonArray("rules")[0].asJsonObject
        rule.add("domain", JsonParser.parseString("[\"example.org\"]"))
        val original = rule.deepCopy()
        assertTrue(projectRootRemoteRuleSets(richer))
        assertFalse(projectRootRemoteRuleSetPortRangeOutboundRoutes(richer))
        assertEquals(original, richer.getAsJsonObject("route").getAsJsonArray("rules")[0])

        val malformed = fixture.getAsJsonObject("generatedConfig").deepCopy()
        val malformedRule = malformed.getAsJsonObject("route").getAsJsonArray("rules")[0].asJsonObject
        malformedRule.add("port_range", JsonParser.parseString("[\"100:80\"]"))
        val originalMalformed = malformedRule.deepCopy()
        assertTrue(projectRootRemoteRuleSets(malformed))
        assertFalse(projectRootRemoteRuleSetPortRangeOutboundRoutes(malformed))
        assertEquals(originalMalformed, malformed.getAsJsonObject("route").getAsJsonArray("rules")[0])
    }

    private fun sharedFixture(): JsonObject {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
        while (directory != null) {
            val candidate = File(directory, "rootmodule/testdata/root_remote_ruleset_port_range_outbound_v76.json")
            if (candidate.isFile) return JsonParser.parseString(candidate.readText(Charsets.UTF_8)).asJsonObject
            directory = directory.parentFile
        }
        throw AssertionError("shared Root remote-ruleset port-range outbound fixture not found")
    }
}
