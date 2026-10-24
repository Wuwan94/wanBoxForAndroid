// @author 雾晚
package io.nekohasekai.sagernet

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.bg.projectRootRemoteRuleSets
import io.nekohasekai.sagernet.bg.projectRootRemoteRuleSetOutboundRoutes
import io.nekohasekai.sagernet.bg.projectRootRemoteRuleSetPortOutboundRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RootRemoteRuleSetPortOutboundRouteMigrationTest {
    @Test fun sharedFixtureProjectsRuleSetPortOutboundAndInvertIntoRootGeneratedRoute() {
        val fixture = sharedFixture()
        val input = fixture.getAsJsonObject("generatedConfig").deepCopy()
        assertTrue(projectRootRemoteRuleSets(input))
        assertFalse(projectRootRemoteRuleSetOutboundRoutes(input))
        assertTrue(projectRootRemoteRuleSetPortOutboundRoutes(input))
        assertEquals(fixture.getAsJsonObject("projectedConfig"), input)
    }

    @Test fun richerMatcherAndUnmigratedDefinitionStayOnLegacyPath() {
        val fixture = sharedFixture()
        val input = fixture.getAsJsonObject("generatedConfig").deepCopy()
        val rules = input.getAsJsonObject("route").getAsJsonArray("rules")
        rules[0].asJsonObject.add("domain", JsonParser.parseString("[\"example.org\"]"))
        val originalRule = rules[0].deepCopy()
        assertTrue(projectRootRemoteRuleSets(input))
        assertFalse(projectRootRemoteRuleSetOutboundRoutes(input))
        assertFalse(projectRootRemoteRuleSetPortOutboundRoutes(input))
        assertEquals(originalRule, rules[0])

        val unmigrated = fixture.getAsJsonObject("generatedConfig").deepCopy()
        val unmigratedRules = unmigrated.getAsJsonObject("route").getAsJsonArray("rules")
        unmigratedRules[0].asJsonObject.add("rule_set", JsonParser.parseString("[\"local-set\"]"))
        val originalUnmigratedRule = unmigratedRules[0].deepCopy()
        assertTrue(projectRootRemoteRuleSets(unmigrated))
        assertFalse(projectRootRemoteRuleSetPortOutboundRoutes(unmigrated))
        assertEquals(originalUnmigratedRule, unmigratedRules[0])

        val unsupportedRange = fixture.getAsJsonObject("generatedConfig").deepCopy()
        val rangeRules = unsupportedRange.getAsJsonObject("route").getAsJsonArray("rules")
        rangeRules[0].asJsonObject.add("port_range", JsonParser.parseString("[\"8000:9000\"]"))
        val originalRangeRule = rangeRules[0].deepCopy()
        assertTrue(projectRootRemoteRuleSets(unsupportedRange))
        assertFalse(projectRootRemoteRuleSetPortOutboundRoutes(unsupportedRange))
        assertEquals(originalRangeRule, rangeRules[0])
    }

    private fun sharedFixture(): JsonObject {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
        while (directory != null) {
            val candidate = File(directory, "rootmodule/testdata/root_remote_ruleset_port_outbound_v64.json")
            if (candidate.isFile) return JsonParser.parseString(candidate.readText(Charsets.UTF_8)).asJsonObject
            directory = directory.parentFile
        }
        throw AssertionError("shared Root remote-ruleset port outbound fixture not found")
    }
}
