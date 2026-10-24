// @author 雾晚
package io.nekohasekai.sagernet

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.bg.ROOT_INVERTED_GROUP_MARKER
import io.nekohasekai.sagernet.bg.projectRootInvertedGroups
import io.nekohasekai.sagernet.bg.projectRootRemoteRuleSets
import io.nekohasekai.sagernet.bg.projectRootRemoteRuleSetReferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RootInvertedGroupMigrationTest {
    @Test fun sharedFixtureMovesOnlyTheNotOrWrapperAndPreservesChildrenAndAction() {
        val fixture = sharedFixture()
        val input = fixture.getAsJsonObject("generatedConfig").deepCopy()
        assertTrue(projectRootRemoteRuleSets(input))
        assertTrue(projectRootRemoteRuleSetReferences(input))
        assertTrue(projectRootInvertedGroups(input))
        val got = input.getAsJsonObject("route").getAsJsonArray("rules")[0].asJsonObject
        val marker = got.getAsJsonObject(ROOT_INVERTED_GROUP_MARKER)
        val original = fixture.getAsJsonObject("generatedConfig").getAsJsonObject("route").getAsJsonArray("rules")[0].asJsonObject
        assertEquals(original.getAsJsonArray("rules"), marker.getAsJsonArray("rules"))
        assertEquals(JsonParser.parseString("{\"outbound\":\"direct\"}"), marker.getAsJsonObject("action"))
        assertEquals(0, marker.get("index").asInt)
    }

    @Test fun domainRegexInsideLogicalGroupStaysOnLegacyPath() {
        val input = sharedFixture().getAsJsonObject("generatedConfig").deepCopy()
        val group = input.getAsJsonObject("route").getAsJsonArray("rules")[0].asJsonObject
        group.getAsJsonArray("rules")[0].asJsonObject.addProperty("domain_regex", "^example\\.org$")
        assertFalse(projectRootInvertedGroups(input))
        assertEquals("logical", group.get("type").asString)
        assertEquals(true, group.get("invert").asBoolean)
    }

    private fun sharedFixture(): JsonObject {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
        while (directory != null) {
            val candidate = File(directory, "rootmodule/testdata/root_remote_ruleset_v47.json")
            if (candidate.isFile) return JsonParser.parseString(candidate.readText(Charsets.UTF_8)).asJsonObject
            directory = directory.parentFile
        }
        throw AssertionError("shared route fixture not found")
    }
}
