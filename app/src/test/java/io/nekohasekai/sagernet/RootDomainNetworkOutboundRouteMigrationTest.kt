// @author 雾晚
package io.nekohasekai.sagernet

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.route.rootDomainNetworkOutboundRouteInput
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RootDomainNetworkOutboundRouteMigrationTest {
    @Test fun fixtureCapturesDomainAndNetworkConjunctionAndInvert() {
        val f = fixture()
        val input = rootDomainNetworkOutboundRouteInput(rule(f.getAsJsonObject("rule")), f.get("outbound").asString)
        assertNotNull(input)
        assertEquals(58001L, input!!.ruleId)
        assertEquals("full:example.com,domain:Example.org,keyword:cdn", input.domainText)
        assertEquals("tcp,udp", input.networkText)
        assertEquals("direct", input.outbound)
        assertTrue(input.invert)
    }

    @Test fun richerOrUnprovenPredicatesStayOnLegacyPath() {
        val base = rule(fixture().getAsJsonObject("rule"))
        assertNull(rootDomainNetworkOutboundRouteInput(base.copy(ip = "192.0.2.0/24"), "direct"))
        assertNull(rootDomainNetworkOutboundRouteInput(base.copy(ruleset = "https://rules.example/set.srs"), "direct"))
        assertNull(rootDomainNetworkOutboundRouteInput(base.copy(port = "443"), "direct"))
        assertNull(rootDomainNetworkOutboundRouteInput(base.copy(source = "192.0.2.1/32"), "direct"))
        assertNull(rootDomainNetworkOutboundRouteInput(base.copy(protocol = "quic"), "direct"))
        assertNull(rootDomainNetworkOutboundRouteInput(base.copy(packages = setOf("example.app")), "direct"))
        assertNull(rootDomainNetworkOutboundRouteInput(base.copy(domains = "regexp:.*example.*"), "direct"))
        assertNull(rootDomainNetworkOutboundRouteInput(base.copy(config = "{\"action\":\"sniff\"}"), "direct"))
        assertNull(rootDomainNetworkOutboundRouteInput(base, "block"))
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
            val candidate = File(directory, "rootmodule/testdata/root_domain_network_outbound_v58.json")
            if (candidate.isFile) return JsonParser.parseString(candidate.readText(Charsets.UTF_8)).asJsonObject
            directory = directory.parentFile ?: break
        }
        throw AssertionError("shared domain/network outbound fixture not found")
    }
}
