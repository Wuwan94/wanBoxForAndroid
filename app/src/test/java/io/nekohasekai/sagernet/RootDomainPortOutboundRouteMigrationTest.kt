// @author 雾晚
package io.nekohasekai.sagernet

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.route.rootDomainPortOutboundRouteInput
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RootDomainPortOutboundRouteMigrationTest {
    @Test fun sharedFixtureCapturesDomainAndPortConjunction() {
        val f = fixture()
        val input = rootDomainPortOutboundRouteInput(rule(f.getAsJsonObject("rule")), f.get("outbound").asString)
        assertNotNull(input)
        assertEquals(59001L, input!!.ruleId)
        assertEquals("full:example.com, domain:corp.example, keyword:chat", input.domainText)
        assertEquals("443, 8443\n65535", input.portText)
        assertEquals("direct", input.outbound)
        assertTrue(input.invert)
    }

    @Test fun unsupportedMatchersPredicatesAndPortRangesRemainLegacy() {
        val base = rule(fixture().getAsJsonObject("rule"))
        assertNull(rootDomainPortOutboundRouteInput(base.copy(domains = "regexp:.*example.*"), "direct"))
        assertNull(rootDomainPortOutboundRouteInput(base.copy(domains = "geosite:cn"), "direct"))
        assertNull(rootDomainPortOutboundRouteInput(base.copy(port = "80:90"), "direct"))
        assertNull(rootDomainPortOutboundRouteInput(base.copy(ip = "192.0.2.0/24"), "direct"))
        assertNull(rootDomainPortOutboundRouteInput(base.copy(network = "tcp"), "direct"))
        assertNull(rootDomainPortOutboundRouteInput(base, "block"))
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
            val candidate = File(directory, "rootmodule/testdata/root_domain_port_outbound_v59.json")
            if (candidate.isFile) return JsonParser.parseString(candidate.readText(Charsets.UTF_8)).asJsonObject
            directory = directory.parentFile ?: break
        }
        throw AssertionError("shared domain/port outbound fixture not found")
    }
}
