// @author 雾晚
package io.nekohasekai.sagernet

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.route.ROOT_APP_IDENTITY_DOMAIN_DNS_RULE_MARKER
import io.nekohasekai.sagernet.route.projectRootAppIdentityDomainDNSRules
import io.nekohasekai.sagernet.route.rootAppIdentityDomainDNSRuleInput
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RootAppIdentityDomainDNSRuleMigrationTest {
    @Test fun sharedFixtureProjectsPackageAndDomainDNSEffectsInPlace() {
        val fixture = fixture()
        val inputs = fixture.getAsJsonArray("appInputs")
        val projected = fixture.getAsJsonObject("projectedConfig")
        assertTrue(projectRootAppIdentityDomainDNSRules(projected))
        val projectedRules = projected.getAsJsonObject("dns").getAsJsonArray("rules")
        inputs.forEachIndexed { index, element ->
            val item = element.asJsonObject
            val input = rootAppIdentityDomainDNSRuleInput(
                rule(item), item.get("action").asString,
                item.getAsJsonArray("uids").map { it.asInt }, item.get("fakeDns").asBoolean
            )
            assertNotNull(input)
            assertEquals(item.get("id").asLong, input!!.ruleId)
            assertEquals(item.getAsJsonArray("packages").map { it.asString }, input.packages)
            assertEquals(item.getAsJsonArray("uids").map { it.asInt }, input.uids)
            assertEquals(item.get("domains").asString, input.domainText)
            assertEquals(item.get("kind").asString, input.kind)
            assertEquals(item.get("fakeDns").asBoolean && input.kind == "proxy", input.fakeDns)
            val marker = projectedRules[index + 1].asJsonObject.getAsJsonObject(ROOT_APP_IDENTITY_DOMAIN_DNS_RULE_MARKER)
            assertEquals(input.ruleId, marker.get("ruleId").asLong)
            assertEquals(input.packages, marker.getAsJsonArray("packages").map { it.asString })
            assertEquals(input.uids, marker.getAsJsonArray("uids").map { it.asInt })
            assertEquals(input.domainText, marker.get("domainText").asString)
            assertEquals(input.kind, marker.get("kind").asString)
            assertEquals(input.fakeDns, marker.get("fakeDns").asBoolean)
        }
        assertEquals("legacy-before", projectedRules.first().asJsonObject.get("tag").asString)
        assertEquals("legacy-after", projectedRules.last().asJsonObject.get("tag").asString)
        assertEquals(fixture.getAsJsonObject("expectedConfig").getAsJsonObject("dns").getAsJsonArray("rules").size(), projectedRules.size())
    }

    @Test fun unsupportedPredicatesAndNonBooleanInvertStayOnAppPath() {
        val base = RuleEntity(id = 813, enabled = true, outbound = -1, packages = setOf("com.example.app"), domains = "domain:example.test")
        assertNotNull(rootAppIdentityDomainDNSRuleInput(base, "route", listOf(10013), false))
        assertNull(rootAppIdentityDomainDNSRuleInput(base.copy(domains = "regex:.*example.test"), "route", listOf(10013), false))
        assertNull(rootAppIdentityDomainDNSRuleInput(base.copy(ip = "192.0.2.1/32"), "route", listOf(10013), false))
        assertNull(rootAppIdentityDomainDNSRuleInput(base.copy(config = "{\"invert\":\"true\"}"), "route", listOf(10013), false))
        assertNull(rootAppIdentityDomainDNSRuleInput(base.copy(config = "{\"unsupported\":true}"), "route", listOf(10013), false))
        assertNull(rootAppIdentityDomainDNSRuleInput(base, "sniff", listOf(10013), false))
    }

    private fun rule(item: JsonObject) = RuleEntity(
        id = item.get("id").asLong, enabled = true, outbound = item.get("outbound").asLong,
        packages = item.getAsJsonArray("packages").map { it.asString }.toSet(), domains = item.get("domains").asString
    )

    private fun fixture(): JsonObject {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (true) {
            val candidate = File(directory, "rootmodule/testdata/root_dns_app_identity_domain_v80.json")
            if (candidate.isFile) return JsonParser.parseString(candidate.readText(Charsets.UTF_8)).asJsonObject
            directory = directory.parentFile ?: break
        }
        throw AssertionError("shared Root App-identity+domain DNS fixture not found")
    }
}
