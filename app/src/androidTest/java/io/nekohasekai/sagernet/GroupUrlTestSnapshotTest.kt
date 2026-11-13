// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.bg.RootModuleSnapshot
import io.nekohasekai.sagernet.bg.proto.ProxyInstance
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

class GroupUrlTestSnapshotTest {
    @Test
    fun groupUrlInheritanceAndLoadBalanceUseCurrentGlobalUrl() = runBlocking {
        val original = BackupRestore.parse(
            org.json.JSONObject(io.nekohasekai.sagernet.utils.BackupHelper.doBackup().toString(Charsets.UTF_8))
        )
        val originalConnectionTestURL = DataStore.connectionTestURL
        val groupId = 99120L
        val mainId = 99121L
        val peerId = 99122L
        fun node(id: Long, host: String) = ProxyEntity(id = id, groupId = groupId).putBean(
            SOCKSBean().apply { initializeDefaultValues(); serverAddress = host; serverPort = 1080 }
        )
        val main = node(mainId, "192.0.2.21")
        val peer = node(peerId, "192.0.2.22")
        try {
            BackupRestore.apply(
                BackupRestore.Plan(listOf(main, peer), listOf(ProxyGroup(id = groupId, name = "url fixture")), null, null),
                true, true, false
            )
            DataStore.globalCustomConfig = ""
            DataStore.setGroupUrlTestUrl(groupId, SagerNet.application.getString(R.string.default_group_test_url))
            DataStore.setGroupUrlTest(groupId, true)

            suspend fun snapshot(): JsonObject {
                val instance = ProxyInstance(main)
                return try { instance.init(); RootModuleSnapshot(instance, "group-url-fixture").build() }
                finally { instance.close() }
            }
            fun config(bundle: JsonObject) = bundle["config"].asJsonObject
            fun healthCheckUrl(bundle: JsonObject): String {
                val encoded = bundle["files"].asJsonObject["files/health_check.json"].asString
                return JsonParser.parseString(String(Base64.getDecoder().decode(encoded), Charsets.UTF_8))
                    .asJsonObject["url"].asString
            }
            fun proxyOutbound(config: JsonObject): JsonObject = config["outbounds"].asJsonArray.first {
                it.asJsonObject["tag"]?.asString == "proxy"
            }.asJsonObject
            fun mixedInboundProbeRule(config: JsonObject): JsonObject = config["route"].asJsonObject["rules"].asJsonArray
                .firstOrNull {
                    it.asJsonObject["inbound"]?.asJsonArray?.any { inbound -> inbound.asString == "mixed-in" } == true
                }?.asJsonObject ?: throw AssertionError("Root mixed-inbound app-level probe route rule is absent")

            DataStore.connectionTestURL = "https://global-one.example/204"
            val globalOneOutbound = proxyOutbound(config(snapshot()))
            assertEquals("urltest", globalOneOutbound["type"]?.asString)
            assertEquals(DataStore.connectionTestURL, globalOneOutbound["url"].asString)
            DataStore.connectionTestURL = "https://global-two.example/204"
            val globalTwoOutbound = proxyOutbound(config(snapshot()))
            assertEquals("urltest", globalTwoOutbound["type"]?.asString)
            assertEquals(DataStore.connectionTestURL, globalTwoOutbound["url"].asString)

            DataStore.setGroupUrlTestUrl(groupId, "https://group.example/204")
            val groupUrlBundle = snapshot()
            val groupUrlSnapshot = config(groupUrlBundle)
            val groupUrlOutbound = proxyOutbound(groupUrlSnapshot)
            assertEquals("urltest", groupUrlOutbound["type"]?.asString)
            assertEquals("https://group.example/204", groupUrlOutbound["url"].asString)
            assertEquals(
                "global-two.example",
                mixedInboundProbeRule(groupUrlSnapshot)["domain"].asJsonArray[0].asString
            )
            assertEquals("https://global-two.example/204", healthCheckUrl(groupUrlBundle))

            DataStore.setGroupUrlTest(groupId, false)
            DataStore.setGroupLoadBalance(groupId, true)
            DataStore.setGroupUrlTestUrl(groupId, SagerNet.application.getString(R.string.default_group_test_url))
            DataStore.connectionTestURL = "https://global-three.example/204"
            val globalThreeOutbound = proxyOutbound(config(snapshot()))
            assertEquals("loadbalance", globalThreeOutbound["type"]?.asString)
            assertEquals(DataStore.connectionTestURL, globalThreeOutbound["url"].asString)
            DataStore.setGroupUrlTestUrl(groupId, "https://group-lb.example/204")
            val groupLoadBalanceOutbound = proxyOutbound(config(snapshot()))
            assertEquals("loadbalance", groupLoadBalanceOutbound["type"]?.asString)
            assertEquals("https://group-lb.example/204", groupLoadBalanceOutbound["url"].asString)
        } finally {
            DataStore.setGroupUrlTest(groupId, false)
            DataStore.setGroupLoadBalance(groupId, false)
            DataStore.setGroupUrlTestUrl(groupId, "")
            DataStore.connectionTestURL = originalConnectionTestURL
            BackupRestore.apply(original, true, true, true)
        }
    }
}
