// @author 雾晚
package io.nekohasekai.sagernet

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.bg.projectRootRemoteRuleSets
import io.nekohasekai.sagernet.bg.projectRootRemoteRuleSetNetworkRejectRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RootRemoteRuleSetNetworkRejectRouteMigrationTest {
    @Test fun sharedFixtureProjectsRuleSetNetworkRejectConjunctionInPlace() {
        val fixture = sharedFixture()
        val input = fixture.getAsJsonObject("generatedConfig").deepCopy()
        assertTrue(projectRootRemoteRuleSets(input))
        assertTrue(projectRootRemoteRuleSetNetworkRejectRoutes(input))
        assertEquals(fixture.getAsJsonObject("projectedConfig"), input)
    }

    @Test fun unsupportedNetworkAndAdditionalPredicateStayOnLegacyPath() {
        val fixture = sharedFixture()
        val invalidNetwork = fixture.getAsJsonObject("generatedConfig").deepCopy()
        invalidNetwork.getAsJsonObject("route").getAsJsonArray("rules")[0].asJsonObject
            .add("network", JsonParser.parseString("[\"icmp\"]"))
        val originalInvalid = invalidNetwork.getAsJsonObject("route").getAsJsonArray("rules")[0].deepCopy()
        assertTrue(projectRootRemoteRuleSets(invalidNetwork))
        assertFalse(projectRootRemoteRuleSetNetworkRejectRoutes(invalidNetwork))
        assertEquals(originalInvalid, invalidNetwork.getAsJsonObject("route").getAsJsonArray("rules")[0])

        val richer = fixture.getAsJsonObject("generatedConfig").deepCopy()
        richer.getAsJsonObject("route").getAsJsonArray("rules")[0].asJsonObject
            .add("domain", JsonParser.parseString("[\"example.org\"]"))
        val originalRicher = richer.getAsJsonObject("route").getAsJsonArray("rules")[0].deepCopy()
        assertTrue(projectRootRemoteRuleSets(richer))
        assertFalse(projectRootRemoteRuleSetNetworkRejectRoutes(richer))
        assertEquals(originalRicher, richer.getAsJsonObject("route").getAsJsonArray("rules")[0])
    }

    private fun sharedFixture(): JsonObject {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
        while (directory != null) {
            val candidate = File(directory, "rootmodule/testdata/root_remote_ruleset_network_reject_v72.json")
            if (candidate.isFile) return JsonParser.parseString(candidate.readText(Charsets.UTF_8)).asJsonObject
            directory = directory.parentFile
        }
        throw AssertionError("shared remote-ruleset network reject fixture not found")
    }
}
