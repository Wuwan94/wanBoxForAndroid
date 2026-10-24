// @author 雾晚
package io.nekohasekai.sagernet

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.route.ROOT_APP_UID_RULESET_OUTBOUND_MARKER
import io.nekohasekai.sagernet.route.ROOT_APP_UID_DOMAIN_RULESET_OUTBOUND_MARKER
import io.nekohasekai.sagernet.route.rootAppUIDDomainRuleSetOutboundInput
import io.nekohasekai.sagernet.route.projectRootAppUIDDomainRuleSetOutboundRules
import io.nekohasekai.sagernet.route.projectRootAppUIDRuleSetOutboundRules
import io.nekohasekai.sagernet.route.rootAppUIDRuleSetOutboundInput
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RootAppUIDRuleSetOutboundMigrationTest {
    @Test fun sharedFixtureProjectsPackageUidAndRuleSetOutboundInPlace() {
        val f = fixture()
        val rule = rule(f.getAsJsonObject("eligibleRule"))
        val tags = f.getAsJsonArray("ruleSetTags").mapIndexed { i, value ->
            value.asString to f.getAsJsonArray("eligibleRuleSetTypes")[i].asBoolean
        }
        val input = rootAppUIDRuleSetOutboundInput(rule, "direct", f.getAsJsonArray("uids").map { it.asInt }, tags)
        assertNotNull(input)
        assertEquals(831L, input!!.ruleId)
        assertEquals(listOf("com.example.alpha", "com.example.beta"), input.packages)
        assertEquals(listOf(10001, 20001), input.uids)
        assertEquals(f.getAsJsonArray("ruleSetTags").map { it.asString }, input.ruleSetTags)
        assertTrue(input.invert)

        val projected = JsonObject().apply {
            add("route", JsonObject().apply { add("rules", com.google.gson.JsonArray().apply {
                add(JsonObject().apply { addProperty("outbound", "direct") })
                val delayedMarker = f.getAsJsonObject("projectedRouteRule").deepCopy()
                delayedMarker.getAsJsonObject(ROOT_APP_UID_RULESET_OUTBOUND_MARKER).addProperty("index", 2)
                add(delayedMarker)
            }) })
        }
        val actual = projectRootAppUIDRuleSetOutboundRules(projected)
        assertNotNull(actual)
        val marker = actual!!.getAsJsonArray("rules")[0].asJsonObject
        assertEquals(1, marker.get("index").asInt)
        assertEquals(ROOT_APP_UID_RULESET_OUTBOUND_MARKER,
            f.getAsJsonObject("projectedRouteRule").keySet().single())
    }

    @Test fun mixedSetKindsAndUnsupportedPredicatesStayOnAppPath() {
        val f = fixture()
        val rule = rule(f.getAsJsonObject("eligibleRule"))
        val uids = f.getAsJsonArray("uids").map { it.asInt }
        val tags = f.getAsJsonArray("ruleSetTags").map { it.asString to false }
        assertNull(rootAppUIDRuleSetOutboundInput(rule, "direct", uids, listOf(tags[0], tags[1].first to true)))
        assertNull(rootAppUIDRuleSetOutboundInput(rule.copy(domains = "regex:.*example.test"), "direct", uids, tags))
        assertNull(rootAppUIDRuleSetOutboundInput(rule.copy(ip = "192.0.2.0/24"), "direct", uids, tags))
        assertNull(rootAppUIDRuleSetOutboundInput(rule.copy(port = "443"), "direct", uids, tags))
        assertNull(rootAppUIDRuleSetOutboundInput(rule.copy(config = "{\"unsupported\":true}"), "direct", uids, tags))
    }

    @Test fun explicitDomainAndOnlyRssiteSetsMigrateButMixedAndRegexStayLegacy() {
        val f = fixture()
        val original = rule(f.getAsJsonObject("eligibleRule"))
        val uids = f.getAsJsonArray("uids").map { it.asInt }
        val tags = f.getAsJsonArray("ruleSetTags").map { it.asString to false }
        val eligible = original.copy(domains = "full:api.example.test,domain:example.test")
        val input = rootAppUIDDomainRuleSetOutboundInput(eligible, "direct", uids, tags)
        assertNotNull(input)
        assertEquals("full:api.example.test,domain:example.test", input!!.domainText)
        assertEquals(listOf("domain", "domain"), input.ruleSetKinds)
        assertNull(rootAppUIDDomainRuleSetOutboundInput(eligible, "direct", uids, listOf(tags[0], tags[1].first to true)))
        assertNull(rootAppUIDDomainRuleSetOutboundInput(eligible.copy(domains = "regex:.*example.test"), "direct", uids, tags))
        assertNull(rootAppUIDDomainRuleSetOutboundInput(eligible.copy(ip = "192.0.2.0/24"), "direct", uids, tags))

        val marker = JsonObject().apply {
            addProperty("index", 2); addProperty("ruleId", input.ruleId)
            add("packages", com.google.gson.Gson().toJsonTree(input.packages))
            add("uids", com.google.gson.Gson().toJsonTree(input.uids))
            addProperty("domainText", input.domainText)
            add("ruleSetTags", com.google.gson.Gson().toJsonTree(input.ruleSetTags))
            add("ruleSetKinds", com.google.gson.Gson().toJsonTree(input.ruleSetKinds))
            addProperty("outbound", input.outbound); addProperty("invert", input.invert)
        }
        val projected = JsonObject().apply { add("route", JsonObject().apply { add("rules", com.google.gson.JsonArray().apply {
            add(JsonObject().apply { addProperty("outbound", "direct") })
            add(JsonObject().apply { addProperty("outbound", "proxy") })
            add(JsonObject().apply { add(ROOT_APP_UID_DOMAIN_RULESET_OUTBOUND_MARKER, marker) })
        }) }) }
        val routeSpec = projectRootAppUIDDomainRuleSetOutboundRules(projected)!!
        val projectedMarker = routeSpec.getAsJsonArray("rules")[0].asJsonObject
        assertEquals(2, projectedMarker.get("index").asInt)
        assertEquals(listOf("domain", "domain"), projectedMarker.getAsJsonArray("ruleSetKinds").map { it.asString })
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
            val candidate = File(directory, "rootmodule/testdata/root_route_app_uid_ruleset_outbound_v83.json")
            if (candidate.isFile) return JsonParser.parseString(candidate.readText(Charsets.UTF_8)).asJsonObject
            directory = directory.parentFile
        }
        throw AssertionError("shared package+ruleset route fixture not found")
    }
}
