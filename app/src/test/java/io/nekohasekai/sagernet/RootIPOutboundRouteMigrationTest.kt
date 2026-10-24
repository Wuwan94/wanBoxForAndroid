package io.nekohasekai.sagernet

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.route.rootIPOutboundRouteInput
import io.nekohasekai.sagernet.route.parseRootIPOutboundCidrs
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RootIPOutboundRouteMigrationTest {
    @Test fun sharedFixtureMatchesTypedInputAndInvertMode() {
        val fixture = fixture()
        val rule = rule(fixture.getAsJsonObject("rule"))
        val input = rootIPOutboundRouteInput(rule, fixture.get("outbound").asString)
        assertNotNull(input)
        assertEquals(552L, input!!.ruleId)
        assertEquals(rule.ip, input.ipText)
        assertEquals(fixture.get("outbound").asString, input.outbound)
        assertFalse(input.invert)
        val ipv6Rule = rule(fixture.getAsJsonObject("ipv6Rule"))
        val ipv6 = rootIPOutboundRouteInput(ipv6Rule, input.outbound)
        assertNotNull(ipv6)
        assertEquals(ipv6Rule.ip, ipv6!!.ipText)
        val inverted = rootIPOutboundRouteInput(rule.copy(config = "{\"invert\":true}"), input.outbound)
        assertNotNull(inverted)
        assertTrue(inverted!!.invert)
    }

    @Test fun unsupportedInputsStayOnLegacyBuilderPath() {
        val rule = rule(fixture().getAsJsonObject("rule"))
        assertNull(rootIPOutboundRouteInput(rule.copy(domains = "full:example.com"), "proxy-main"))
        assertNull(rootIPOutboundRouteInput(rule.copy(port = "443"), "proxy-main"))
        assertNull(rootIPOutboundRouteInput(rule.copy(config = "{\"domain_regex\":[\".*\"]}"), "proxy-main"))
        assertNull(rootIPOutboundRouteInput(rule.copy(ip = "geoip:private"), "proxy-main"))
        assertNull(rootIPOutboundRouteInput(rule, "block"))
    }

    @Test fun parserAcceptsOnlyCanonicalIPv4Prefixes() {
        assertEquals(listOf("192.0.2.0/24", "198.51.100.7/32"), parseRootIPOutboundCidrs("192.0.2.0/24,198.51.100.7/32"))
        assertNull(parseRootIPOutboundCidrs("192.0.2.1"))
        assertNull(parseRootIPOutboundCidrs("192.000.2.0/24"))
        assertEquals(listOf("2001:db8::/32", "2001:db8:1::7/128"), parseRootIPOutboundCidrs("2001:db8::/32,2001:db8:1::7/128"))
        assertNull(parseRootIPOutboundCidrs("2001:db8::/129"))
        assertNull(parseRootIPOutboundCidrs("fe80::1%wlan0/64"))
        assertNull(parseRootIPOutboundCidrs("not-an-ipv6::/64"))
    }

    private fun rule(json: JsonObject): RuleEntity {
        fun text(name: String) = json.get(name)?.takeUnless { it.isJsonNull }?.asString ?: ""
        fun number(name: String, default: Long = 0L) = json.get(name)?.takeUnless { it.isJsonNull }?.asLong ?: default
        val packages = json.get("packages")?.takeUnless { it.isJsonNull }?.asJsonArray?.map { it.asString }?.toSet() ?: emptySet()
        return RuleEntity(id=number("id"), enabled=json.get("enabled")?.asBoolean ?: false, config=text("config"),
            domains=text("domains"), ip=text("ip"), port=text("port"), sourcePort=text("sourcePort"), network=text("network"),
            source=text("source"), protocol=text("protocol"), ruleset=text("ruleset"), outbound=number("outbound"), packages=packages)
    }

    private fun fixture(): JsonObject {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (true) {
            val candidate = File(directory, "rootmodule/testdata/root_ip_outbound_v52.json")
            if (candidate.isFile) return JsonParser.parseString(candidate.readText(Charsets.UTF_8)).asJsonObject
            directory = directory.parentFile ?: break
        }
        throw AssertionError("shared IP outbound fixture not found")
    }
}
