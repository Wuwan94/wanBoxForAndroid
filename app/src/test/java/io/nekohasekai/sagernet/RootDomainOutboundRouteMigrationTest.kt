// @author 雾晚
package io.nekohasekai.sagernet

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.route.rootDomainOutboundRouteInput
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RootDomainOutboundRouteMigrationTest {
    @Test fun fixtureMatchesConfigBuilderDomainRouteAndInvertSemantics() {
        val f = fixture()
        val rule = rule(f.getAsJsonObject("rule"))
        val input = rootDomainOutboundRouteInput(rule, f.get("outbound").asString)
        assertNotNull(input)
        assertEquals(55001L, input!!.ruleId)
        assertEquals("domain:Example.com,keyword:Tracker", input.domainText)
        assertEquals("direct", input.outbound)
        assertTrue(input.invert)
        assertEquals(f.getAsJsonObject("projectedRouteRule").getAsJsonObject("_wanbox_root_domain_outbound_v55").get("domainText").asString, input.domainText)
    }

    @Test fun regexGeositeAndRicherPredicatesStayOnLegacyPath() {
        val base = rule(fixture().getAsJsonObject("rule"))
        assertNull(rootDomainOutboundRouteInput(base.copy(domains = "regexp:.*example.*"), "direct"))
        assertNull(rootDomainOutboundRouteInput(base.copy(domains = "geosite:category-ads-all"), "direct"))
        assertNull(rootDomainOutboundRouteInput(base.copy(ip = "192.0.2.0/24"), "direct"))
        assertNull(rootDomainOutboundRouteInput(base.copy(config = "{\"domain_regex\":[\".*\"]}"), "direct"))
        assertNull(rootDomainOutboundRouteInput(base, "block"))
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
            val candidate = File(directory, "rootmodule/testdata/root_domain_outbound_v55.json")
            if (candidate.isFile) return JsonParser.parseString(candidate.readText(Charsets.UTF_8)).asJsonObject
            directory = directory.parentFile ?: break
        }
        throw AssertionError("shared domain outbound fixture not found")
    }
}
