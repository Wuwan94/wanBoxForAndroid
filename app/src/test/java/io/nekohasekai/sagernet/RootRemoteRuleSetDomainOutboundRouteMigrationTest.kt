// @author 雾晚
package io.nekohasekai.sagernet

import com.google.gson.JsonParser
import java.io.File
import io.nekohasekai.sagernet.bg.ROOT_REMOTE_RULE_SET_DOMAIN_OUTBOUND_ROUTE_MARKER
import io.nekohasekai.sagernet.bg.projectRootRemoteRuleSetDomainOutboundRoutes
import io.nekohasekai.sagernet.bg.projectRootRemoteRuleSets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootRemoteRuleSetDomainOutboundRouteMigrationTest {
    @Test
    fun sharedV68FixtureMatchesAppProjection() {
        val fixture = sharedFixture()
        val generated = fixture.getAsJsonObject("generatedConfig").deepCopy()
        assertTrue(projectRootRemoteRuleSets(generated))
        assertTrue(projectRootRemoteRuleSetDomainOutboundRoutes(generated))
        assertEquals(fixture.getAsJsonObject("projectedConfig"), generated)
    }

    private fun sharedFixture(): com.google.gson.JsonObject {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
        while (directory != null) {
            val candidate = File(directory, "rootmodule/testdata/root_remote_ruleset_domain_outbound_v68.json")
            if (candidate.isFile) return JsonParser.parseString(candidate.readText(Charsets.UTF_8)).asJsonObject
            directory = directory.parentFile
        }
        throw AssertionError("shared Root remote-ruleset domain outbound fixture not found")
    }

    @Test
    fun migratedRuleSetAndDomainAreProjectedAsConjunctionWithOrderAndInvert() {
        val config = JsonParser.parseString("""
            {"route":{"rule_set":[{"_wanbox_root_remote_rule_set_v46":{"tag":"remote-a"}}],"rules":[
              {"outbound":"before"},
              {"rule_set":["remote-a"],"domain_suffix":["example.org"],"domain_keyword":["video"],"outbound":"proxy-a","invert":true},
              {"outbound":"after"}
            ]}}
        """.trimIndent()).asJsonObject

        assertTrue(projectRootRemoteRuleSetDomainOutboundRoutes(config))
        val rules = config.getAsJsonObject("route").getAsJsonArray("rules")
        assertEquals("before", rules[0].asJsonObject.get("outbound").asString)
        val marker = rules[1].asJsonObject.getAsJsonObject(ROOT_REMOTE_RULE_SET_DOMAIN_OUTBOUND_ROUTE_MARKER)
        assertEquals(1, marker.get("index").asInt)
        assertEquals("remote-a", marker.getAsJsonArray("tags")[0].asString)
        assertEquals("example.org", marker.getAsJsonArray("suffixes")[0].asString)
        assertEquals("video", marker.getAsJsonArray("keywords")[0].asString)
        assertTrue(marker.get("invert").asBoolean)
        assertEquals("after", rules[2].asJsonObject.get("outbound").asString)
    }

    @Test
    fun unsupportedSetOrAdditionalPredicateStaysOnAppPath() {
        val config = JsonParser.parseString("""
            {"route":{"rule_set":[{"_wanbox_root_remote_rule_set_v46":{"tag":"remote-a"}}],"rules":[
              {"rule_set":["local"],"domain":["example.org"],"outbound":"proxy-a"},
              {"rule_set":["remote-a"],"domain":["example.org"],"network":["tcp"],"outbound":"proxy-a"},
              {"rule_set":["remote-a"],"domain":["example.org,other.org"],"outbound":"proxy-a"}
            ]}}
        """.trimIndent()).asJsonObject

        assertFalse(projectRootRemoteRuleSetDomainOutboundRoutes(config))
        assertEquals("local", config.getAsJsonObject("route").getAsJsonArray("rules")[0].asJsonObject.getAsJsonArray("rule_set")[0].asString)
    }
}
