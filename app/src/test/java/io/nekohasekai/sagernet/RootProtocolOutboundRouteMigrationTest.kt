// @author 雾晚
package io.nekohasekai.sagernet

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.bg.projectRootProtocolOutboundRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RootProtocolOutboundRouteMigrationTest {
    @Test fun sharedFixtureProjectsSupportedProtocolOnlyRulesInPlace() {
        val fixture = fixture()
        val input = fixture.getAsJsonObject("generatedConfig").deepCopy()
        assertTrue(projectRootProtocolOutboundRoutes(input))
        assertEquals(fixture.getAsJsonObject("projectedConfig"), input)
    }

    @Test fun unsupportedProtocolAndRicherRulesStayOnLegacyPath() {
        val invalid = fixture().getAsJsonObject("generatedConfig").deepCopy()
        invalid.getAsJsonObject("route").getAsJsonArray("rules")[1].asJsonObject
            .add("protocol", JsonParser.parseString("[\"bogus\"]"))
        val originalInvalid = invalid.getAsJsonObject("route").getAsJsonArray("rules")[1].deepCopy()
        assertTrue(projectRootProtocolOutboundRoutes(invalid)) // the independent DNS protocol rule still moves
        assertEquals(originalInvalid, invalid.getAsJsonObject("route").getAsJsonArray("rules")[1])

        val richer = fixture().getAsJsonObject("generatedConfig").deepCopy()
        richer.getAsJsonObject("route").getAsJsonArray("rules")[1].asJsonObject
            .add("domain", JsonParser.parseString("[\"example.org\"]"))
        val originalRicher = richer.getAsJsonObject("route").getAsJsonArray("rules")[1].deepCopy()
        assertTrue(projectRootProtocolOutboundRoutes(richer)) // the independent DNS protocol rule still moves
        assertEquals(originalRicher, richer.getAsJsonObject("route").getAsJsonArray("rules")[1])
    }

    private fun fixture(): JsonObject {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
        while (directory != null) {
            val candidate = File(directory, "rootmodule/testdata/root_protocol_outbound_v78.json")
            if (candidate.isFile) return JsonParser.parseString(candidate.readText(Charsets.UTF_8)).asJsonObject
            directory = directory.parentFile
        }
        throw AssertionError("shared protocol outbound fixture not found")
    }
}
