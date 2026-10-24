// @author 雾晚
package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.route.ROOT_EXACT_DOMAIN_DNS_RULE_MARKER
import io.nekohasekai.sagernet.route.rootExactDomainDNSRuleInput
import io.nekohasekai.sagernet.route.parseRootDNSDomainMatchers
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RootExactDomainDNSRuleMigrationTest {
    @Test fun sharedFixtureMatchesBuilderInputsAndRootEffects() {
        val fixture = fixture()
        val rules = fixture.getAsJsonArray("eligibleRules")
        assertEquals(5, rules.size())
        rules.forEach { element ->
            val item = element.asJsonObject
            val input = rootExactDomainDNSRuleInput(
                rule(item), item.get("action").asString, item.get("fakeDns").asBoolean
            )
            assertNotNull(input)
            assertEquals(item.get("id").asLong, input!!.ruleId)
            assertEquals(item.get("domains").asString, input.domainText)
            assertEquals(item.get("kind").asString, input.kind)
            assertEquals(item.get("fakeDns").asBoolean, input.fakeDns)
        }
        val dnsRules = fixture.getAsJsonObject("config").getAsJsonObject("dns").getAsJsonArray("rules")
        val expected = fixture.getAsJsonObject("expected").getAsJsonObject("dns").getAsJsonArray("rules")
        assertEquals(expected.size(), dnsRules.size())
        assertEquals("legacy-before.example", expected[0].asJsonObject.getAsJsonArray("domain_suffix")[0].asString)
        assertEquals("legacy-after.example", expected[6].asJsonObject.getAsJsonArray("domain_suffix")[0].asString)
    }
    @Test fun directRejectAndFakeDNSEffectsHaveTypedInputs() {
        val directInput = rootExactDomainDNSRuleInput(rule(-1L), "route", false)
        assertNotNull(directInput)
        assertEquals("direct", directInput!!.kind)
        assertFalse(directInput.fakeDns)

        val rejectInput = rootExactDomainDNSRuleInput(rule(-2L), "reject", true)
        assertNotNull(rejectInput)
        assertEquals("reject", rejectInput!!.kind)
        assertFalse(rejectInput.fakeDns)

        val fakeInput = rootExactDomainDNSRuleInput(rule(42L), "route", true)
        assertNotNull(fakeInput)
        assertEquals("proxy", fakeInput!!.kind)
        assertTrue(fakeInput.fakeDns)
        assertEquals("full:one.example, full:two.example", fakeInput.domainText)
        assertTrue(ROOT_EXACT_DOMAIN_DNS_RULE_MARKER.endsWith("v53"))
    }

    @Test fun unsupportedMatchersAndAdditionalPredicatesStayLegacy() {
        val base = rule(42L)
        assertNotNull(rootExactDomainDNSRuleInput(base.copy(domains = "domain:example.com"), "route", false))
        assertNotNull(rootExactDomainDNSRuleInput(base.copy(domains = "keyword:video"), "route", false))
        assertNotNull(rootExactDomainDNSRuleInput(base.copy(domains = "example.com"), "route", false))
        assertNull(rootExactDomainDNSRuleInput(base.copy(domains = "regexp:^example$"), "route", false))
        assertNull(rootExactDomainDNSRuleInput(base.copy(domains = "geosite:private"), "route", false))
        val compositeDNS = rootExactDomainDNSRuleInput(base.copy(ip = "192.0.2.1/32"), "route", true)
        assertNotNull(compositeDNS)
        assertTrue(compositeDNS!!.fakeDns)
        val domainWithRuleset = rootExactDomainDNSRuleInput(
            base.copy(ruleset = "https://example.test/rules"), "route", false
        )
        assertNotNull("domain DNS effect is independent of the later ruleset DNS effect", domainWithRuleset)
        // DNS selection uses only domain criteria; route inversion applies to the separate route matcher.
        assertNotNull(rootExactDomainDNSRuleInput(base.copy(config = "{\"invert\":true}"), "route", false))
        assertNull(rootExactDomainDNSRuleInput(base, "sniff", false))
    }

    private fun rule(item: JsonObject): RuleEntity = RuleEntity(
        id = item.get("id").asLong, enabled = true, config = "", domains = item.get("domains").asString,
        ip = "", port = "", sourcePort = "", network = "", source = "", protocol = "", ruleset = "",
        outbound = item.get("outbound").asLong, packages = emptySet()
    )

    private fun fixture(): JsonObject {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (true) {
            val candidate = File(directory, "rootmodule/testdata/root_dns_user_rule.json")
            if (candidate.isFile) return JsonParser.parseString(candidate.readText(Charsets.UTF_8)).asJsonObject
            directory = directory.parentFile ?: break
        }
        throw AssertionError("shared Root DNS user-rule fixture not found")
    }

    private fun rule(outbound: Long) = RuleEntity(
        id = 503L, enabled = true, config = "", domains = "full:one.example, full:two.example",
        ip = "", port = "", sourcePort = "", network = "", source = "", protocol = "", ruleset = "",
        outbound = outbound, packages = emptySet()
    )

    @Test
    fun `domain DNS input remains eligible beside independent remote ruleset effects`() {
        val fixture = readFixture("root_dns_domain_remote_ruleset_composition_v78.json")
        val item = fixture.getAsJsonObject("appRule")
        val input = rootExactDomainDNSRuleInput(
            RuleEntity(
                id = item.get("id").asLong, enabled = true, config = "",
                domains = item.get("domains").asString, ip = "", port = "", sourcePort = "",
                network = "", source = "", protocol = "", ruleset = item.get("ruleset").asString,
                outbound = item.get("outbound").asLong, packages = emptySet()
            ),
            item.get("action").asString,
            item.get("fakeDns").asBoolean
        )
        assertNotNull(input)
        assertEquals(item.get("id").asLong, input!!.ruleId)
        assertEquals(item.get("dnsDomainKind").asString, input.kind)
        val projected = fixture.getAsJsonObject("projectedConfig")
            .getAsJsonObject("dns").getAsJsonArray("rules")
        assertTrue(projected[1].asJsonObject.has(ROOT_EXACT_DOMAIN_DNS_RULE_MARKER))
        assertTrue(projected[2].asJsonObject.has("_wanbox_root_remote_ruleset_dns_effect_v56"))
        assertEquals("legacy-after", projected[3].asJsonObject.get("tag").asString)
    }

    private fun readFixture(name: String): JsonObject {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (true) {
            val candidate = File(directory, "rootmodule/testdata/$name")
            if (candidate.isFile) return JsonParser.parseString(candidate.readText(Charsets.UTF_8)).asJsonObject
            directory = directory.parentFile ?: break
        }
        throw AssertionError("shared Root DNS fixture not found: $name")
    }

    @Test
    fun `root dns matcher accepts suffix and keyword but retains unsupported legacy`() {
        assertTrue(parseRootDNSDomainMatchers("domain:Sub.Example, bare.example, keyword:video"))
        assertFalse(parseRootDNSDomainMatchers("regexp:^bad$"))
        assertFalse(parseRootDNSDomainMatchers("geosite:private"))
        assertFalse(parseRootDNSDomainMatchers("domain:bad domain"))
    }
}
