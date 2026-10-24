// @author 雾晚
package io.nekohasekai.sagernet

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.bg.hasRootExactDomainOutboundRouteMarker
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.route.rootExactDomainOutboundRouteInput
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RootExactDomainOutboundRouteMigrationTest {
    @Test fun sharedFixtureDefinesTypedInputAndRootOutputSlot() {
        val f = fixture()
        val rule = rule(f.getAsJsonObject("eligibleRule"))
        val input = rootExactDomainOutboundRouteInput(rule, f.get("outbound").asString)
        assertNotNull(input)
        assertEquals(501L, input!!.ruleId)
        assertEquals(f.get("domainText").asString, input.domainText)
        assertEquals(f.get("outbound").asString, input.outbound)
        assertTrue(hasRootExactDomainOutboundRouteMarker(f.getAsJsonObject("inputConfig").deepCopy()))
    }

    @Test fun unsupportedMatcherOrAdditionalPredicateStaysLegacy() {
        val f = fixture()
        val base = rule(f.getAsJsonObject("eligibleRule"))
        assertNull(rootExactDomainOutboundRouteInput(base.copy(domains = "domain:example.com"), "proxy-main"))
        assertNull(rootExactDomainOutboundRouteInput(base.copy(config = "{\"invert\":true}"), "proxy-main"))
        assertNull(rootExactDomainOutboundRouteInput(base, ""))
        assertNull(rootExactDomainOutboundRouteInput(base, "block"))
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
            val candidate = File(directory, "rootmodule/testdata/root_exact_domain_outbound_v50.json")
            if (candidate.isFile) return JsonParser.parseString(candidate.readText(Charsets.UTF_8)).asJsonObject
            directory = directory.parentFile ?: break
        }
        throw AssertionError("shared exact-domain outbound fixture not found")
    }
}
