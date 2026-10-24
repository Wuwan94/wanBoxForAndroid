// @author 雾晚
package io.nekohasekai.sagernet

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.bg.projectRootRemoteRuleSets
import io.nekohasekai.sagernet.bg.projectRootRemoteRuleSetOutboundRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RootRemoteRuleSetOutboundRouteMigrationTest {
    @Test fun sharedFixtureProjectsRuleSetOutboundAndInvertIntoRootGeneratedRoute() {
        val fixture = sharedFixture()
        val input = fixture.getAsJsonObject("generatedConfig").deepCopy()
        assertTrue(projectRootRemoteRuleSets(input))
        assertTrue(projectRootRemoteRuleSetOutboundRoutes(input))
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
        assertEquals(originalRule, rules[0])
    }

    private fun sharedFixture(): JsonObject {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
        while (directory != null) {
            val candidate = File(directory, "rootmodule/testdata/root_remote_ruleset_outbound_v54.json")
            if (candidate.isFile) return JsonParser.parseString(candidate.readText(Charsets.UTF_8)).asJsonObject
            directory = directory.parentFile
        }
        throw AssertionError("shared Root remote-ruleset outbound fixture not found")
    }
}
