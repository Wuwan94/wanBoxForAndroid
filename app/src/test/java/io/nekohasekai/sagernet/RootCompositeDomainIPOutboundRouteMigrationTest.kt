package io.nekohasekai.sagernet

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.route.rootCompositeDomainIPOutboundRouteInput
import io.nekohasekai.sagernet.bg.ROOT_COMPOSITE_DOMAIN_IP_OUTBOUND_MARKER
import io.nekohasekai.sagernet.route.rootExactDomainDNSRuleInput
import io.nekohasekai.sagernet.route.ROOT_EXACT_DOMAIN_DNS_RULE_MARKER
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RootCompositeDomainIPOutboundRouteMigrationTest {
    @Test fun compositeRouteAndDnsEffectsShareOneOrderedFixture() {
        val fixture = combinedFixture()
        val ruleJson = fixture.getAsJsonObject("rule")
        val rule = rule(ruleJson)
        val route = rootCompositeDomainIPOutboundRouteInput(rule, fixture.get("outbound").asString)
        assertNotNull(route)
        assertEquals(rule.id, route!!.ruleId)
        assertEquals(rule.domains, route.domainText)
        assertEquals(rule.ip, route.ipText)
        assertEquals(fixture.get("outbound").asString, route.outbound)
        assertTrue(route.invert)

        val dns = rootExactDomainDNSRuleInput(rule, "route", fixture.get("fakeDns").asBoolean)
        assertNotNull(dns)
        assertEquals(route.ruleId, dns!!.ruleId)
        assertEquals(route.domainText, dns.domainText)
        assertEquals("proxy", dns.kind)
        assertTrue(dns.fakeDns)

        // Route inversion remains a whole-group NOT; DNS selection still uses only the
        // domain half of the combined domain/IP rule, without inheriting route inversion.
        val invertedRule = rule
        val invertedDns = rootExactDomainDNSRuleInput(invertedRule, "route", true)
        assertNotNull(invertedDns)
        assertEquals(dns.domainText, invertedDns!!.domainText)
        assertEquals(dns.kind, invertedDns.kind)
        assertTrue(invertedDns.fakeDns)

        val input = fixture.getAsJsonObject("inputConfig")
        val routeMarker = input.getAsJsonObject("route").getAsJsonArray("rules")[1].asJsonObject
        assertTrue(routeMarker.has(ROOT_COMPOSITE_DOMAIN_IP_OUTBOUND_MARKER))
        val dnsMarker = input.getAsJsonObject("dns").getAsJsonArray("rules")[1].asJsonObject
        assertTrue(dnsMarker.has(ROOT_EXACT_DOMAIN_DNS_RULE_MARKER))
        assertEquals(3, fixture.getAsJsonObject("expectedConfig").getAsJsonObject("route").getAsJsonArray("rules").size())
        assertEquals(3, fixture.getAsJsonObject("expectedConfig").getAsJsonObject("dns").getAsJsonArray("rules").size())
    }
    @Test fun sharedFixtureMatchesTypedRootInputAndInvertMode() {
        val fixture = fixture()
        val rule = rule(fixture.getAsJsonObject("rule"))
        val input = rootCompositeDomainIPOutboundRouteInput(rule, fixture.get("outbound").asString)
        assertNotNull(input)
        assertEquals(551L, input!!.ruleId)
        assertEquals(rule.domains, input.domainText)
        assertEquals(rule.ip, input.ipText)
        assertEquals(fixture.get("outbound").asString, input.outbound)
        assertFalse(input.invert)
        val inverted = rootCompositeDomainIPOutboundRouteInput(rule.copy(config = "{\"invert\":true}"), input.outbound)
        assertNotNull(inverted)
        assertTrue(inverted!!.invert)
    }

    @Test fun unsupportedInputsStayOnLegacyBuilderPath() {
        val rule = rule(fixture().getAsJsonObject("rule"))
        assertNotNull(rootCompositeDomainIPOutboundRouteInput(rule, "proxy-main"))
        assertNull(rootCompositeDomainIPOutboundRouteInput(rule.copy(config = "{\"domain_regex\":[\".*\"]}"), "proxy-main"))
        val suffixAndKeyword = rootCompositeDomainIPOutboundRouteInput(
            rule.copy(domains = "domain:example.com, keyword:needle"), "proxy-main")
        assertNotNull(suffixAndKeyword)
        assertNull(rootCompositeDomainIPOutboundRouteInput(rule.copy(domains = "regexp:^example\\.com$"), "proxy-main"))
        assertNull(rootCompositeDomainIPOutboundRouteInput(rule.copy(domains = "geosite:private"), "proxy-main"))
        assertNull(rootCompositeDomainIPOutboundRouteInput(rule.copy(ip = "geoip:private"), "proxy-main"))
        val ipv6 = rootCompositeDomainIPOutboundRouteInput(rule.copy(ip = "2001:db8::/32"), "proxy-main")
        assertNotNull(ipv6)
        assertEquals("2001:db8::/32", ipv6!!.ipText)
        assertNull(rootCompositeDomainIPOutboundRouteInput(rule.copy(ip = "2001:db8::/129"), "proxy-main"))
        assertNull(rootCompositeDomainIPOutboundRouteInput(rule.copy(ip = "not-a-cidr/32"), "proxy-main"))
    }

    private fun rule(json: JsonObject): RuleEntity {
        fun text(name: String) = json.get(name)?.takeUnless { it.isJsonNull }?.asString ?: ""
        fun number(name: String, default: Long = 0L) = json.get(name)?.takeUnless { it.isJsonNull }?.asLong ?: default
        val packages = json.get("packages")?.takeUnless { it.isJsonNull }?.asJsonArray?.map { it.asString }?.toSet() ?: emptySet()
        return RuleEntity(id=number("id"), enabled=json.get("enabled")?.asBoolean ?: false, config=text("config"),
            domains=text("domains"), ip=text("ip"), port=text("port"), sourcePort=text("sourcePort"), network=text("network"),
            source=text("source"), protocol=text("protocol"), ruleset=text("ruleset"), outbound=number("outbound"), packages=packages)
    }

    private fun combinedFixture(): JsonObject {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (true) {
            val candidate = File(directory, "rootmodule/testdata/root_composite_domain_ip_dns.json")
            if (candidate.isFile) return JsonParser.parseString(candidate.readText(Charsets.UTF_8)).asJsonObject
            directory = directory.parentFile ?: break
        }
        throw AssertionError("shared composite route/DNS fixture not found")
    }

    private fun fixture(): JsonObject {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (true) {
            val candidate = File(directory, "rootmodule/testdata/root_composite_domain_ip_outbound_v51.json")
            if (candidate.isFile) return JsonParser.parseString(candidate.readText(Charsets.UTF_8)).asJsonObject
            directory = directory.parentFile ?: break
        }
        throw AssertionError("shared composite domain/IP fixture not found")
    }
}
