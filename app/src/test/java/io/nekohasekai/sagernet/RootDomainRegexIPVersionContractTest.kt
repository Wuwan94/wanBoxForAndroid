// @author 雾晚
package io.nekohasekai.sagernet

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.bg.projectRootRouteDomainRegexRejectRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class RootDomainRegexIPVersionContractTest {
    private fun contract(): JsonObject {
        val start = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        val file = generateSequence(start) { it.parentFile }
            .map { File(it, "rootmodule/testdata/root_route_domain_regex_ip_version_contract.json") }
            .firstOrNull { it.isFile } ?: throw AssertionError("shared domain_regex/IP-version contract fixture not found")
        return JsonParser.parseString(file.readText(Charsets.UTF_8)).asJsonObject
    }

    @Test fun regexRejectProjectionStaysDisabledAndLeavesGeneratedRulesUntouched() {
        val fixture = contract()
        val cases = fixture.getAsJsonArray("rawProjectionCases").map { it.asJsonObject.getAsJsonObject("rule") }
        val fallbacks = fixture.getAsJsonArray("rawProjectionFallbacks").map { it.asJsonObject.getAsJsonObject("rule") }
        for (rule in cases + fallbacks) {
            val config = JsonObject().apply {
                add("route", JsonObject().apply { add("rules", com.google.gson.JsonArray().apply { add(rule.deepCopy()) }) })
            }
            val before = config.deepCopy()
            assertNull(projectRootRouteDomainRegexRejectRules(config))
            assertEquals(before, config)
        }
    }

    @Test fun actualSnapshotBuilderDoesNotActivateTheUnprovenV43Projection() {
        val start = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        val client = generateSequence(start) { it.parentFile }
            .map { File(it, "app/src/main/java/io/nekohasekai/sagernet/bg/RootModuleClient.kt") }
            .firstOrNull { it.isFile }?.readText(Charsets.UTF_8)
            ?: throw AssertionError("RootModuleClient.kt not found")
        assertFalse(client.contains("projectRootRouteDomainRegexRejectRules(config.asJsonObject)"))
        assertFalse(client.contains("rootRouteDomainRegexRejectRules?.let"))
    }
}
