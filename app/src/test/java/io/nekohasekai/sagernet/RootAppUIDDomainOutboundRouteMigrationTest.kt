// @author 雾晚
package io.nekohasekai.sagernet

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.route.ROOT_APP_UID_DOMAIN_OUTBOUND_ROUTE_MARKER
import io.nekohasekai.sagernet.route.projectRootAppUIDDomainOutboundRules
import io.nekohasekai.sagernet.route.rootAppUIDDomainOutboundRouteInput
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RootAppUIDDomainOutboundRouteMigrationTest {
    @Test fun sharedFixtureProjectsConjunctivePackageUidDomainOutboundWithInvert() {
        val f = fixture()
        val item = f.getAsJsonObject("eligibleRule")
        val input = rootAppUIDDomainOutboundRouteInput(rule(item), f.get("outboundTag").asString, item.getAsJsonArray("uids").map { it.asInt })
        assertNotNull(input)
        assertEquals(811L, input!!.ruleId)
        assertEquals(item.getAsJsonArray("packages").map { it.asString }.sorted(), input.packages)
        assertEquals(item.getAsJsonArray("uids").map { it.asInt }.sorted(), input.uids)
        assertEquals(item.get("domains").asString, input.domainText)
        assertTrue(input.invert)

        val projected = JsonObject().apply {
            add("route", JsonObject().apply { add("rules", com.google.gson.JsonArray().apply {
                add(JsonObject().apply { addProperty("outbound", "direct") })
                add(f.getAsJsonObject("projectedRouteRule").deepCopy())
            }) })
        }
        val actual = projectRootAppUIDDomainOutboundRules(projected)
        assertNotNull(actual)
        val expected = f.getAsJsonObject("spec").deepCopy()
        expected.getAsJsonArray("rules")[0].asJsonObject.addProperty("index", 1)
        assertEquals(expected, actual)
        assertEquals(ROOT_APP_UID_DOMAIN_OUTBOUND_ROUTE_MARKER,
            f.getAsJsonObject("projectedRouteRule").keySet().single())
    }

    @Test fun unsupportedPredicatesRemainOnAppPath() {
        val base = rule(fixture().getAsJsonObject("eligibleRule"))
        assertNull(rootAppUIDDomainOutboundRouteInput(base.copy(domains = "regex:.*example.test"), "direct", listOf(10001)))
        assertNull(rootAppUIDDomainOutboundRouteInput(base.copy(ip = "192.0.2.0/24"), "direct", listOf(10001)))
        assertNull(rootAppUIDDomainOutboundRouteInput(base.copy(ruleset = "geoip-test"), "direct", listOf(10001)))
        assertNull(rootAppUIDDomainOutboundRouteInput(base.copy(port = "443"), "direct", listOf(10001)))
        assertNull(rootAppUIDDomainOutboundRouteInput(base.copy(config = "{\"unsupported\":true}"), "direct", listOf(10001)))
        assertNull(rootAppUIDDomainOutboundRouteInput(base, "block", listOf(10001)))
    }

    private fun rule(item: JsonObject) = RuleEntity(
        id = item.get("id").asLong, enabled = item.get("enabled").asBoolean,
        outbound = item.get("outbound").asLong, packages = item.getAsJsonArray("packages").map { it.asString }.toSet(),
        domains = item.get("domains").asString, ip = item.get("ip").asString,
        ruleset = item.get("ruleset").asString, port = item.get("port").asString,
        sourcePort = item.get("sourcePort").asString, network = item.get("network").asString,
        source = item.get("source").asString, protocol = item.get("protocol").asString,
        config = item.get("config").asString
    )

    private fun fixture(): JsonObject {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
        while (directory != null) {
            val candidate = File(directory, "rootmodule/testdata/root_route_app_uid_domain_outbound_v81.json")
            if (candidate.isFile) return JsonParser.parseString(candidate.readText(Charsets.UTF_8)).asJsonObject
            directory = directory.parentFile
        }
        throw AssertionError("shared package+domain route fixture not found")
    }
}
