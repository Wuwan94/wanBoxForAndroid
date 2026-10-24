// @author 雾晚
package io.nekohasekai.sagernet

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.route.ROOT_APP_IDENTITY_DNS_RULE_MARKER
import io.nekohasekai.sagernet.route.projectRootAppIdentityDNSRules
import io.nekohasekai.sagernet.route.rootAppIdentityDNSRuleInput
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RootAppIdentityDNSRuleMigrationTest {
    @Test fun sharedFixtureMatchesConfigBuilderInputsAndPreservesDNSOrdering() {
        val fixture = fixture()
        val inputs = fixture.getAsJsonArray("appInputs")
        val projected = fixture.getAsJsonObject("projectedConfig")
        assertTrue(projectRootAppIdentityDNSRules(projected))
        val projectedRules = projected.getAsJsonObject("dns").getAsJsonArray("rules")
        inputs.forEachIndexed { index, element ->
            val item = element.asJsonObject
            val input = rootAppIdentityDNSRuleInput(
                rule(item), item.get("action").asString,
                item.getAsJsonArray("uids").map { it.asInt }, item.get("fakeDns").asBoolean
            )
            assertNotNull(input)
            assertEquals(item.get("id").asLong, input!!.ruleId)
            assertEquals(item.getAsJsonArray("packages").map { it.asString }, input.packages)
            assertEquals(item.getAsJsonArray("uids").map { it.asInt }, input.uids)
            assertEquals(item.get("kind").asString, input.kind)
            assertEquals(item.get("fakeDns").asBoolean && item.get("kind").asString == "proxy", input.fakeDns)
            val marker = projectedRules[index + 1].asJsonObject.getAsJsonObject(ROOT_APP_IDENTITY_DNS_RULE_MARKER)
            assertEquals(input.ruleId, marker.get("ruleId").asLong)
            assertEquals(input.packages, marker.getAsJsonArray("packages").map { it.asString })
            assertEquals(input.uids, marker.getAsJsonArray("uids").map { it.asInt })
            assertEquals(input.kind, marker.get("kind").asString)
            assertEquals(input.fakeDns, marker.get("fakeDns").asBoolean)
        }
        val expectedRules = fixture.getAsJsonObject("expectedConfig").getAsJsonObject("dns").getAsJsonArray("rules")
        assertEquals(expectedRules.size(), projectedRules.size())
        assertEquals("legacy-before", projectedRules.first().asJsonObject.get("tag").asString)
        assertEquals("legacy-after", projectedRules.last().asJsonObject.get("tag").asString)
        assertTrue(projectedRules[1].asJsonObject.has(ROOT_APP_IDENTITY_DNS_RULE_MARKER))
    }

    @Test fun onlyPackageIdentityOnlyRouteAndRejectRulesAreEligible() {
        val base = rule(801, -1, setOf("com.example.app"))
        assertNotNull(rootAppIdentityDNSRuleInput(base, "route", listOf(10001), false))
        assertNotNull(rootAppIdentityDNSRuleInput(base.copy(outbound = -2), "reject", listOf(10001), false))
        assertNull(rootAppIdentityDNSRuleInput(base.copy(packages = emptySet()), "route", listOf(10001), false))
        assertNull(rootAppIdentityDNSRuleInput(base.copy(domains = "example.test"), "route", listOf(10001), false))
        assertNull(rootAppIdentityDNSRuleInput(base.copy(ip = "192.0.2.1/32"), "route", listOf(10001), false))
        assertNull(rootAppIdentityDNSRuleInput(base.copy(config = "{\"invert\":true}"), "route", listOf(10001), false))
        assertNull(rootAppIdentityDNSRuleInput(base, "sniff", listOf(10001), false))
    }

    private fun rule(item: JsonObject): RuleEntity = rule(
        item.get("id").asLong, item.get("outbound").asLong,
        item.getAsJsonArray("packages").map { it.asString }.toSet()
    )

    private fun rule(id: Long, outbound: Long, packages: Set<String>) = RuleEntity(
        id = id, enabled = true, outbound = outbound, packages = packages
    )

    private fun fixture(): JsonObject {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (true) {
            val candidate = File(directory, "rootmodule/testdata/root_dns_app_identity_v79.json")
            if (candidate.isFile) return JsonParser.parseString(candidate.readText(Charsets.UTF_8)).asJsonObject
            directory = directory.parentFile ?: break
        }
        throw AssertionError("shared Root App-identity DNS fixture not found")
    }
}
