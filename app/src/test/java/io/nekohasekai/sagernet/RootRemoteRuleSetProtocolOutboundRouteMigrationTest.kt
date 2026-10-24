// @author 雾晚
package io.nekohasekai.sagernet

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.bg.projectRootRemoteRuleSets
import io.nekohasekai.sagernet.bg.projectRootRemoteRuleSetProtocolOutboundRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RootRemoteRuleSetProtocolOutboundRouteMigrationTest {
    @Test fun sharedFixtureProjectsRemoteRuleSetAndProtocolConjunctionInPlace() {
        val fixture = sharedFixture()
        val input = fixture.getAsJsonObject("generatedConfig").deepCopy()
        assertTrue(projectRootRemoteRuleSets(input))
        assertTrue(projectRootRemoteRuleSetProtocolOutboundRoutes(input))
        assertEquals(fixture.getAsJsonObject("projectedConfig"), input)
    }

    @Test fun unsupportedProtocolAndAdditionalPredicateStayOnLegacyPath() {
        val fixture = sharedFixture()
        val invalidProtocol = fixture.getAsJsonObject("generatedConfig").deepCopy()
        invalidProtocol.getAsJsonObject("route").getAsJsonArray("rules")[0].asJsonObject
            .add("protocol", JsonParser.parseString("[\"bogus\"]"))
        val invalidOriginal = invalidProtocol.getAsJsonObject("route").getAsJsonArray("rules")[0].deepCopy()
        assertTrue(projectRootRemoteRuleSets(invalidProtocol))
        assertFalse(projectRootRemoteRuleSetProtocolOutboundRoutes(invalidProtocol))
        assertEquals(invalidOriginal, invalidProtocol.getAsJsonObject("route").getAsJsonArray("rules")[0])

        val richer = fixture.getAsJsonObject("generatedConfig").deepCopy()
        richer.getAsJsonObject("route").getAsJsonArray("rules")[0].asJsonObject
            .add("domain", JsonParser.parseString("[\"example.org\"]"))
        val richerOriginal = richer.getAsJsonObject("route").getAsJsonArray("rules")[0].deepCopy()
        assertTrue(projectRootRemoteRuleSets(richer))
        assertFalse(projectRootRemoteRuleSetProtocolOutboundRoutes(richer))
        assertEquals(richerOriginal, richer.getAsJsonObject("route").getAsJsonArray("rules")[0])
    }

    private fun sharedFixture(): JsonObject {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
        while (directory != null) {
            val candidate = File(directory, "rootmodule/testdata/root_remote_ruleset_protocol_outbound_v77.json")
            if (candidate.isFile) return JsonParser.parseString(candidate.readText(Charsets.UTF_8)).asJsonObject
            directory = directory.parentFile
        }
        throw AssertionError("shared remote-ruleset protocol outbound fixture not found")
    }
}
