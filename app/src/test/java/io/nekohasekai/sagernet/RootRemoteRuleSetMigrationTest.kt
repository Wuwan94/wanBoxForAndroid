// @author 雾晚
package io.nekohasekai.sagernet

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.bg.projectRootRemoteRuleSets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RootRemoteRuleSetMigrationTest {
    @Test fun sharedFixtureProjectsOnlyExactGeneratedRemoteBinaryEntries() {
        val fixture = sharedFixture()
        val input = fixture.getAsJsonObject("generatedConfig").deepCopy()
        assertTrue(projectRootRemoteRuleSets(input))
        assertEquals(fixture.getAsJsonObject("projectedConfig"), input)
        // Rules referencing the generated tags, including a composite/inverted legacy rule,
        // remain in their original positions and shape in this narrow definition-only slice.
        assertEquals(
            fixture.getAsJsonObject("generatedConfig").getAsJsonObject("route").getAsJsonArray("rules"),
            input.getAsJsonObject("route").getAsJsonArray("rules")
        )
    }

    @Test fun malformedGeneratedUrlFallsBackWithoutBlockingOtherValidDefinitions() {
        val fixture = sharedFixture()
        val config = fixture.getAsJsonObject("generatedConfig").deepCopy()
        val sets = config.getAsJsonObject("route").getAsJsonArray("rule_set")
        sets[0].asJsonObject.addProperty("url", "javascript:alert(1)")
        val original = sets[0].deepCopy()
        assertTrue(projectRootRemoteRuleSets(config))
        assertEquals(original, config.getAsJsonObject("route").getAsJsonArray("rule_set")[0])
        assertTrue(config.getAsJsonObject("route").getAsJsonArray("rule_set")[3].asJsonObject.has("_wanbox_root_remote_rule_set_v46"))
    }

    @Test fun unsupportedRuleSetShapesRemainExactlyOnTheLegacyPath() {
        val fixture = sharedFixture()
        val config = fixture.getAsJsonObject("generatedConfig").deepCopy()
        val sets = config.getAsJsonObject("route").getAsJsonArray("rule_set")
        val unsupported = sets[2].deepCopy()
        val before = config.deepCopy()
        assertTrue(projectRootRemoteRuleSets(config))
        assertEquals(unsupported, config.getAsJsonObject("route").getAsJsonArray("rule_set")[2])
        assertEquals(before.getAsJsonObject("route").getAsJsonArray("rule_set")[1], config.getAsJsonObject("route").getAsJsonArray("rule_set")[1])
        assertFalse(config.getAsJsonObject("route").getAsJsonArray("rule_set")[2].asJsonObject.has("_wanbox_root_remote_rule_set_v46"))
    }

    private fun sharedFixture(): JsonObject {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
        while (directory != null) {
            val candidate = File(directory, "rootmodule/testdata/root_remote_ruleset_v46.json")
            if (candidate.isFile) return JsonParser.parseString(candidate.readText(Charsets.UTF_8)).asJsonObject
            directory = directory.parentFile
        }
        throw AssertionError("shared Root remote-ruleset fixture not found from test working directory")
    }
}
