package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.bg.RootModuleSnapshot
import io.nekohasekai.sagernet.bg.proto.ProxyInstance
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.utils.BackupHelper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ReferenceModeConfigTest {
    @Test fun selectedModeAndDnsPolicyReachTheRealCoreSnapshot() = runBlocking {
        val original = BackupRestore.parse(PortableBackup.document(BackupHelper.doBackup()))
        val profile = ProxyEntity(id = 97991, groupId = 97990).putBean(SOCKSBean().apply {
            initializeDefaultValues(); serverAddress = "192.0.2.1"; serverPort = 1080
        })
        try {
            BackupRestore.apply(BackupRestore.Plan(listOf(profile),
                listOf(ProxyGroup(id = profile.groupId, name = "mode-fixture")), emptyList(), null), true, true, false)
            DataStore.globalCustomConfig = ""
            DataStore.globalMode = false
            DataStore.proxyApps = false
            for ((index, mode) in listOf("rule", "global", "direct").withIndex()) {
                DataStore.dashboardMode = index
                val instance = ProxyInstance(profile)
                try {
                    instance.init()
                    val config = RootModuleSnapshot(instance, "mode-fixture").build()["config"].asJsonObject
                    assertEquals(mode, config["experimental"].asJsonObject["clash_api"].asJsonObject["default_mode"].asString)
                    val routes = config["route"].asJsonObject["rules"].asJsonArray.map { it.asJsonObject }
                    val modeRules = routes.filter { it.has("clash_mode") }.associateBy { it["clash_mode"].asString }
                    assertEquals(setOf("direct", "global"), modeRules.keys)
                    val tags = config["outbounds"].asJsonArray.map { it.asJsonObject["tag"].asString }.toSet()
                    modeRules.values.forEach { assertTrue(it["outbound"].asString in tags) }
                    val dns = config["dns"].asJsonObject["rules"].asJsonArray.map { it.asJsonObject }
                    assertEquals("dns-direct", dns.first { it["clash_mode"]?.asString == "direct" }["server"].asString)
                    assertEquals("dns-remote", dns.first { it["clash_mode"]?.asString == "global" }["server"].asString)
                } finally { instance.close() }
            }
        } finally { BackupRestore.apply(original, true, true, true) }
    }
}
