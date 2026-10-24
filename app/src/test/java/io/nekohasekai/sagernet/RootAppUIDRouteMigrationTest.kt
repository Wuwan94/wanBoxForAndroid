// @author 雾晚
package io.nekohasekai.sagernet

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.route.projectRootRouteAppUIDRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class RootAppUIDRouteMigrationTest {
    @Test fun sharedFixtureProjectsExactPackageUidInputAndKeepsSourceConfigUnchanged() {
        val fixture = sharedFixture()
        val input = fixture.getAsJsonObject("generatedConfig").deepCopy()
        val original = input.deepCopy()
        assertEquals(fixture.getAsJsonObject("spec"), projectRootRouteAppUIDRules(input))
        assertEquals(original, input)
    }

    @Test fun earlierRootProjectionRemovalRebasesSnapshotIndexWithoutChangingRuleOrder() {
        val fixture = sharedFixture()
        val config = fixture.getAsJsonObject("generatedConfig").deepCopy()
        val rules = config.getAsJsonObject("route").getAsJsonArray("rules")
        rules.remove(0) // A prior eligible Root rule was projected out before this marker.
        val expected = fixture.getAsJsonObject("spec").deepCopy()
        expected.getAsJsonArray("rules")[0].asJsonObject.addProperty("index", 0)
        assertEquals(expected, projectRootRouteAppUIDRules(config))
    }

    @Test fun unsupportedRicherRulesRemainOnLegacyPath() {
        val fixture = sharedFixture()
        for (case in fixture.getAsJsonArray("unsupportedCases")) {
            val item = case.asJsonObject
            val config = JsonObject().apply {
                add("route", JsonObject().apply {
                    add("rules", com.google.gson.JsonArray().apply { add(item.getAsJsonObject("rule").deepCopy()) })
                })
            }
            val before = config.deepCopy()
            assertNull(item.get("name").asString, projectRootRouteAppUIDRules(config))
            assertEquals(before, config)
        }
    }

    private fun sharedFixture(): JsonObject {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
        while (directory != null) {
            val candidate = File(directory, "rootmodule/testdata/root_route_app_uid_v45.json")
            if (candidate.isFile) return JsonParser.parseString(candidate.readText(Charsets.UTF_8)).asJsonObject
            directory = directory.parentFile
        }
        throw AssertionError("shared Root app UID fixture not found from test working directory")
    }
}
