// @author 雾晚
package io.nekohasekai.sagernet

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.bg.projectRootRemoteRuleSets
import io.nekohasekai.sagernet.bg.projectRootRemoteRuleSetIPOutboundRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RootRemoteRuleSetIPOutboundRouteMigrationTest {
    @Test fun sharedFixtureProjectsCIDRConjunctionWithOrderAndInvert() {
        val fixture = sharedFixture()
        val input = fixture.getAsJsonObject("generatedConfig").deepCopy()
        assertTrue(projectRootRemoteRuleSets(input))
        assertTrue(projectRootRemoteRuleSetIPOutboundRoutes(input))
        assertEquals(fixture.getAsJsonObject("projectedConfig"), input)
    }

    @Test fun malformedOrRicherRulesRemainLegacy() {
        val fixture = sharedFixture()
        val richer = fixture.getAsJsonObject("generatedConfig").deepCopy()
        val rules = richer.getAsJsonObject("route").getAsJsonArray("rules")
        rules[0].asJsonObject.add("domain", JsonParser.parseString("[\"example.org\"]"))
        val original = rules[0].deepCopy()
        assertTrue(projectRootRemoteRuleSets(richer))
        assertFalse(projectRootRemoteRuleSetIPOutboundRoutes(richer))
        assertEquals(original, rules[0])

        val malformed = fixture.getAsJsonObject("generatedConfig").deepCopy()
        malformed.getAsJsonObject("route").getAsJsonArray("rules")[0].asJsonObject
            .add("ip_cidr", JsonParser.parseString("[\"example.org/24\"]"))
        val malformedOriginal = malformed.getAsJsonObject("route").getAsJsonArray("rules")[0].deepCopy()
        assertTrue(projectRootRemoteRuleSets(malformed))
        assertFalse(projectRootRemoteRuleSetIPOutboundRoutes(malformed))
        assertEquals(malformedOriginal, malformed.getAsJsonObject("route").getAsJsonArray("rules")[0])
    }

    private fun sharedFixture(): JsonObject {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
        while (directory != null) {
            val candidate = File(directory, "rootmodule/testdata/root_remote_ruleset_ip_outbound_v69.json")
            if (candidate.isFile) return JsonParser.parseString(candidate.readText(Charsets.UTF_8)).asJsonObject
            directory = directory.parentFile
        }
        throw AssertionError("shared remote rule-set IP outbound fixture not found")
    }
}
