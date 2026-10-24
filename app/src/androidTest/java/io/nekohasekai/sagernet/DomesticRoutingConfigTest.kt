// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.bg.RootModuleSnapshot
import io.nekohasekai.sagernet.fmt.RootInboundBackend
import io.nekohasekai.sagernet.bg.proto.ProxyInstance
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.route.DomesticRoutingPreset
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Actual Room, generator and portable snapshot; no real nodes or network requests. @author 雾晚 */
class DomesticRoutingConfigTest {
    @Test fun presetIsRepeatableAndAppOverridesDnsAndGeoFallbacks() = runBlocking {
        val original = BackupRestore.parse(JSONObject(io.nekohasekai.sagernet.utils.BackupHelper.doBackup().toString(Charsets.UTF_8)))
        val oldDashboardMode = DataStore.dashboardMode
        val oldGlobalMode = DataStore.globalMode
        val main = ProxyEntity(id = 99201, groupId = 99200).putBean(SOCKSBean().apply {
            initializeDefaultValues(); serverAddress = "192.0.2.1"; serverPort = 1080
        })
        val appPackage = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.packageName
        val oldDomain = RuleEntity(id = 99202, name = "old name", userOrder = 1, domains = "geosite-cn", outbound = -1)
        val selectedApp = RuleEntity(id = 99203, userOrder = 3, enabled = true, packages = setOf(appPackage), outbound = 0)
        val explicit = RuleEntity(id = 99204, userOrder = 4, enabled = true, domains = "domain:fixture.invalid", outbound = 0)
        try {
            BackupRestore.apply(BackupRestore.Plan(listOf(main), listOf(ProxyGroup(id = 99200, name = "fixture")),
                listOf(oldDomain, selectedApp, explicit), null), true, true, false)
            DomesticRoutingPreset.apply("domestic", "IPs")
            val once = SagerDatabase.rulesDao.allRules()
            DomesticRoutingPreset.apply("domestic", "IPs")
            assertEquals(once, SagerDatabase.rulesDao.allRules())
            assertEquals(4, once.size)
            assertEquals(oldDomain.copy(enabled = true), once.first { it.id == oldDomain.id })
            assertEquals(selectedApp, once.first { it.id == selectedApp.id })
            DataStore.globalCustomConfig = ""; DataStore.globalMode = false
            DataStore.enableDnsRouting = true; DataStore.proxyApps = false
            DataStore.enableClashAPI = false; DataStore.resolveDestination = false
            for (fakeIp in listOf(false, true)) {
                DataStore.enableFakeDns = fakeIp
                val instance = ProxyInstance(main)
                try {
                    instance.init()
                    val snapshot = RootModuleSnapshot(instance, "fixture-revision", RootInboundBackend.TUN).build()
                    val config = snapshot["config"].asJsonObject
                    val routes = config["route"].asJsonObject["rules"].asJsonArray.toList().map { it.asJsonObject }
                    val app = routes.indexOfFirst { it["rules"]?.toString()?.contains(appPackage) == true }
                    val domain = routes.indexOfFirst { it["rule_set"]?.toString()?.contains("geosite-cn") == true }
                    val ips = routes.indexOfFirst { it["rule_set"]?.toString()?.contains("geoip:cn") == true }
                    val literal = routes.indexOfFirst { it["domain_suffix"]?.toString()?.contains("fixture.invalid") == true }
                    val resolve = routes.indexOfFirst { it["action"]?.asString == "resolve" }
                    assertTrue(app >= 0 && literal > app && domain > literal)
                    assertTrue(resolve > domain && ips > resolve)
                    assertEquals("3s", routes[resolve]["timeout"].asString)
                    assertTrue(routes.none { it.has("match_only") })
                    assertEquals("bypass", routes[domain]["outbound"].asString)
                    assertEquals("bypass", routes[ips]["outbound"].asString)
                    assertEquals(routes[app]["outbound"].asString, config["route"].asJsonObject["final"].asString)
                    val dns = config["dns"].asJsonObject
                    val dnsRules = dns["rules"].asJsonArray.toList().map { it.asJsonObject }
                    val appDns = dnsRules.indexOfFirst { it["rules"]?.toString()?.contains(appPackage) == true }
                    val domainDns = dnsRules.indexOfFirst { it["rule_set"]?.toString()?.contains("geosite-cn") == true }
                    assertTrue(appDns >= 0 && domainDns > appDns)
                    assertEquals(if (fakeIp) "dns-fake" else "dns-remote", dnsRules[appDns]["server"].asString)
                    assertEquals("dns-direct", dnsRules[domainDns]["server"].asString)
                    assertEquals("dns-remote", dns["final"].asString)
                    assertEquals(once, SagerDatabase.rulesDao.allRules())
                } finally { instance.close() }
            }

            // 矩阵验证：runtime 模式分支、安全优先序、export 契约与 forTest 测速隔离
            val testCases = listOf(
                Triple(false, 0, "rule"),
                Triple(true, 0, "rule"),
                Triple(false, 1, "global"),
                Triple(true, 1, "global"),
                Triple(false, 2, "direct"),
                Triple(true, 2, "direct"),
                Triple(false, -1, "rule"),
                Triple(true, 99, "rule")
            )
            val expectedLoopbackCidrs = setOf(
                "127.0.0.0/8",
                "::1/128",
                "224.0.0.0/4",
                "169.254.0.0/16",
                "fe80::/10"
            )

            for ((legacyGlobal, dashMode, expectedDefaultMode) in testCases) {
                DataStore.globalMode = legacyGlobal
                DataStore.dashboardMode = dashMode

                // 1. 运行时 (!forTest && !forExport)
                val runtimeJson = JSONObject(buildConfig(main, forTest = false, forExport = false).config)
                val exp = runtimeJson.optJSONObject("experimental")
                assertNotNull("Runtime must configure experimental block", exp)
                val clashApi = exp?.optJSONObject("clash_api")
                assertNotNull("Runtime must configure clash_api", clashApi)
                assertEquals("default_mode must map correctly", expectedDefaultMode, clashApi?.optString("default_mode"))

                val route = runtimeJson.getJSONObject("route")
                val rules = route.getJSONArray("rules")
                val ruleObjects = (0 until rules.length()).map { rules.getJSONObject(it) }

                // 安全规则断言：必须严格存在且先于 clash_mode 条件规则
                val hijackDnsIndex = ruleObjects.indexOfFirst { rule ->
                    rule.optString("action") == "hijack-dns" &&
                        rule.optJSONArray("port")?.let { arr -> (0 until arr.length()).any { arr.getInt(it) == 53 } } == true
                }
                assertTrue("Runtime must contain hijack-dns rule on port 53", hijackDnsIndex >= 0)

                val protocolDnsIndex = ruleObjects.indexOfFirst { rule ->
                    rule.optString("action") == "hijack-dns" &&
                        rule.optJSONArray("protocol")?.let { arr -> (0 until arr.length()).any { arr.getString(it) == "dns" } } == true
                }
                assertTrue("Runtime must contain protocol dns rule", protocolDnsIndex >= 0)

                val loopbackIndex = ruleObjects.indexOfFirst { rule ->
                    if (rule.optString("outbound") != "direct") return@indexOfFirst false
                    val cidrs = rule.optJSONArray("ip_cidr") ?: return@indexOfFirst false
                    val set = (0 until cidrs.length()).map { cidrs.getString(it) }.toSet()
                    set.containsAll(expectedLoopbackCidrs)
                }
                assertTrue("Runtime must contain complete direct loopback/link-local/multicast safety rule", loopbackIndex >= 0)

                val globalModeIndex = ruleObjects.indexOfFirst { it.optString("clash_mode") == "global" }
                val directModeIndex = ruleObjects.indexOfFirst { it.optString("clash_mode") == "direct" }
                val userGeositeIndex = ruleObjects.indexOfFirst { it.optJSONArray("rule_set")?.toString()?.contains("geosite-cn") == true }
                val userExplicitIndex = ruleObjects.indexOfFirst { it.optJSONArray("domain_suffix")?.toString()?.contains("fixture.invalid") == true }

                assertTrue("clash_mode=global rule must exist", globalModeIndex >= 0)
                assertTrue("clash_mode=direct rule must exist", directModeIndex >= 0)
                assertTrue("User geosite-cn rule must remain in runtime config regardless of globalMode", userGeositeIndex >= 0)
                assertTrue("User explicit domain rule must remain in runtime config", userExplicitIndex >= 0)

                assertEquals("proxy", ruleObjects[globalModeIndex].getString("outbound"))
                assertEquals("direct", ruleObjects[directModeIndex].getString("outbound"))

                // 强制顺序验证：安全与回环规则必须严格先于两个 clash_mode 条件
                assertTrue("hijack-dns must precede clash_mode=global", hijackDnsIndex < globalModeIndex)
                assertTrue("hijack-dns must precede clash_mode=direct", hijackDnsIndex < directModeIndex)
                assertTrue("protocol=dns must precede clash_mode=global", protocolDnsIndex < globalModeIndex)
                assertTrue("protocol=dns must precede clash_mode=direct", protocolDnsIndex < directModeIndex)
                assertTrue("loopback safety must precede clash_mode=global", loopbackIndex < globalModeIndex)
                assertTrue("loopback safety must precede clash_mode=direct", loopbackIndex < directModeIndex)

                // clash_mode 条件规则必须置于用户业务规则之前
                assertTrue("clash_mode=global must precede explicit user rule", globalModeIndex < userExplicitIndex)
                assertTrue("clash_mode=direct must precede explicit user rule", directModeIndex < userExplicitIndex)
                assertTrue("clash_mode=global must precede geosite rule", globalModeIndex < userGeositeIndex)
                assertTrue("clash_mode=direct must precede geosite rule", directModeIndex < userGeositeIndex)

                // 2. 导出场景 (forExport = true)
                val exportJson = JSONObject(buildConfig(main, forTest = false, forExport = true).config)
                val exportExp = exportJson.optJSONObject("experimental")
                val exportClashApi = exportExp?.optJSONObject("clash_api")
                assertFalse("Export without allowAccess/enableClashAPI must not set default_mode", exportClashApi?.has("default_mode") == true)

                val exportRules = exportJson.getJSONObject("route").getJSONArray("rules")
                val exportRuleObjects = (0 until exportRules.length()).map { exportRules.getJSONObject(it) }
                val exportGeosite = exportRuleObjects.any { it.optJSONArray("rule_set")?.toString()?.contains("geosite-cn") == true }

                if (legacyGlobal) {
                    assertFalse("Legacy export globalMode must bypass user rules", exportGeosite)
                    val tunInRule = exportRuleObjects.firstOrNull { it.optJSONArray("inbound")?.toString()?.contains("tun-in") == true }
                    assertNotNull("Legacy export globalMode must route tun-in to mainProxyTag", tunInRule)
                } else {
                    assertTrue("Export with globalMode=false must retain user geosite-cn rules", exportGeosite)
                }

                // 3. 测试场景 (forTest = true, 测速与探测隔离契约)
                val forTestJson = JSONObject(buildConfig(main, forTest = true, forExport = false).config)
                assertFalse("forTest must not configure experimental block", forTestJson.has("experimental"))
                val testRules = forTestJson.getJSONObject("route").getJSONArray("rules")
                val testRuleObjects = (0 until testRules.length()).map { testRules.getJSONObject(it) }
                assertFalse("forTest must not generate clash_mode rules", testRuleObjects.any { it.has("clash_mode") })
                assertFalse("forTest must not contain user geosite rules",
                    testRuleObjects.any { it.optJSONArray("rule_set")?.toString()?.contains("geosite-cn") == true })
                assertFalse("forTest must not contain user explicit domain rules",
                    testRuleObjects.any { it.optJSONArray("domain_suffix")?.toString()?.contains("fixture.invalid") == true })

                // 验证无论 legacy globalMode 如何切换，forTest 的 rules 序列结构保持完全一致
                DataStore.globalMode = !legacyGlobal
                val forTestBaselineJson = JSONObject(buildConfig(main, forTest = true, forExport = false).config)
                DataStore.globalMode = legacyGlobal
                assertEquals("forTest route rules must stay completely identical regardless of legacy globalMode",
                    forTestJson.getJSONObject("route").getJSONArray("rules").toString(),
                    forTestBaselineJson.getJSONObject("route").getJSONArray("rules").toString()
                )
            }
        } finally {
            DataStore.dashboardMode = oldDashboardMode
            DataStore.globalMode = oldGlobalMode
            BackupRestore.apply(original, true, true, true)
        }
    }
}
