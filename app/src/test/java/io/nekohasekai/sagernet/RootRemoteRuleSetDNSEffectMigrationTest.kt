// @author 雾晚
package io.nekohasekai.sagernet

import com.google.gson.JsonParser
import io.nekohasekai.sagernet.bg.ROOT_REMOTE_RULE_SET_DNS_EFFECT_MARKER
import io.nekohasekai.sagernet.bg.projectRootRemoteRuleSetDNSEffects
import io.nekohasekai.sagernet.bg.projectRootRemoteRuleSets
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RootRemoteRuleSetDNSEffectMigrationTest {
    @Test fun generatesOnlySupportedEffectsAndKeepsTheirSlots() {
        val fixture = fixture()
        val projected = fixture.getAsJsonObject("generatedConfig")
        assertTrue(projectRootRemoteRuleSets(projected))
        assertTrue(projectRootRemoteRuleSetDNSEffects(projected))
        val actual = projected.getAsJsonObject("dns").getAsJsonArray("rules")
        val expected = fixture.getAsJsonObject("projectedConfig").getAsJsonObject("dns").getAsJsonArray("rules")
        assertEquals(expected, actual)
        assertEquals(6, actual.size())
        assertEquals("legacy-before", actual[0].asJsonObject.get("tag").asString)
        assertEquals("direct", actual[1].asJsonObject.getAsJsonObject(ROOT_REMOTE_RULE_SET_DNS_EFFECT_MARKER).get("kind").asString)
        assertEquals("reject", actual[2].asJsonObject.getAsJsonObject(ROOT_REMOTE_RULE_SET_DNS_EFFECT_MARKER).get("kind").asString)
        assertEquals("fake", actual[3].asJsonObject.getAsJsonObject(ROOT_REMOTE_RULE_SET_DNS_EFFECT_MARKER).get("kind").asString)
        assertEquals("legacy-after", actual[5].asJsonObject.get("tag").asString)
    }

    @Test fun leavesUnmigratedAndCustomRulesOnAppPath() {
        val fixture = fixture()
        val config = fixture.getAsJsonObject("unmigratedConfig")
        assertTrue(projectRootRemoteRuleSets(config))
        assertFalse(projectRootRemoteRuleSetDNSEffects(config))
        val rules = config.getAsJsonObject("dns").getAsJsonArray("rules")
        assertEquals(2, rules.size())
        assertFalse(rules[0].asJsonObject.has(ROOT_REMOTE_RULE_SET_DNS_EFFECT_MARKER))
        assertFalse(rules[1].asJsonObject.has(ROOT_REMOTE_RULE_SET_DNS_EFFECT_MARKER))
    }

    private fun fixture() : com.google.gson.JsonObject {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (true) {
            val candidate = File(directory, "rootmodule/testdata/root_dns_ruleset_effect_v56.json")
            if (candidate.isFile) return JsonParser.parseString(candidate.readText(Charsets.UTF_8)).asJsonObject
            directory = directory.parentFile ?: break
        }
        throw AssertionError("shared Root DNS ruleset fixture not found")
    }
}
