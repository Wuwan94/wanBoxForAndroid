// @author 雾晚
package io.nekohasekai.sagernet

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.bg.projectRootRemoteRuleSets
import io.nekohasekai.sagernet.bg.projectRootRemoteRuleSetSourcePortOutboundRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RootRemoteRuleSetSourcePortOutboundRouteMigrationTest {
    @Test fun sharedFixtureProjectsRuleSetAndSourcePortAsRootGeneratedConjunction() {
        val fixture = sharedFixture()
        val input = fixture.getAsJsonObject("generatedConfig").deepCopy()
        assertTrue(projectRootRemoteRuleSets(input))
        assertTrue(projectRootRemoteRuleSetSourcePortOutboundRoutes(input))
        assertEquals(fixture.getAsJsonObject("projectedConfig"), input)
    }

    @Test fun richerAndUnmigratedRulesStayOnLegacyPath() {
        val fixture = sharedFixture()
        val richer = fixture.getAsJsonObject("generatedConfig").deepCopy()
        val rules = richer.getAsJsonObject("route").getAsJsonArray("rules")
        rules[0].asJsonObject.add("domain", JsonParser.parseString("[\"example.org\"]"))
        val original = rules[0].deepCopy()
        assertTrue(projectRootRemoteRuleSets(richer))
        assertFalse(projectRootRemoteRuleSetSourcePortOutboundRoutes(richer))
        assertEquals(original, rules[0])

        val unsupportedPorts = fixture.getAsJsonObject("generatedConfig").deepCopy()
        unsupportedPorts.getAsJsonObject("route").getAsJsonArray("rules")[0].asJsonObject.add("source_port", JsonParser.parseString("[70000]"))
        val unsupportedOriginal = unsupportedPorts.getAsJsonObject("route").getAsJsonArray("rules")[0].deepCopy()
        assertTrue(projectRootRemoteRuleSets(unsupportedPorts))
        assertFalse(projectRootRemoteRuleSetSourcePortOutboundRoutes(unsupportedPorts))
        assertEquals(unsupportedOriginal, unsupportedPorts.getAsJsonObject("route").getAsJsonArray("rules")[0])

        val unmigrated = fixture.getAsJsonObject("generatedConfig").deepCopy()
        unmigrated.getAsJsonObject("route").getAsJsonArray("rules")[0].asJsonObject
            .add("rule_set", JsonParser.parseString("[\"local-set\"]"))
        val unmigratedOriginal = unmigrated.getAsJsonObject("route").getAsJsonArray("rules")[0].deepCopy()
        assertTrue(projectRootRemoteRuleSets(unmigrated))
        assertFalse(projectRootRemoteRuleSetSourcePortOutboundRoutes(unmigrated))
        assertEquals(unmigratedOriginal, unmigrated.getAsJsonObject("route").getAsJsonArray("rules")[0])
    }

    private fun sharedFixture(): JsonObject {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
        while (directory != null) {
            val candidate = File(directory, "rootmodule/testdata/root_remote_ruleset_source_port_outbound_v66.json")
            if (candidate.isFile) return JsonParser.parseString(candidate.readText(Charsets.UTF_8)).asJsonObject
            directory = directory.parentFile
        }
        throw AssertionError("shared Root remote-ruleset source_port outbound fixture not found")
    }
}
