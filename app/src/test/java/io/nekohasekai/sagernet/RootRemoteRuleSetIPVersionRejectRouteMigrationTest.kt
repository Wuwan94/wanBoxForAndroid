// @author 雾晚
package io.nekohasekai.sagernet

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.bg.projectRootRemoteRuleSets
import io.nekohasekai.sagernet.bg.projectRootRemoteRuleSetIPVersionRejectRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RootRemoteRuleSetIPVersionRejectRouteMigrationTest {
    @Test fun sharedFixtureProjectsRuleSetIPVersionRejectConjunctionInPlace() {
        val fixture = sharedFixture(); val input = fixture.getAsJsonObject("generatedConfig").deepCopy()
        assertTrue(projectRootRemoteRuleSets(input)); assertTrue(projectRootRemoteRuleSetIPVersionRejectRoutes(input))
        assertEquals(fixture.getAsJsonObject("projectedConfig"), input)
    }
    @Test fun richerOrUnmigratedInputsRemainOnLegacyPath() {
        val fixture = sharedFixture(); val richer = fixture.getAsJsonObject("generatedConfig").deepCopy()
        richer.getAsJsonObject("route").getAsJsonArray("rules")[0].asJsonObject.add("network", JsonParser.parseString("[\"tcp\"]"))
        val original = richer.getAsJsonObject("route").getAsJsonArray("rules")[0].deepCopy()
        assertTrue(projectRootRemoteRuleSets(richer)); assertFalse(projectRootRemoteRuleSetIPVersionRejectRoutes(richer))
        assertEquals(original, richer.getAsJsonObject("route").getAsJsonArray("rules")[0])
        val missingSet = fixture.getAsJsonObject("generatedConfig").deepCopy()
        missingSet.getAsJsonObject("route").getAsJsonArray("rule_set")[0].asJsonObject.remove("format")
        val originalRule = missingSet.getAsJsonObject("route").getAsJsonArray("rules")[0].deepCopy()
        assertFalse(projectRootRemoteRuleSets(missingSet)); assertFalse(projectRootRemoteRuleSetIPVersionRejectRoutes(missingSet))
        assertEquals(originalRule, missingSet.getAsJsonObject("route").getAsJsonArray("rules")[0])
    }
    private fun sharedFixture(): JsonObject {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
        while (directory != null) {
            val candidate = File(directory, "rootmodule/testdata/root_remote_ruleset_ip_version_reject_v75.json")
            if (candidate.isFile) return JsonParser.parseString(candidate.readText(Charsets.UTF_8)).asJsonObject
            directory = directory.parentFile
        }
        throw AssertionError("shared remote-ruleset IP-version reject fixture not found")
    }
}
