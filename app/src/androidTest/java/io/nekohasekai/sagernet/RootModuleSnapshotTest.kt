// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.bg.RootModuleSnapshot
import io.nekohasekai.sagernet.bg.proto.ProxyInstance
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real App producer without root authorization or starting TUN. @author 雾晚 */
class RootModuleSnapshotTest {
    @Test fun validatedRootRoutesIgnoreLaterDatabaseMutations() = runBlocking {
        val original = BackupRestore.parse(org.json.JSONObject(io.nekohasekai.sagernet.utils.BackupHelper.doBackup().toString(Charsets.UTF_8)))
        fun node(id: Long, host: String) = ProxyEntity(id = id, groupId = 99010).putBean(SOCKSBean().apply {
            initializeDefaultValues(); serverAddress = host; serverPort = 1080
        })
        val main = node(99011, "192.0.2.1")
        val target = node(99012, "192.0.2.2")
        val rule = RuleEntity(id = 99014, enabled = true, domains = "domain:frozen.invalid", outbound = target.id)
        try {
            BackupRestore.apply(BackupRestore.Plan(listOf(main, target), listOf(ProxyGroup(id = 99010, name = "fixture")), listOf(rule), null), true, true, false)
            DataStore.serviceMode = Key.MODE_ROOT
            DataStore.globalCustomConfig = ""; DataStore.globalMode = false; DataStore.enableClashAPI = false
            val snapshot = io.nekohasekai.sagernet.route.RootRouteBuildSnapshot.capture()
            assertTrue(snapshot.issues().isEmpty())
            SagerDatabase.rulesDao.updateRule(rule.copy(domains = "domain:changed.invalid", outbound = 0))
            SagerDatabase.proxyDao.updateProxy(target.apply { requireBean().serverAddress = "192.0.2.99" })
            val instance = ProxyInstance(main, rootRouteSnapshot = snapshot)
            try {
                instance.init()
                val config = RootModuleSnapshot(instance, "fixture-revision").build()["config"].asJsonObject
                val route = config["route"].asJsonObject["rules"].asJsonArray.first {
                    it.asJsonObject["domain_suffix"]?.toString()?.contains("frozen.invalid") == true
                }.asJsonObject
                val outbound = config["outbounds"].asJsonArray.first {
                    it.asJsonObject["tag"]?.asString == route["outbound"].asString
                }.asJsonObject
                assertEquals("192.0.2.2", outbound["server"].asString)
                assertFalse(config["route"].toString().contains("changed.invalid"))
            } finally { instance.close() }
        } finally { BackupRestore.apply(original, true, true, true) }
    }

    @Test fun savedRuleTargetChangesReachActualPortableSnapshot() = runBlocking {
        val original = BackupRestore.parse(org.json.JSONObject(io.nekohasekai.sagernet.utils.BackupHelper.doBackup().toString(Charsets.UTF_8)))
        fun node(id: Long, host: String) = ProxyEntity(id = id, groupId = 99010).putBean(SOCKSBean().apply {
            initializeDefaultValues(); serverAddress = host; serverPort = 1080
        })
        val main = node(99011, "192.0.2.1")
        val first = node(99012, "192.0.2.2")
        val second = node(99013, "192.0.2.3")
        val rule = RuleEntity(id = 99014, userOrder = 1, enabled = true, domains = "domain:fixture.invalid", outbound = first.id)
        val remoteFallback = RuleEntity(id = 99015, userOrder = 0, enabled = true,
            ruleset = "rssite:https://example.invalid/list.srs", outbound = -1)
        try {
            BackupRestore.apply(BackupRestore.Plan(listOf(main, first, second), listOf(ProxyGroup(id = 99010, name = "fixture")), listOf(rule, remoteFallback), null), true, true, false)
            DataStore.globalCustomConfig = ""; DataStore.globalMode = false; DataStore.enableClashAPI = false
            suspend fun config(): com.google.gson.JsonObject {
                val instance = ProxyInstance(main)
                try { instance.init(); return RootModuleSnapshot(instance, "fixture-revision").build()["config"].asJsonObject }
                finally { instance.close() }
            }
            fun target(config: com.google.gson.JsonObject): String = config["route"].asJsonObject["rules"].asJsonArray
                .first { it.asJsonObject["domain_suffix"]?.asJsonArray?.any { value -> value.asString == "fixture.invalid" } == true }
                .asJsonObject["outbound"].asString
            fun host(config: com.google.gson.JsonObject, tag: String) = config["outbounds"].asJsonArray
                .first { it.asJsonObject["tag"]?.asString == tag }.asJsonObject["server"].asString
            val before = config()
            val routeRules = before["route"].asJsonObject["rules"].asJsonArray.toList()
            val explicitIndex = routeRules.indexOfFirst { it.asJsonObject["domain_suffix"]?.toString()?.contains("fixture.invalid") == true }
            val remoteIndex = routeRules.indexOfFirst { it.asJsonObject["rule_set"] != null }
            assertTrue("Explicit user rule must precede its remote fallback", explicitIndex >= 0 && remoteIndex > explicitIndex)
            val privateIndex = routeRules.indexOfFirst { it.asJsonObject["ip_is_private"]?.asBoolean == true }
            if (privateIndex >= 0) assertTrue("Private fallback must not shadow user rules", privateIndex > remoteIndex)
            assertEquals("192.0.2.2", host(before, target(before)))
            SagerDatabase.rulesDao.updateRule(rule.copy(outbound = second.id))
            val after = config()
            assertEquals("192.0.2.3", host(after, target(after)))
            assertNotEquals(before.toString(), after.toString())
            SagerDatabase.rulesDao.updateRule(rule.copy(enabled = false))
            assertFalse(config()["route"].asJsonObject["rules"].toString().contains("fixture.invalid"))
        } finally { BackupRestore.apply(original, true, true, true) }
    }

    @Test fun quicSwitchControlsOnlyUdp443RejectInRootSnapshot() = runBlocking {
        val originalSettings = PublicDatabase.kvPairDao.all()
        val originalRules = SagerDatabase.rulesDao.allRules()
        val quicSettingKey = Key.BLOCK_QUIC
        val stableSettings = originalSettings.filterNot {
            it.key in setOf(
                quicSettingKey,
                Key.GLOBAL_CUSTOM_CONFIG,
                Key.GLOBAL_MODE,
                Key.ENABLE_CLASH_API,
                Key.SERVICE_MODE
            )
        } + listOf(
            KeyValuePair(Key.GLOBAL_CUSTOM_CONFIG).put(""),
            KeyValuePair(Key.GLOBAL_MODE).put(false),
            KeyValuePair(Key.ENABLE_CLASH_API).put(false),
            KeyValuePair(Key.SERVICE_MODE).put(Key.MODE_ROOT)
        )
        val bean = SOCKSBean().apply {
            initializeDefaultValues()
            serverAddress = "192.0.2.1"
            serverPort = 1080
        }
        val main = ProxyEntity(id = 99101, groupId = 99102).putBean(bean)
        val ordinaryRule = RuleEntity(
            id = 99103,
            userOrder = 1,
            enabled = true,
            domains = "domain:priority.invalid",
            outbound = 0
        )
        val customReject = RuleEntity(
            id = 99104,
            userOrder = 1,
            enabled = true,
            domains = "domain:fixture.invalid",
            port = "443",
            network = "udp",
            outbound = -2
        )

        fun saveState(blockQuic: Boolean?, rules: List<RuleEntity>) {
            val settings = stableSettings + listOfNotNull(
                blockQuic?.let { KeyValuePair(quicSettingKey).put(it) }
            )
            BackupRestore.apply(
                BackupRestore.Plan(null, null, rules, settings),
                profile = false,
                rule = true,
                setting = true
            )
        }

        suspend fun config(): com.google.gson.JsonObject {
            val instance = ProxyInstance(main)
            try {
                instance.init()
                return RootModuleSnapshot(instance, "quic-fixture").build()["config"].asJsonObject
            } finally {
                instance.close()
            }
        }

        fun routeRules(config: com.google.gson.JsonObject) =
            config["route"].asJsonObject["rules"].asJsonArray.map { it.asJsonObject }

        fun matchesNetworkAndPort(rule: com.google.gson.JsonObject, network: String, port: Int) =
            (rule["network"]?.asJsonArray?.any { it.asString == network } ?: true) &&
                (rule["port"]?.asJsonArray?.any { it.asInt == port } ?: true)

        fun udp443Rejects(config: com.google.gson.JsonObject) = routeRules(config).filter { rule ->
            rule["action"]?.asString == "reject" &&
                rule["network"]?.asJsonArray?.any { it.asString == "udp" } == true &&
                rule["port"]?.asJsonArray?.any { it.asInt == 443 } == true
        }

        try {
            saveState(blockQuic = null, rules = listOf(ordinaryRule))
            assertTrue("QUIC blocking defaults to enabled when no setting is saved", DataStore.blockQuic)
            val defaultConfig = config()
            val defaultRules = routeRules(defaultConfig)
            val automaticReject = udp443Rejects(defaultConfig).single()
            assertEquals(listOf("udp"), automaticReject["network"].asJsonArray.map { it.asString })
            assertEquals(listOf(443), automaticReject["port"].asJsonArray.map { it.asInt })
            assertFalse("Automatic QUIC blocking must not match TCP/443", matchesNetworkAndPort(automaticReject, "tcp", 443))
            assertFalse("Automatic QUIC blocking must not match UDP/80", matchesNetworkAndPort(automaticReject, "udp", 80))
            assertEquals("reject", automaticReject["action"].asString)
            val userRuleIndex = defaultRules.indexOfFirst {
                it["domain_suffix"]?.asJsonArray?.any { value -> value.asString == "priority.invalid" } == true
            }
            assertTrue("Automatic QUIC blocking must precede user routing", userRuleIndex > defaultRules.indexOf(automaticReject))

            saveState(blockQuic = false, rules = emptyList())
            assertFalse("Saved disabled QUIC blocking must be read back as false", DataStore.blockQuic)
            assertTrue("Disabling QUIC blocking must remove the automatic rule", udp443Rejects(config()).isEmpty())

            saveState(blockQuic = false, rules = listOf(customReject))
            assertFalse("Saved disabled QUIC blocking must remain false with a custom rule", DataStore.blockQuic)
            val disabledWithCustomRule = udp443Rejects(config())
            assertEquals("The user's reject rule remains without a duplicate global rule", 1, disabledWithCustomRule.size)
            assertTrue(
                disabledWithCustomRule.single()["domain_suffix"]?.asJsonArray
                    ?.any { it.asString == "fixture.invalid" } == true
            )
        } finally {
            BackupRestore.apply(
                BackupRestore.Plan(null, null, originalRules, originalSettings),
                profile = false,
                rule = true,
                setting = true
            )
        }
    }

    @Test fun actualGeneratorExportsPortableRootSnapshotAndPreservesDatabase() = runBlocking {
        val settings = PublicDatabase.kvPairDao.all()
        val rules = SagerDatabase.rulesDao.allRules()
        val bean = SOCKSBean().apply { initializeDefaultValues(); serverAddress = "192.0.2.1"; serverPort = 1080 }
        val instance = ProxyInstance(ProxyEntity(id = 99001, groupId = 99002).putBean(bean))
        try {
            DataStore.globalCustomConfig = ""
            DataStore.enableClashAPI = false
            DataStore.serviceMode = "vpn" // Historical preference normalizes to Root, no VPN authorization.
            instance.init()
            val snapshot = RootModuleSnapshot(instance, "").build()
            assertEquals(1, snapshot["schemaVersion"].asInt)
            assertEquals(99001L, snapshot["profileId"].asLong)
            assertEquals(Key.MODE_ROOT, DataStore.serviceMode)
            assertTrue(snapshot["files"].isJsonObject)
            assertTrue(snapshot["plugins"].isJsonArray)
            val config = snapshot["config"].toString()
            assertFalse(config.contains("/data/user/")); assertFalse(config.contains("/data/data/"))
            assertTrue(snapshot["config"].asJsonObject["inbounds"].asJsonArray.any {
                it.asJsonObject["type"]?.asString == "tun" && it.asJsonObject["auto_route"].asBoolean
            })
            assertEquals(rules, SagerDatabase.rulesDao.allRules())
        } finally {
            instance.close()
            BackupRestore.apply(BackupRestore.Plan(null, null, null, settings), false, false, true)
        }
    }
}
