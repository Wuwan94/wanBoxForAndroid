// @author 雾晚
package io.nekohasekai.sagernet

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.route.ROOT_DOMAIN_IP_VERSION_OUTBOUND_ROUTE_MARKER
import io.nekohasekai.sagernet.route.rootDomainIPVersionOutboundRouteInput
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RootDomainIPVersionOutboundRouteMigrationTest {
    @Test fun sharedFixtureProjectsDomainAndIPVersionConjunction() {
        val fixture = fixture()
        val input = rootDomainIPVersionOutboundRouteInput(rule(fixture.getAsJsonObject("rule")), fixture.get("outbound").asString)
        assertNotNull(input)
        assertEquals(67001L, input!!.ruleId)
        assertEquals("full:example.org, domain:example.net, keyword:cdn", input.domainText)
        assertEquals(6, input.ipVersion)
        assertTrue(input.invert)
        assertEquals("direct", input.outbound)
        val marker = JsonObject().apply {
            add(ROOT_DOMAIN_IP_VERSION_OUTBOUND_ROUTE_MARKER, fixture.getAsJsonObject("projectedRouteRule")
                .getAsJsonObject(ROOT_DOMAIN_IP_VERSION_OUTBOUND_ROUTE_MARKER).deepCopy())
        }
        assertEquals(fixture.getAsJsonObject("projectedRouteRule"), marker)
    }

    @Test fun unsupportedAndRicherRulesStayOnLegacyBuilderPath() {
        val base = rule(fixture().getAsJsonObject("rule"))
        assertNull(rootDomainIPVersionOutboundRouteInput(base.copy(config = "{\"ip_version\":5}"), "direct"))
        assertNull(rootDomainIPVersionOutboundRouteInput(base.copy(config = "{\"ip_version\":6,\"invert\":\"true\"}"), "direct"))
        assertNull(rootDomainIPVersionOutboundRouteInput(base.copy(config = "{\"ip_version\":6,\"domain_regex\":[\".*\"]}"), "direct"))
        assertNull(rootDomainIPVersionOutboundRouteInput(base.copy(domains = "regexp:.*example.*"), "direct"))
        assertNull(rootDomainIPVersionOutboundRouteInput(base.copy(ip = "192.0.2.0/24"), "direct"))
        assertNull(rootDomainIPVersionOutboundRouteInput(base.copy(port = "443:445"), "direct"))
        assertNull(rootDomainIPVersionOutboundRouteInput(base.copy(ruleset = "https://example.org/set.srs"), "direct"))
        assertNull(rootDomainIPVersionOutboundRouteInput(base, "block"))
    }

    private fun rule(json: JsonObject): RuleEntity {
        fun text(name: String) = json.get(name)?.takeUnless { it.isJsonNull }?.asString ?: ""
        fun number(name: String) = json.get(name)?.takeUnless { it.isJsonNull }?.asLong ?: 0L
        return RuleEntity(id = number("id"), enabled = json.get("enabled")?.asBoolean ?: false,
            config = text("config"), domains = text("domains"), ip = text("ip"), port = text("port"),
            sourcePort = text("sourcePort"), network = text("network"), source = text("source"),
            protocol = text("protocol"), ruleset = text("ruleset"), outbound = number("outbound"), packages = emptySet())
    }

    private fun fixture(): JsonObject {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (true) {
            val candidate = File(directory, "rootmodule/testdata/root_domain_ip_version_outbound_v67.json")
            if (candidate.isFile) return JsonParser.parseString(candidate.readText(Charsets.UTF_8)).asJsonObject
            directory = directory.parentFile ?: break
        }
        throw AssertionError("shared domain/IP-version outbound fixture not found")
    }
}
