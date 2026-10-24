// @author 雾晚
package io.nekohasekai.sagernet

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.bg.projectRootRemoteRuleSets
import io.nekohasekai.sagernet.bg.projectRootRemoteRuleSetReferences
import io.nekohasekai.sagernet.bg.projectRootRouteRawRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RootRouteRawRulesMigrationTest {
    @Test fun fixtureKeepsCompoundInvertRemoteAndDnsRulesExactAndOrdered() {
        val fixture = sharedFixture()
        val input = fixture.getAsJsonObject("generatedConfig").deepCopy()
        assertTrue(projectRootRemoteRuleSets(input))
        assertTrue(projectRootRemoteRuleSetReferences(input))
        assertTrue(projectRootRouteRawRules(input))
        assertEquals(fixture.getAsJsonObject("projectedConfigV48"), input)
    }

    @Test fun legacyRegexAndPriorTypedMarkersStayOnTheirOriginalPaths() {
        val fixture = sharedFixture()
        val input = fixture.getAsJsonObject("generatedConfig").deepCopy()
        assertTrue(projectRootRemoteRuleSets(input))
        assertTrue(projectRootRemoteRuleSetReferences(input))
        assertTrue(projectRootRouteRawRules(input))
        val rules = input.getAsJsonObject("route").getAsJsonArray("rules")
        assertEquals(fixture.getAsJsonObject("generatedConfig").getAsJsonObject("route").getAsJsonArray("rules")[2], rules[2])
        assertFalse(rules[2].asJsonObject.has("_wanbox_root_route_raw_rule_v48"))
    }

    private fun sharedFixture(): JsonObject {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
        while (directory != null) {
            val candidate = File(directory, "rootmodule/testdata/root_remote_ruleset_v47.json")
            if (candidate.isFile) return JsonParser.parseString(candidate.readText(Charsets.UTF_8)).asJsonObject
            directory = directory.parentFile
        }
        throw AssertionError("shared Root route-rules fixture not found from test working directory")
    }
}
