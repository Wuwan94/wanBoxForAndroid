// @author 雾晚
package io.nekohasekai.sagernet

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.bg.projectRootRemoteRuleSets
import io.nekohasekai.sagernet.bg.projectRootRemoteRuleSetRejectRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RootRemoteRuleSetRejectRouteMigrationTest {
    @Test fun sharedFixtureProjectsRuleSetRejectInItsOriginalSlot() {
        val fixture = sharedFixture()
        val input = fixture.getAsJsonObject("generatedConfig").deepCopy()
        assertTrue(projectRootRemoteRuleSets(input))
        assertTrue(projectRootRemoteRuleSetRejectRoutes(input))
        assertEquals(fixture.getAsJsonObject("projectedConfig"), input)
    }

    @Test fun unmigratedTagsAndRicherRejectRulesStayOnLegacyPath() {
        val fixture = sharedFixture()
        val unmigrated = fixture.getAsJsonObject("generatedConfig").deepCopy()
        unmigrated.getAsJsonObject("route").getAsJsonArray("rules")[1].asJsonObject
            .add("rule_set", JsonParser.parseString("[\"unmigrated\"]"))
        val originalUnmigrated = unmigrated.getAsJsonObject("route").getAsJsonArray("rules")[1].deepCopy()
        assertTrue(projectRootRemoteRuleSets(unmigrated))
        assertFalse(projectRootRemoteRuleSetRejectRoutes(unmigrated))
        assertEquals(originalUnmigrated, unmigrated.getAsJsonObject("route").getAsJsonArray("rules")[1])

        val richer = fixture.getAsJsonObject("generatedConfig").deepCopy()
        richer.getAsJsonObject("route").getAsJsonArray("rules")[1].asJsonObject
            .add("protocol", JsonParser.parseString("[\"tcp\"]"))
        val originalRicher = richer.getAsJsonObject("route").getAsJsonArray("rules")[1].deepCopy()
        assertTrue(projectRootRemoteRuleSets(richer))
        assertFalse(projectRootRemoteRuleSetRejectRoutes(richer))
        assertEquals(originalRicher, richer.getAsJsonObject("route").getAsJsonArray("rules")[1])
    }

    private fun sharedFixture(): JsonObject {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
        while (directory != null) {
            val candidate = File(directory, "rootmodule/testdata/root_remote_ruleset_reject_v71.json")
            if (candidate.isFile) return JsonParser.parseString(candidate.readText(Charsets.UTF_8)).asJsonObject
            directory = directory.parentFile
        }
        throw AssertionError("shared remote-ruleset reject fixture not found")
    }
}
