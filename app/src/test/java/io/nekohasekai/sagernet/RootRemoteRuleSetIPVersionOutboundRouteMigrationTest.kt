// @author 雾晚
package io.nekohasekai.sagernet

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.bg.projectRootRemoteRuleSets
import io.nekohasekai.sagernet.bg.projectRootRemoteRuleSetIPVersionOutboundRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RootRemoteRuleSetIPVersionOutboundRouteMigrationTest {
    @Test fun sharedFixtureProjectsRuleSetAndIPVersionAsRootGeneratedConjunction() {
        val fixture = sharedFixture()
        val input = fixture.getAsJsonObject("generatedConfig").deepCopy()
        assertTrue(projectRootRemoteRuleSets(input))
        assertTrue(projectRootRemoteRuleSetIPVersionOutboundRoutes(input))
        assertEquals(fixture.getAsJsonObject("projectedConfig"), input)
    }

    @Test fun richerAndUnmigratedRulesStayOnLegacyPath() {
        val fixture = sharedFixture()
        val richer = fixture.getAsJsonObject("generatedConfig").deepCopy()
        val rules = richer.getAsJsonObject("route").getAsJsonArray("rules")
        rules[0].asJsonObject.add("domain", JsonParser.parseString("[\"example.org\"]"))
        val original = rules[0].deepCopy()
        assertTrue(projectRootRemoteRuleSets(richer))
        assertFalse(projectRootRemoteRuleSetIPVersionOutboundRoutes(richer))
        assertEquals(original, rules[0])

        val unsupportedVersion = fixture.getAsJsonObject("generatedConfig").deepCopy()
        unsupportedVersion.getAsJsonObject("route").getAsJsonArray("rules")[0].asJsonObject.addProperty("ip_version", 5)
        val unsupportedOriginal = unsupportedVersion.getAsJsonObject("route").getAsJsonArray("rules")[0].deepCopy()
        assertTrue(projectRootRemoteRuleSets(unsupportedVersion))
        assertFalse(projectRootRemoteRuleSetIPVersionOutboundRoutes(unsupportedVersion))
        assertEquals(unsupportedOriginal, unsupportedVersion.getAsJsonObject("route").getAsJsonArray("rules")[0])

        val unmigrated = fixture.getAsJsonObject("generatedConfig").deepCopy()
        unmigrated.getAsJsonObject("route").getAsJsonArray("rules")[0].asJsonObject
            .add("rule_set", JsonParser.parseString("[\"local-set\"]"))
        val unmigratedOriginal = unmigrated.getAsJsonObject("route").getAsJsonArray("rules")[0].deepCopy()
        assertTrue(projectRootRemoteRuleSets(unmigrated))
        assertFalse(projectRootRemoteRuleSetIPVersionOutboundRoutes(unmigrated))
        assertEquals(unmigratedOriginal, unmigrated.getAsJsonObject("route").getAsJsonArray("rules")[0])
    }

    private fun sharedFixture(): JsonObject {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
        while (directory != null) {
            val candidate = File(directory, "rootmodule/testdata/root_remote_ruleset_ip_version_outbound_v65.json")
            if (candidate.isFile) return JsonParser.parseString(candidate.readText(Charsets.UTF_8)).asJsonObject
            directory = directory.parentFile
        }
        throw AssertionError("shared Root remote-ruleset ip_version outbound fixture not found")
    }
}
