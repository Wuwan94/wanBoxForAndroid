// @author 雾晚
package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.bg.RootModuleClient
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException

/** Protocol + Android integration regression checks; not Magisk device tests. @author 雾晚 */
class RootModuleContractTest {
    @Test fun managerDoesNotShipUnusedStandaloneCoreAndModuleStillPackagesIt() {
        val build = File("build.gradle.kts").readText()
        assertTrue(build.contains("excludes += \"**/librootbox.so\""))
        assertTrue(build.contains("useLegacyPackaging = true"))
        val client = source("bg/RootModuleClient.kt")
        assertFalse(client.contains("librootbox.so"))
        assertTrue(File("../rootmodule/pack.py").readText().contains("files['bin/rootbox'], files['bin/wanboxctl'] = core, cli"))
    }
    private fun source(path: String) = File("src/main/java/io/nekohasekai/sagernet/$path").readText()

    @Test fun domainRegexSemanticParityGateKeepsProductionProjectionDisabled() {
        val client = source("bg/RootModuleClient.kt")
        assertFalse(client.contains("projectRootRouteDomainRegexRejectRules(config.asJsonObject)"))
        assertFalse(client.contains("rootRouteDomainRegexRejectRules?.let"))
        assertTrue(client.contains("Keep domain_regex rules in the App-generated legacy config until full matcher/action"))
    }

    @Test fun fullV42IntegrationSnapshotMatchesKotlinProjections() {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        val fixtureFile = generateSequence(directory) { it.parentFile }
            .map { File(it, "rootmodule/testdata/root_snapshot_integration_v42.json") }
            .firstOrNull { it.isFile } ?: throw AssertionError("shared v42 integration snapshot fixture not found")
        val fixture = com.google.gson.JsonParser.parseString(fixtureFile.readText(Charsets.UTF_8)).asJsonObject
        val snapshot = fixture.getAsJsonObject("snapshot")
        val markerConfig = snapshot.getAsJsonObject("config")
        assertEquals(42, snapshot.get("schemaVersion").asInt)
        assertEquals(snapshot.getAsJsonObject("rootRouteNetworkPortRejectRules"),
            io.nekohasekai.sagernet.bg.projectRootRouteNetworkPortRejectRules(markerConfig.deepCopy()))
        assertEquals(snapshot.getAsJsonObject("rootRouteDomainKeywordRejectRules"),
            io.nekohasekai.sagernet.bg.projectRootRouteDomainKeywordRejectRules(markerConfig.deepCopy()))
        val sourceConfig = fixture.getAsJsonObject("projectionInputConfig")
        assertEquals(snapshot.getAsJsonObject("rootDnsPolicy"),
            io.nekohasekai.sagernet.bg.projectRootDNSPolicy(sourceConfig.deepCopy()))
        assertEquals(snapshot.getAsJsonObject("rootRouteFinal"),
            io.nekohasekai.sagernet.bg.projectRootRouteFinal(sourceConfig.deepCopy()))
        val orderedProjectionInput = sourceConfig.deepCopy()
        assertEquals(snapshot.getAsJsonObject("rootRouteProbeRule"),
            io.nekohasekai.sagernet.bg.projectRootRouteProbeRule(orderedProjectionInput, "probe.example"))
        assertEquals(snapshot.getAsJsonObject("rootRouteIpVersionRule"),
            io.nekohasekai.sagernet.bg.projectRootRouteIPVersionRule(orderedProjectionInput, 6))
    }
    @Test fun structuredBasicHttpAndTrojanTlsProjectionIsConservative() {
        val client = source("bg/RootModuleClient.kt")
        assertTrue(client.contains("setOf(\"socks\", \"shadowsocks\", \"trojan\", \"http\")"))
        assertTrue(client.contains("setOf(\"enabled\", \"insecure\", \"server_name\", \"alpn\")"))
        assertTrue(client.contains("add(\"tlsServerName\""))
        assertTrue(client.contains("rootOutbounds.any { it.asJsonObject.get(\"protocol\")?.asString in setOf(\"http\", \"trojan\") } -> 5"))
        assertTrue(client.contains("tlsObj.keySet().any { it !in setOf"))
        assertTrue(client.contains("protocol == \"trojan\" && tls == null"))
    }
    @Test fun rootWideRoutePolicyIsTransferredAsStructuredModuleInput() {
        val client = source("bg/RootModuleClient.kt")
        assertTrue(client.contains("val rootRoutePolicy = if (rootInbound != null)"))
        assertTrue(client.contains("keys.forEach(route::remove)"))
        assertTrue(client.contains("rootRoutePolicy != null -> 6"))
        assertTrue(client.contains("add(\"rootRoutePolicy\", it)"))
        assertTrue(client.contains("default_network_strategy"))
    }
    @Test fun rootDNSResolverRawInputSpecMatchesSharedKotlinGoFixture() {
        val fixture = com.google.gson.JsonParser.parseString(File("../rootmodule/testdata/root_dns_resolvers.json").readText()).asJsonObject
        val expected = fixture.getAsJsonObject("spec")
        val servers = fixture.getAsJsonArray("expectedServers")
        val config = com.google.gson.JsonParser.parseString("""{"dns":{"servers":${servers}},"outbounds":[{"type":"selector","tag":"proxy"}],"route":{"rules":[{"port":[53],"outbound":"proxy"},{"protocol":["dns"],"outbound":"proxy"}]}}""").asJsonObject
        val direct = expected.getAsJsonArray("direct").map { it.asString }
        val remote = expected.getAsJsonArray("remote").map { it.asString }
        assertEquals(expected, io.nekohasekai.sagernet.bg.projectRootDNSResolversSpec(
            config, direct, remote, expected.get("ipv6Mode").asInt,
            expected.get("directDomainStrategy").asString, expected.get("remoteDomainStrategy").asString
        ))
        assertEquals(2, config.getAsJsonObject("route").getAsJsonArray("rules").size())
        assertEquals(3, fixture.getAsJsonArray("expectedServers").size())
        assertEquals(4, fixture.getAsJsonArray("expectedTargetRules").size())
        assertEquals("https", fixture.getAsJsonArray("expectedServers")[1].asJsonObject.get("type").asString)
        assertEquals("prefer_ipv4", fixture.getAsJsonArray("expectedServers")[1].asJsonObject.get("domain_strategy").asString)
        assertEquals("prefer_ipv6", fixture.getAsJsonArray("expectedServers")[2].asJsonObject.get("domain_strategy").asString)
        assertTrue(source("bg/RootModuleClient.kt").contains("rootDNSResolvers != null -> 29"))

        val duplicate = config.deepCopy()
        duplicate.getAsJsonObject("dns").getAsJsonArray("servers").add(servers[1].deepCopy())
        assertNull(io.nekohasekai.sagernet.bg.projectRootDNSResolversSpec(
            duplicate, direct, remote, expected.get("ipv6Mode").asInt,
            expected.get("directDomainStrategy").asString, expected.get("remoteDomainStrategy").asString
        ))
        val missingProxy = config.deepCopy()
        missingProxy.getAsJsonArray("outbounds").remove(0)
        assertNull(io.nekohasekai.sagernet.bg.projectRootDNSResolversSpec(
            missingProxy, direct, remote, expected.get("ipv6Mode").asInt,
            expected.get("directDomainStrategy").asString, expected.get("remoteDomainStrategy").asString
        ))
    }
    @Test fun dnsFinalAndStrategyProjectionMatchesSharedCrossLanguageFixture() {
        val fixture = com.google.gson.JsonParser.parseString(File("../rootmodule/testdata/root_dns_policy_projection.json").readText()).asJsonObject
        val config = fixture.getAsJsonObject("config")
        val expected = fixture.getAsJsonObject("rootDnsPolicy")
        val expectedLocal = fixture.getAsJsonObject("rootDnsLocalServer")
        val projectedLocal = io.nekohasekai.sagernet.bg.projectRootDNSLocalServer(config)
        assertEquals(expectedLocal, projectedLocal)
        val expectedDirect = fixture.getAsJsonObject("rootDnsDirectServer")
        val projectedDirect = io.nekohasekai.sagernet.bg.projectRootDNSDefaultDirectServer(config)
        assertEquals(expectedDirect, projectedDirect)
        val projected = io.nekohasekai.sagernet.bg.projectRootDNSPolicy(config)
        assertEquals(expected, projected)
        val dns = config.getAsJsonObject("dns")
        assertFalse(dns.has("final")); assertFalse(dns.has("strategy")); assertFalse(dns.has("independent_cache"))
        assertEquals(1, dns.getAsJsonArray("servers").size())
        assertEquals(1, dns.getAsJsonArray("rules").size())
        assertTrue(projected!!.get("independentCache").asBoolean)
        assertTrue(source("bg/RootModuleClient.kt").contains("rootDNSDirectServer != null -> 11"))
        assertTrue(source("bg/RootModuleClient.kt").contains("rootDNSLocalServer != null -> 10"))
        assertTrue(source("bg/RootModuleClient.kt").contains("rootDNSPolicy?.has(\"independentCache\") == true -> 9"))
        assertTrue(source("bg/RootModuleClient.kt").contains("rootRouteFinal != null -> 12"))
        assertTrue(source("bg/RootModuleClient.kt").contains("add(\"rootRouteFinal\", it)"))
    }
    @Test fun routeFinalProjectionMatchesSharedCrossLanguageFixtureAndKeepsUnknowns() {
        val fixture = com.google.gson.JsonParser.parseString(File("../rootmodule/testdata/root_dns_policy_projection.json").readText()).asJsonObject.getAsJsonObject("routeFinalProjection")
        val config = fixture.getAsJsonObject("config")
        assertEquals(fixture.getAsJsonObject("rootRouteFinal"), io.nekohasekai.sagernet.bg.projectRootRouteFinal(config))
        assertFalse(config.getAsJsonObject("route").has("final"))
        assertEquals(fixture.getAsJsonObject("expectedRoute").getAsJsonArray("rules"), config.getAsJsonObject("route").getAsJsonArray("rules"))
        val unsupported = com.google.gson.JsonParser.parseString("""{"outbounds":[],"route":{"final":"missing","rules":[{"action":"sniff"}]}}""").asJsonObject
        assertNull(io.nekohasekai.sagernet.bg.projectRootRouteFinal(unsupported))
        assertTrue(unsupported.getAsJsonObject("route").has("final"))
    }
    @Test fun routeLoopbackProjectionMatchesSharedCrossLanguageFixtureAndPreservesUnknownRules() {
        val fixture = com.google.gson.JsonParser.parseString(File("../rootmodule/testdata/root_dns_policy_projection.json").readText()).asJsonObject.getAsJsonObject("routeLoopbackProjection")
        val config = fixture.getAsJsonObject("config")
        assertEquals(fixture.getAsJsonObject("rootRouteLoopback"), io.nekohasekai.sagernet.bg.projectRootRouteLoopback(config))
        assertEquals(fixture.getAsJsonObject("expectedLegacyRoute").getAsJsonArray("rules"), config.getAsJsonObject("route").getAsJsonArray("rules"))
        assertTrue(source("bg/RootModuleClient.kt").contains("rootRouteLoopback != null -> 13"))
        val duplicate = com.google.gson.JsonParser.parseString("""{"route":{"rules":[{"ip_cidr":["127.0.0.0/8","::1/128","224.0.0.0/4","169.254.0.0/16","fe80::/10"],"outbound":"direct"},{"ip_cidr":["127.0.0.0/8","::1/128","224.0.0.0/4","169.254.0.0/16","fe80::/10"],"outbound":"direct"}]}}""").asJsonObject
        assertNull(io.nekohasekai.sagernet.bg.projectRootRouteLoopback(duplicate))
        assertEquals(2, duplicate.getAsJsonObject("route").getAsJsonArray("rules").size())
    }
    @Test fun routeProbeProjectionMatchesSharedFixtureAndKeepsUnsupportedShapes() {
        val fixture = com.google.gson.JsonParser.parseString(File("../rootmodule/testdata/root_dns_policy_projection.json").readText()).asJsonObject.getAsJsonObject("routeProbeRuleProjection")
        val config = fixture.getAsJsonObject("config")
        assertEquals(fixture.getAsJsonObject("rootRouteProbeRule"), io.nekohasekai.sagernet.bg.projectRootRouteProbeRule(config, "probe.example"))
        val sniffIndex = io.nekohasekai.sagernet.bg.projectRootRouteSniffRule(config.deepCopy())!!.get("index").asInt
        val ipVersion = io.nekohasekai.sagernet.bg.projectRootRouteIPVersionRule(config, 6)?.apply {
            addProperty("index", io.nekohasekai.sagernet.bg.rootRouteIndexAfterSniff(get("index").asInt, sniffIndex))
        }
        assertEquals(fixture.getAsJsonObject("rootRouteIPVersionRule"), ipVersion)
        assertEquals(fixture.getAsJsonObject("rootRouteSniffRule"), io.nekohasekai.sagernet.bg.projectRootRouteSniffRule(config))
        assertEquals(fixture.getAsJsonArray("expectedLegacyRules"), config.getAsJsonObject("route").getAsJsonArray("rules"))
        val client = source("bg/RootModuleClient.kt")
        assertTrue(client.contains("rootRouteProbeRule != null -> 25"))
        assertTrue(client.contains("add(\"rootRouteProbeRule\", it)"))
        val duplicate = com.google.gson.JsonParser.parseString("""{"route":{"rules":[{"inbound":["mixed"],"domain":["probe.example"],"outbound":"proxy"},{"inbound":["mixed"],"domain":["probe.example"],"outbound":"proxy"}]}}""").asJsonObject
        assertNull(io.nekohasekai.sagernet.bg.projectRootRouteProbeRule(duplicate, "probe.example"))
        val extended = com.google.gson.JsonParser.parseString("""{"route":{"rules":[{"inbound":["mixed"],"domain":["probe.example"],"outbound":"proxy","network":["tcp"]}]}}""").asJsonObject
        assertNull(io.nekohasekai.sagernet.bg.projectRootRouteProbeRule(extended, "probe.example"))
        val notFirst = com.google.gson.JsonParser.parseString("""{"route":{"rules":[{"action":"sniff"},{"inbound":["mixed"],"domain":["probe.example"],"outbound":"proxy"}]}}""").asJsonObject
        assertNull(io.nekohasekai.sagernet.bg.projectRootRouteProbeRule(notFirst, "probe.example"))
    }

    @Test fun routeResolveProjectionMatchesSharedFixtureAndKeepsUnsupportedShapes() {
        val fixture = com.google.gson.JsonParser.parseString(File("../rootmodule/testdata/root_dns_policy_projection.json").readText()).asJsonObject.getAsJsonObject("routeResolveRuleProjection")
        val config = fixture.getAsJsonObject("config")
        assertEquals(fixture.getAsJsonObject("rootRouteProbeRule"), io.nekohasekai.sagernet.bg.projectRootRouteProbeRule(config, "probe.example"))
        val sniffIndex = io.nekohasekai.sagernet.bg.projectRootRouteSniffRule(config.deepCopy())!!.get("index").asInt
        val resolveCandidate = config.deepCopy().apply { io.nekohasekai.sagernet.bg.projectRootRouteSniffRule(this) }
        val resolveWillProject = io.nekohasekai.sagernet.bg.projectRootRouteResolveRule(resolveCandidate) != null
        val ipVersion = io.nekohasekai.sagernet.bg.projectRootRouteIPVersionRule(config, 6)?.apply {
            val afterSniff = io.nekohasekai.sagernet.bg.rootRouteIndexAfterSniff(get("index").asInt, sniffIndex)
            addProperty("index", afterSniff - if (resolveWillProject && afterSniff > 0) 1 else 0)
        }
        assertEquals(fixture.getAsJsonObject("rootRouteIPVersionRule"), ipVersion)
        assertEquals(fixture.getAsJsonObject("rootRouteSniffRule"), io.nekohasekai.sagernet.bg.projectRootRouteSniffRule(config))
        assertEquals(fixture.getAsJsonObject("rootRouteResolveRule"), io.nekohasekai.sagernet.bg.projectRootRouteResolveRule(config))
        assertEquals(fixture.getAsJsonArray("expectedLegacyRules"), config.getAsJsonObject("route").getAsJsonArray("rules"))
        val client = source("bg/RootModuleClient.kt")
        assertTrue(client.contains("rootRouteResolveRule != null -> 26"))
        assertTrue(client.contains("add(\"rootRouteResolveRule\", it)"))
        val duplicate = com.google.gson.JsonParser.parseString("""{"route":{"rules":[{"action":"resolve","strategy":"prefer_ipv4"},{"action":"resolve","strategy":"prefer_ipv4"}]}}""").asJsonObject
        assertNull(io.nekohasekai.sagernet.bg.projectRootRouteResolveRule(duplicate))
        val nonFirst = com.google.gson.JsonParser.parseString("""{"route":{"rules":[{"action":"sniff"},{"action":"resolve","strategy":"prefer_ipv4"}]}}""").asJsonObject
        assertNull(io.nekohasekai.sagernet.bg.projectRootRouteResolveRule(nonFirst))
        val unsupported = com.google.gson.JsonParser.parseString("""{"route":{"rules":[{"action":"resolve","strategy":"unsupported"}]}}""").asJsonObject
        assertNull(io.nekohasekai.sagernet.bg.projectRootRouteResolveRule(unsupported))
    }

    @Test fun routeDNSTargetBlockMatchesSharedFixtureAndPreservesFallbackOrder() {
        val fixture = com.google.gson.JsonParser.parseString(File("../rootmodule/testdata/root_dns_policy_projection.json").readText()).asJsonObject
        val projectionFixture = fixture.getAsJsonObject("routeDNSTargetRulesProjection")
        val config = projectionFixture.getAsJsonObject("config").deepCopy()
        val expected = projectionFixture.getAsJsonObject("rootRouteDnsTargetRules")
        assertEquals(expected, io.nekohasekai.sagernet.bg.projectRootRouteDNSTargetRules(config))
        assertEquals(projectionFixture.getAsJsonArray("expectedAfterProjectionRules"), config.getAsJsonObject("route").getAsJsonArray("rules"))
        val normalized = projectionFixture.getAsJsonObject("normalizedDnsTargets")
        assertEquals(listOf("dns.local"), normalized.getAsJsonArray("directDomains").map { it.asString })
        assertEquals(listOf("shared.example"), normalized.getAsJsonArray("overlapRemoved").map { it.asString })
        assertTrue(normalized.getAsJsonArray("remoteDomains").map { it.asString }.contains("shared.example"))
        assertTrue(source("bg/RootModuleClient.kt").contains("rootRouteDNSTargetRules != null -> 28"))
        assertTrue(source("bg/RootModuleClient.kt").contains("add(\"rootRouteDnsTargetRules\", it)"))

        val lateRemoval = projectionFixture.getAsJsonObject("config").deepCopy()
        assertTrue(io.nekohasekai.sagernet.bg.removeRootRouteDNSTargetRules(lateRemoval, expected))
        assertEquals(projectionFixture.getAsJsonArray("expectedAfterProjectionRules"), lateRemoval.getAsJsonObject("route").getAsJsonArray("rules"))

        val duplicate = projectionFixture.getAsJsonObject("config").deepCopy()
        val duplicateRules = duplicate.getAsJsonObject("route").getAsJsonArray("rules")
        duplicateRules.add(duplicateRules[6].deepCopy())
        val beforeDuplicate = duplicateRules.deepCopy()
        assertNull(io.nekohasekai.sagernet.bg.projectRootRouteDNSTargetRules(duplicate))
        assertEquals(beforeDuplicate, duplicate.getAsJsonObject("route").getAsJsonArray("rules"))

        val malformed = projectionFixture.getAsJsonObject("config").deepCopy()
        malformed.getAsJsonObject("route").getAsJsonArray("rules")[6].asJsonObject.getAsJsonArray("domain")[0] = com.google.gson.JsonPrimitive(" dns.local")
        val beforeMalformed = malformed.getAsJsonObject("route").getAsJsonArray("rules").deepCopy()
        assertNull(io.nekohasekai.sagernet.bg.projectRootRouteDNSTargetRules(malformed))
        assertEquals(beforeMalformed, malformed.getAsJsonObject("route").getAsJsonArray("rules"))

        val mismatchedFallback = projectionFixture.getAsJsonObject("config").deepCopy()
        mismatchedFallback.getAsJsonObject("route").getAsJsonArray("rules")[11].asJsonObject.addProperty("outbound", "direct")
        assertNull(io.nekohasekai.sagernet.bg.projectRootRouteDNSTargetRules(mismatchedFallback))
        val empty = projectionFixture.getAsJsonObject("config").deepCopy()
        repeat(4) { empty.getAsJsonObject("route").getAsJsonArray("rules").remove(6) }
        assertNull(io.nekohasekai.sagernet.bg.projectRootRouteDNSTargetRules(empty))
    }

    @Test fun routeModePairProjectionMatchesSharedFixtureAndKeepsUnsupportedShapes() {
        val fixture = com.google.gson.JsonParser.parseString(File("../rootmodule/testdata/root_dns_policy_projection.json").readText()).asJsonObject.getAsJsonObject("routeModeRulesProjection")
        val config = fixture.getAsJsonObject("config")
        assertEquals(fixture.getAsJsonObject("rootRouteModeRules"), io.nekohasekai.sagernet.bg.projectRootRouteModeRules(config))
        assertEquals(fixture.getAsJsonArray("expectedAfterModeProjectionRules"), config.getAsJsonObject("route").getAsJsonArray("rules"))
        val client = source("bg/RootModuleClient.kt")
        assertTrue(client.contains("rootRouteModeRules != null -> 27"))
        assertTrue(client.contains("add(\"rootRouteModeRules\", it)"))

        val duplicate = com.google.gson.JsonParser.parseString(fixture.getAsJsonObject("config").toString()).asJsonObject
        val duplicateRules = duplicate.getAsJsonObject("route").getAsJsonArray("rules")
        duplicateRules.add(com.google.gson.JsonParser.parseString("""{"clash_mode":"global","outbound":"proxy"}"""))
        val duplicateBefore = duplicateRules.deepCopy()
        assertNull(io.nekohasekai.sagernet.bg.projectRootRouteModeRules(duplicate))
        assertEquals(duplicateBefore, duplicate.getAsJsonObject("route").getAsJsonArray("rules"))

        val incomplete = com.google.gson.JsonParser.parseString("""{"route":{"rules":[{"clash_mode":"global","outbound":"proxy"}]}}""").asJsonObject
        assertNull(io.nekohasekai.sagernet.bg.projectRootRouteModeRules(incomplete))
        val nonAdjacent = com.google.gson.JsonParser.parseString("""{"route":{"rules":[{"clash_mode":"global","outbound":"proxy"},{"domain":["keep.example"],"outbound":"direct"},{"clash_mode":"direct","outbound":"direct"}]}}""").asJsonObject
        assertNull(io.nekohasekai.sagernet.bg.projectRootRouteModeRules(nonAdjacent))
        val malformed = com.google.gson.JsonParser.parseString("""{"route":{"rules":[{"clash_mode":"global","outbound":"proxy","network":["tcp"]},{"clash_mode":"direct","outbound":"direct"}]}}""").asJsonObject
        assertNull(io.nekohasekai.sagernet.bg.projectRootRouteModeRules(malformed))
        val reversed = com.google.gson.JsonParser.parseString("""{"route":{"rules":[{"clash_mode":"direct","outbound":"direct"},{"clash_mode":"global","outbound":"proxy"}]}}""").asJsonObject
        assertNull(io.nekohasekai.sagernet.bg.projectRootRouteModeRules(reversed))
    }

    @Test fun routeDNSPort53ProjectionMatchesSharedFixtureAndPreservesOrder() {
        val fixture = com.google.gson.JsonParser.parseString(File("../rootmodule/testdata/root_dns_policy_projection.json").readText()).asJsonObject.getAsJsonObject("routeDNSPort53RuleProjection")
        val config = fixture.getAsJsonObject("config")
        assertEquals(fixture.getAsJsonObject("rootRouteDNSPort53Rule"), io.nekohasekai.sagernet.bg.projectRootRouteDNSPort53Rule(config))
        assertEquals(fixture.getAsJsonArray("expectedLegacyRules"), config.getAsJsonObject("route").getAsJsonArray("rules"))
        val client = source("bg/RootModuleClient.kt")
        assertTrue(client.contains("rootRouteDNSPort53Rule != null -> 17"))
        assertTrue(client.contains("add(\"rootRouteDnsPort53Rule\", it)"))
        val duplicate = com.google.gson.JsonParser.parseString("""{"outbounds":[{"tag":"proxy"}],"route":{"rules":[{"port":[53],"outbound":"proxy"},{"port":[53],"outbound":"proxy"}]}}""").asJsonObject
        assertNull(io.nekohasekai.sagernet.bg.projectRootRouteDNSPort53Rule(duplicate))
        assertEquals(2, duplicate.getAsJsonObject("route").getAsJsonArray("rules").size())
        val extended = com.google.gson.JsonParser.parseString("""{"outbounds":[{"tag":"proxy"}],"route":{"rules":[{"port":[53],"outbound":"proxy","network":["tcp"]}]}}""").asJsonObject
        assertNull(io.nekohasekai.sagernet.bg.projectRootRouteDNSPort53Rule(extended))
        assertEquals(1, extended.getAsJsonObject("route").getAsJsonArray("rules").size())
    }
    @Test fun routeBypassLanProjectionMatchesSharedFixtureAndPreservesOrder() {
        val fixture = com.google.gson.JsonParser.parseString(File("../rootmodule/testdata/root_dns_policy_projection.json").readText()).asJsonObject.getAsJsonObject("routeBypassLanProjection")
        val config = fixture.getAsJsonObject("config")
        assertEquals(fixture.getAsJsonObject("rootRouteLoopback"), io.nekohasekai.sagernet.bg.projectRootRouteLoopback(config))
        assertEquals(fixture.getAsJsonObject("rootRouteMulticast"), io.nekohasekai.sagernet.bg.projectRootRouteMulticast(config))
        assertEquals(fixture.getAsJsonObject("rootRouteBypassLan"), io.nekohasekai.sagernet.bg.projectRootRouteBypassLan(config))
        assertEquals(fixture.getAsJsonObject("rootRouteDNSPort53Rule"), io.nekohasekai.sagernet.bg.projectRootRouteDNSPort53Rule(config))
        assertEquals(fixture.getAsJsonObject("rootRouteDNSProtocolRule"), io.nekohasekai.sagernet.bg.projectRootRouteDNSProtocolRule(config))
        assertEquals(fixture.getAsJsonArray("expectedLegacyRules"), config.getAsJsonObject("route").getAsJsonArray("rules"))
        assertTrue(source("bg/RootModuleClient.kt").contains("rootRouteBypassLan != null -> 19"))
        val duplicate = com.google.gson.JsonParser.parseString("""{"route":{"rules":[{"ip_cidr":["10.0.0.0/8","172.16.0.0/12","192.168.0.0/16","fc00::/7"],"outbound":"direct"},{"ip_cidr":["10.0.0.0/8","172.16.0.0/12","192.168.0.0/16","fc00::/7"],"outbound":"direct"}]}}""").asJsonObject
        assertNull(io.nekohasekai.sagernet.bg.projectRootRouteBypassLan(duplicate))
        val extended = com.google.gson.JsonParser.parseString("""{"route":{"rules":[{"ip_cidr":["10.0.0.0/8","172.16.0.0/12","192.168.0.0/16","fc00::/7"],"outbound":"direct","network":["tcp"]}]}}""").asJsonObject
        assertNull(io.nekohasekai.sagernet.bg.projectRootRouteBypassLan(extended))
    }
    @Test fun routeDNSProtocolRuleProjectionMatchesSharedFixtureAndPreservesOrder() {
        val fixture = com.google.gson.JsonParser.parseString(File("../rootmodule/testdata/root_dns_policy_projection.json").readText()).asJsonObject.getAsJsonObject("routeDNSProtocolRuleProjection")
        val config = fixture.getAsJsonObject("config")
        assertEquals(fixture.getAsJsonObject("rootRouteMulticast"), io.nekohasekai.sagernet.bg.projectRootRouteMulticast(config))
        val port53 = io.nekohasekai.sagernet.bg.projectRootRouteDNSPort53Rule(config)
        assertEquals(fixture.getAsJsonObject("rootRouteDNSPort53Rule"), port53)
        assertEquals(fixture.getAsJsonObject("rootRouteDNSProtocolRule"), io.nekohasekai.sagernet.bg.projectRootRouteDNSProtocolRule(config))
        assertEquals(fixture.getAsJsonArray("expectedLegacyRules"), config.getAsJsonObject("route").getAsJsonArray("rules"))
        val duplicate = com.google.gson.JsonParser.parseString("""{"outbounds":[{"tag":"proxy"}],"route":{"rules":[{"protocol":["dns"],"outbound":"proxy"},{"protocol":["dns"],"outbound":"proxy"}]}}""").asJsonObject
        assertNull(io.nekohasekai.sagernet.bg.projectRootRouteDNSProtocolRule(duplicate))
        assertEquals(2, duplicate.getAsJsonObject("route").getAsJsonArray("rules").size())
        val extended = com.google.gson.JsonParser.parseString("""{"outbounds":[{"tag":"proxy"}],"route":{"rules":[{"protocol":["dns"],"outbound":"proxy","network":["tcp"]}]}}""").asJsonObject
        assertNull(io.nekohasekai.sagernet.bg.projectRootRouteDNSProtocolRule(extended))
        assertEquals(1, extended.getAsJsonObject("route").getAsJsonArray("rules").size())
        assertTrue(source("bg/RootModuleClient.kt").contains("rootRouteDNSProtocolRule != null -> 18"))
    }
    @Test fun privateBypassMaterializationIndexAccountsForEarlierRestoredRules() {
        assertEquals(6, io.nekohasekai.sagernet.bg.rootRoutePrivateBypassModuleIndex(7, listOf(8, 4, 2)))
        assertEquals(7, io.nekohasekai.sagernet.bg.rootRoutePrivateBypassModuleIndex(7, listOf(0, 6)))
        assertTrue(source("bg/RootModuleClient.kt").contains("rootRoutePrivateBypassModuleIndex(originalIndex, earlierProjectedIndexes)"))
    }

    @Test fun routePrivateBypassProjectionMatchesSharedFixtureAndKeepsUnsupportedRules() {
        val fixture = com.google.gson.JsonParser.parseString(File("../rootmodule/testdata/root_dns_policy_projection.json").readText()).asJsonObject.getAsJsonObject("routePrivateBypassProjection")
        val config = fixture.getAsJsonObject("config")
        assertEquals(fixture.getAsJsonObject("rootRoutePrivateBypass"), io.nekohasekai.sagernet.bg.projectRootRoutePrivateBypass(config))
        assertEquals(fixture.getAsJsonArray("expectedLegacyRulesAfterPrivateBypass"), config.getAsJsonObject("route").getAsJsonArray("rules"))
        val client = source("bg/RootModuleClient.kt")
        assertTrue(client.contains("rootRoutePrivateBypass != null -> 21"))
        assertTrue(client.contains("projectRootRoutePrivateBypass(config.asJsonObject)"))
        val duplicate = com.google.gson.JsonParser.parseString("""{"route":{"rules":[{"ip_is_private":true,"outbound":"bypass"},{"ip_is_private":true,"outbound":"bypass"}]}}""").asJsonObject
        assertNull(io.nekohasekai.sagernet.bg.projectRootRoutePrivateBypass(duplicate))
        val extended = com.google.gson.JsonParser.parseString("""{"route":{"rules":[{"ip_is_private":true,"outbound":"bypass","network":["tcp"]}]}}""").asJsonObject
        assertNull(io.nekohasekai.sagernet.bg.projectRootRoutePrivateBypass(extended))
        val falseValue = com.google.gson.JsonParser.parseString("""{"route":{"rules":[{"ip_is_private":false,"outbound":"bypass"}]}}""").asJsonObject
        assertNull(io.nekohasekai.sagernet.bg.projectRootRoutePrivateBypass(falseValue))
    }

    @Test fun routeDNSHijackPort53ProjectionMatchesSharedFixtureAndPreservesCustomRules() {
        val fixture = com.google.gson.JsonParser.parseString(File("../rootmodule/testdata/root_dns_policy_projection.json").readText()).asJsonObject.getAsJsonObject("routePrivateBypassProjection")
        val config = fixture.getAsJsonObject("config").deepCopy()
        assertEquals(fixture.getAsJsonObject("rootRouteDNSHijackPort53Rule"), io.nekohasekai.sagernet.bg.projectRootRouteDNSHijackPort53Rule(config))
        assertEquals(fixture.getAsJsonArray("expectedLegacyRulesAfterDNSHijackPort53"), config.getAsJsonObject("route").getAsJsonArray("rules"))
        val client = source("bg/RootModuleClient.kt")
        assertTrue(client.contains("rootRouteDNSHijackPort53Rule != null -> 22"))
        assertTrue(client.contains("projectRootRouteDNSHijackPort53Rule(config.asJsonObject)"))
        val duplicate = com.google.gson.JsonParser.parseString("""{"route":{"rules":[{"port":[53],"action":"hijack-dns"},{"port":[53],"action":"hijack-dns"}]}}""").asJsonObject
        assertNull(io.nekohasekai.sagernet.bg.projectRootRouteDNSHijackPort53Rule(duplicate))
        val extended = com.google.gson.JsonParser.parseString("""{"route":{"rules":[{"port":[53],"action":"hijack-dns","network":["tcp"]}]}}""").asJsonObject
        assertNull(io.nekohasekai.sagernet.bg.projectRootRouteDNSHijackPort53Rule(extended))
    }

    @Test fun routeDNSProtocolHijackProjectionMatchesSharedFixtureAndPreservesCustomRules() {
        val fixture = com.google.gson.JsonParser.parseString(File("../rootmodule/testdata/root_dns_policy_projection.json").readText()).asJsonObject.getAsJsonObject("routeDNSProtocolHijackRuleProjection")
        val config = fixture.getAsJsonObject("config").deepCopy()
        assertEquals(fixture.getAsJsonObject("rootRouteDNSProtocolHijackRule"), io.nekohasekai.sagernet.bg.projectRootRouteDNSProtocolHijackRule(config))
        assertEquals(fixture.getAsJsonArray("expectedLegacyRules"), config.getAsJsonObject("route").getAsJsonArray("rules"))
        val client = source("bg/RootModuleClient.kt")
        assertTrue(client.contains("rootRouteDNSProtocolHijackRule != null -> 23"))
        assertTrue(client.contains("projectRootRouteDNSProtocolHijackRule(config.asJsonObject)"))
        val duplicate = com.google.gson.JsonParser.parseString("""{"route":{"rules":[{"protocol":["dns"],"action":"hijack-dns"},{"protocol":["dns"],"action":"hijack-dns"}]}}""").asJsonObject
        assertNull(io.nekohasekai.sagernet.bg.projectRootRouteDNSProtocolHijackRule(duplicate))
        val extended = com.google.gson.JsonParser.parseString("""{"route":{"rules":[{"protocol":["dns"],"action":"hijack-dns","network":["tcp"]}]}}""").asJsonObject
        assertNull(io.nekohasekai.sagernet.bg.projectRootRouteDNSProtocolHijackRule(extended))
    }

    @Test fun routeSniffProjectionMatchesSharedFixtureAndPreservesCrossProjectionOrder() {
        val fixture = com.google.gson.JsonParser.parseString(File("../rootmodule/testdata/root_dns_policy_projection.json").readText()).asJsonObject.getAsJsonObject("routeSniffRuleProjection")
        val original = fixture.getAsJsonObject("config")
        val rawSniff = io.nekohasekai.sagernet.bg.projectRootRouteSniffRule(original.deepCopy())
        assertEquals(fixture.getAsJsonObject("rootRouteSniffRule"), rawSniff)
        val config = original.deepCopy()
        val protocolRule = io.nekohasekai.sagernet.bg.projectRootRouteDNSProtocolHijackRule(config)
        assertEquals(2, protocolRule!!.get("index").asInt)
        assertEquals(1, io.nekohasekai.sagernet.bg.rootRouteIndexAfterSniff(protocolRule.get("index").asInt, rawSniff!!.get("index").asInt))
        assertEquals(fixture.getAsJsonObject("rootRouteIPVersionRule"), io.nekohasekai.sagernet.bg.projectRootRouteIPVersionRule(config, 6))
        val sniffAfterEarlierProjections = io.nekohasekai.sagernet.bg.projectRootRouteSniffRule(config)
        assertEquals(0, sniffAfterEarlierProjections!!.get("index").asInt)
        assertEquals(fixture.getAsJsonArray("expectedLegacyRules"), config.getAsJsonObject("route").getAsJsonArray("rules"))
        assertEquals(0, io.nekohasekai.sagernet.bg.rootRouteIndexAfterSniff(0, 1))
        assertEquals(1, io.nekohasekai.sagernet.bg.rootRouteIndexAfterSniff(2, 1))
        val client = source("bg/RootModuleClient.kt")
        assertTrue(client.contains("rootRouteSniffRule != null -> 24"))
        assertTrue(client.contains("projectRootRouteSniffRule(config.asJsonObject)"))
        val duplicate = com.google.gson.JsonParser.parseString("""{"route":{"rules":[{"action":"sniff"},{"action":"sniff"}]}}""").asJsonObject
        assertNull(io.nekohasekai.sagernet.bg.projectRootRouteSniffRule(duplicate))
        assertEquals(2, duplicate.getAsJsonObject("route").getAsJsonArray("rules").size())
        val extended = com.google.gson.JsonParser.parseString("""{"route":{"rules":[{"action":"sniff","network":["tcp"]}]}}""").asJsonObject
        assertNull(io.nekohasekai.sagernet.bg.projectRootRouteSniffRule(extended))
        assertEquals(1, extended.getAsJsonObject("route").getAsJsonArray("rules").size())
    }

    @Test fun routeIPVersionRuleProjectionMatchesSharedFixtureAndRestoresOrder() {
        val fixture = com.google.gson.JsonParser.parseString(File("../rootmodule/testdata/root_dns_policy_projection.json").readText()).asJsonObject.getAsJsonObject("routeIPVersionRuleProjection")
        val config = fixture.getAsJsonObject("config")
        assertEquals(fixture.getAsJsonObject("rootRouteLoopback"), io.nekohasekai.sagernet.bg.projectRootRouteLoopback(config))
        assertEquals(fixture.getAsJsonObject("rootRouteMulticast"), io.nekohasekai.sagernet.bg.projectRootRouteMulticast(config))
        assertEquals(fixture.getAsJsonObject("rootRouteBypassLan"), io.nekohasekai.sagernet.bg.projectRootRouteBypassLan(config))
        assertEquals(fixture.getAsJsonObject("rootRouteDNSPort53Rule"), io.nekohasekai.sagernet.bg.projectRootRouteDNSPort53Rule(config))
        assertEquals(fixture.getAsJsonObject("rootRouteDNSProtocolRule"), io.nekohasekai.sagernet.bg.projectRootRouteDNSProtocolRule(config))
        assertEquals(fixture.getAsJsonObject("rootRouteIPVersionRule"), io.nekohasekai.sagernet.bg.projectRootRouteIPVersionRule(config, 6))
        assertEquals(fixture.getAsJsonArray("expectedLegacyRules"), config.getAsJsonObject("route").getAsJsonArray("rules"))
        val client = source("bg/RootModuleClient.kt")
        assertTrue(client.contains("rootRouteIPVersionRule != null -> 20"))
        val duplicate = com.google.gson.JsonParser.parseString("""{"route":{"rules":[{"ip_version":6,"action":"reject"},{"ip_version":6,"action":"reject"}]}}""").asJsonObject
        assertNull(io.nekohasekai.sagernet.bg.projectRootRouteIPVersionRule(duplicate, 6))
        val extended = com.google.gson.JsonParser.parseString("""{"route":{"rules":[{"ip_version":6,"action":"reject","network":["tcp"]}]}}""").asJsonObject
        assertNull(io.nekohasekai.sagernet.bg.projectRootRouteIPVersionRule(extended, 6))
        val fractional = com.google.gson.JsonParser.parseString("""{"route":{"rules":[{"ip_version":6.0,"action":"reject"}]}}""").asJsonObject
        assertNull(io.nekohasekai.sagernet.bg.projectRootRouteIPVersionRule(fractional, 6))
        val wrongMode = com.google.gson.JsonParser.parseString("""{"route":{"rules":[{"ip_version":6,"action":"reject"}]}}""").asJsonObject
        assertNull(io.nekohasekai.sagernet.bg.projectRootRouteIPVersionRule(wrongMode, 4))
        assertEquals(1, wrongMode.getAsJsonObject("route").getAsJsonArray("rules").size())
    }
    @Test fun routeMulticastProjectionMatchesSharedFixtureAndPreservesOrder() {
        val fixture = com.google.gson.JsonParser.parseString(File("../rootmodule/testdata/root_dns_policy_projection.json").readText()).asJsonObject.getAsJsonObject("routeMulticastProjection")
        val config = fixture.getAsJsonObject("config")
        assertEquals(fixture.getAsJsonObject("rootRouteMulticast"), io.nekohasekai.sagernet.bg.projectRootRouteMulticast(config))
        assertEquals(fixture.getAsJsonArray("expectedLegacyRules"), config.getAsJsonObject("route").getAsJsonArray("rules"))
        assertTrue(source("bg/RootModuleClient.kt").contains("rootRouteMulticast != null -> 14"))
        val duplicate = com.google.gson.JsonParser.parseString("""{"route":{"rules":[{"ip_cidr":["224.0.0.0/3","ff00::/8"],"source_ip_cidr":["224.0.0.0/3","ff00::/8"],"action":"reject"},{"ip_cidr":["224.0.0.0/3","ff00::/8"],"source_ip_cidr":["224.0.0.0/3","ff00::/8"],"action":"reject"}]}}""").asJsonObject
        assertNull(io.nekohasekai.sagernet.bg.projectRootRouteMulticast(duplicate))
        assertEquals(2, duplicate.getAsJsonObject("route").getAsJsonArray("rules").size())
    }
    @Test fun dnsOutboundAnyRuleProjectionMatchesSharedFixtureAndPreservesOrder() {
        val fixture = com.google.gson.JsonParser.parseString(File("../rootmodule/testdata/root_dns_policy_projection.json").readText()).asJsonObject.getAsJsonObject("dnsOutboundAnyRuleProjection")
        val config = fixture.getAsJsonObject("config")
        assertEquals(fixture.getAsJsonObject("rootDnsOutboundAnyRule"), io.nekohasekai.sagernet.bg.projectRootDNSOutboundAnyRule(config))
        assertEquals(fixture.getAsJsonArray("expectedLegacyRules"), config.getAsJsonObject("dns").getAsJsonArray("rules"))
        assertTrue(source("bg/RootModuleClient.kt").contains("rootDNSOutboundAnyRule != null -> 15"))
        val duplicate = com.google.gson.JsonParser.parseString("""{"dns":{"rules":[{"outbound":["any"],"server":"dns-direct"},{"outbound":["any"],"server":"dns-direct"}]}}""").asJsonObject
        assertNull(io.nekohasekai.sagernet.bg.projectRootDNSOutboundAnyRule(duplicate))
        assertEquals(2, duplicate.getAsJsonObject("dns").getAsJsonArray("rules").size())
    }
    @Test fun dnsIPv6QueryRuleProjectionMatchesSharedFixtureAndPreservesLegacyRules() {
        val fixture = com.google.gson.JsonParser.parseString(File("../rootmodule/testdata/root_dns_policy_projection.json").readText()).asJsonObject.getAsJsonObject("dnsIPv6QueryRuleProjection")
        val config = fixture.getAsJsonObject("config")
        assertEquals(fixture.getAsJsonObject("rootDnsIPv6QueryRule"), io.nekohasekai.sagernet.bg.projectRootDNSIPv6QueryRule(config))
        assertEquals(fixture.getAsJsonArray("expectedLegacyRules"), config.getAsJsonObject("dns").getAsJsonArray("rules"))
        assertTrue(source("bg/RootModuleClient.kt").contains("rootDNSIPv6QueryRule != null -> 16"))
        val duplicate = com.google.gson.JsonParser.parseString("""{"dns":{"strategy":"ipv4_only","rules":[{"query_type":["AAAA"],"action":"reject"},{"query_type":["AAAA"],"action":"reject"}]}}""").asJsonObject
        assertNull(io.nekohasekai.sagernet.bg.projectRootDNSIPv6QueryRule(duplicate))
        assertEquals(2, duplicate.getAsJsonObject("dns").getAsJsonArray("rules").size())
        val unsupported = com.google.gson.JsonParser.parseString("""{"dns":{"strategy":"prefer_ipv4","rules":[{"query_type":["AAAA"],"action":"reject"}]}}""").asJsonObject
        assertNull(io.nekohasekai.sagernet.bg.projectRootDNSIPv6QueryRule(unsupported))
        assertEquals(1, unsupported.getAsJsonObject("dns").getAsJsonArray("rules").size())
    }
    @Test fun customDirectDNSServerRemainsInLegacyConfig() {
        val config = com.google.gson.JsonParser.parseString("""{"dns":{"servers":[{"type":"https","tag":"dns-direct","server":"1.1.1.1","server_port":443,"path":"/dns-query","detour":"direct"}]}}""").asJsonObject
        assertNull(io.nekohasekai.sagernet.bg.projectRootDNSDefaultDirectServer(config))
        assertEquals(1, config.getAsJsonObject("dns").getAsJsonArray("servers").size())
    }
    @Test fun customLocalDNSServerRemainsInLegacyConfig() {
        val config = com.google.gson.JsonParser.parseString("""{"dns":{"servers":[{"type":"local","tag":"dns-local","detour":"direct","custom":true}]}}""").asJsonObject
        assertNull(io.nekohasekai.sagernet.bg.projectRootDNSLocalServer(config))
        assertEquals(1, config.getAsJsonObject("dns").getAsJsonArray("servers").size())
    }
    @Test fun dnsFinalAndStrategyProjectionRetainsSchema8WhenCachePolicyIsAbsent() {
        val config = com.google.gson.JsonParser.parseString("""{"dns":{"final":"dns-remote","strategy":"prefer_ipv4","servers":[{"tag":"dns-remote"}],"rules":[{"domain":["example.org"]}]}}""").asJsonObject
        val projected = io.nekohasekai.sagernet.bg.projectRootDNSPolicy(config)
        assertEquals("dns-remote", projected!!.get("final").asString)
        assertEquals("prefer_ipv4", projected.get("strategy").asString)
        assertFalse(projected.has("independentCache"))
        val dns = config.getAsJsonObject("dns")
        assertFalse(dns.has("final")); assertFalse(dns.has("strategy"))
        assertEquals(1, dns.getAsJsonArray("rules").size())
    }
    @Test fun dnsPolicyProjectionLeavesUnsupportedFieldsInLegacyConfig() {
        val config = com.google.gson.JsonParser.parseString("""{"dns":{"final":"dns-remote","strategy":"custom","servers":[{"tag":"dns-remote"}]}}""").asJsonObject
        assertNull(io.nekohasekai.sagernet.bg.projectRootDNSPolicy(config))
        assertTrue(config.getAsJsonObject("dns").has("final"))
        assertTrue(config.getAsJsonObject("dns").has("strategy"))
    }
    @Test fun dnsPolicyProjectionLeavesMalformedIndependentCacheInLegacyConfig() {
        val config = com.google.gson.JsonParser.parseString("""{"dns":{"final":"dns-remote","independent_cache":"yes","servers":[{"tag":"dns-remote"}]}}""").asJsonObject
        assertNull(io.nekohasekai.sagernet.bg.projectRootDNSPolicy(config))
        assertTrue(config.getAsJsonObject("dns").has("independent_cache"))
    }
    @Test fun staticDNSHostsArePassedAsRawSourceAndRemovedFromLegacySnapshot() {
        val client = source("bg/RootModuleClient.kt")
        assertTrue(client.contains("projectRootDNSHosts(config.asJsonObject, DataStore.dnsHosts)"))
        assertTrue(client.contains("rootDNSHosts != null -> 7"))
        assertTrue(client.contains("add(\"rootDnsHosts\", it)"))
        val config = com.google.gson.JsonParser.parseString(
            """{"dns":{"servers":[{"type":"hosts","tag":"dns-hosts","predefined":{"example.org":["1.1.1.1"]}},{"type":"local","tag":"local"}]}}"""
        ).asJsonObject
        val raw = "example.org 1.1.1.1\nexample.org 2001:4860:4860::8888\n"
        val projected = io.nekohasekai.sagernet.bg.projectRootDNSHosts(config, raw)
        assertEquals(raw, projected!!.get("raw").asString)
        val servers = config.getAsJsonObject("dns").getAsJsonArray("servers")
        assertEquals(1, servers.size())
        assertEquals("local", servers[0].asJsonObject.get("tag").asString)
    }
    @Test fun installerSelectionBlocksStaleConnectedStatsAndKeepsLegacyResponses() {
        val selected = RootModuleClient.parseResponse("""{"schemaVersion":1,"ok":true,"state":{"phase":"connected","installData":{"id":"0123456789abcdef0123456789abcdef","mode":"nodes"},"stats":{"tx":999}}}""")
        assertFalse(selected.connected);assertNull(selected.stats)
        assertEquals(io.nekohasekai.sagernet.bg.BaseService.State.Stopped,selected.state)
        assertEquals(io.nekohasekai.sagernet.bg.RootModuleDataUpdate.NODES_ONLY,selected.installData!!.mode)
        val old = RootModuleClient.parseResponse("""{"schemaVersion":1,"ok":true,"state":{"phase":"connected"}}""")
        assertTrue(old.connected);assertNull(old.installData)
    }
    @Test fun malformedOrUnsupportedInstallerSelectionCannotClearData() {
        for (choice in listOf("""{"id":"../unsafe","mode":"fresh"}""", """{"id":"0123456789abcdef0123456789abcdef","mode":"erase_all"}""", """{"mode":"nodes"}""")) {
            try {
                RootModuleClient.parseResponse("""{"schemaVersion":1,"ok":true,"state":{"phase":"stopped","installData":$choice}}""")
                fail("accepted malformed selection")
            } catch (_: IOException) {}
        }
    }
    @Test fun staleStoppedStatsAreNotAcceptedAsCurrentSuccess() {
        val parsed = RootModuleClient.parseResponse("""{"schemaVersion":1,"ok":true,"state":{"phase":"stopped","stats":{"tx":999},"revision":"new"}}""")
        assertFalse(parsed.connected); assertNull(parsed.stats)
    }
    @Test fun failureAndUnknownProtocolAreRejected() {
        for (json in listOf("""{"schemaVersion":2,"ok":true}""", """{"schemaVersion":1,"ok":false,"error":"revision_conflict"}""", """{"schemaVersion":1,"ok":true,"state":{"phase":"made_up_success"}}""")) {
            try { RootModuleClient.parseResponse(json); fail("accepted failure") } catch (_: IOException) {}
        }
    }
    @Test fun connectedStateIncludesModuleRevisionAndDoesNotRequireAppBox() {
        val parsed = RootModuleClient.parseResponse("""{"schemaVersion":1,"ok":true,"state":{"phase":"connected","revision":"r2","runningRevision":"r1","profileId":7,"profileName":"fixture"}}""")
        assertTrue(parsed.connected); assertEquals("r1", parsed.runningRevision); assertEquals(7L, parsed.profileId)
        val observer = source("bg/RootTunService.kt")
        assertFalse(observer.contains("ProcessBuilder")); assertFalse(observer.contains("noBackupFilesDir"))
        assertFalse(observer.contains("myPid()")); assertFalse(observer.contains("rootProcess"))
        assertTrue(observer.contains("RootModuleClient.call(\"status\")"))
        assertTrue(observer.contains("after.runningRevision != targetRevision"))
    }
    @Test fun moduleErrorsAreSafeAndHiddenUiDoesNotPoll() {
        val parsed = RootModuleClient.parseResponse("""{"schemaVersion":1,"ok":true,"state":{"phase":"failed","error":"cgroup_move_failed"}}""")
        assertFalse(parsed.connected); assertEquals("cgroup_move_failed", parsed.error)
        assertEquals("module_operation_failed", RootModuleClient.safeError(IOException("password=fake-secret")))
        val activity = source("ui/MainActivity.kt")
        assertTrue(activity.substringAfter("override fun onStop()").substringBefore("override fun onDestroy()").contains("connection.disconnect(this)"))
        val app = source("SagerNet.kt")
        assertFalse(app.contains("DefaultNetworkListener.start(this"))
        assertTrue(app.contains("val status = client.call(\"status\")"))
    }
    @Test fun rootOnlyRegistrationAndHistoricalSettingNormalization() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("BIND_VPN_SERVICE")); assertFalse(manifest.contains("VpnRequestActivity"))
        val store = source("database/DataStore.kt")
        assertTrue(store.contains("get() = Key.MODE_ROOT")); assertTrue(store.contains("putString(Key.SERVICE_MODE, Key.MODE_ROOT)"))
        val connection = source("bg/SagerConnection.kt")
        assertTrue(connection.contains("RootTunService::class.java")); assertFalse(connection.contains("MODE_PROXY ->"))
        val core = source("fmt/ConfigBuilder.kt")
        assertTrue(core.contains("val useTun = !forTest")); assertTrue(core.contains("auto_route = true"))
        assertTrue(core.contains("auto_redirect = if (isRootTun) true else null"))
    }
    @Test fun noAppPidOrGlobalBootScriptAndModulePreservesSnapshots() {
        val client = source("bg/RootModuleClient.kt")
        assertTrue(client.contains("192L * 1024 * 1024")); assertTrue(client.contains("config apply"))
        assertFalse(client.contains("myPid")); assertFalse(client.contains("librootbox.so"))
        val core = File("../libcore/rootbox.go").readText()
        assertTrue(core.contains("supervisorPID")); assertFalse(core.contains("parentPID"))
        val boot = source("BootReceiver.kt")
        assertFalse(boot.contains("SagerNet.startService()"))
        assertTrue(File("../rootmodule/package/service.sh").readText().contains("__internal boot"))
    }
}
