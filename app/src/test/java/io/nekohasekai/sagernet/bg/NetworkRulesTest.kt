package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkRulesTest {
    @Test
    fun parseAndSerializeRoundTrip() {
        val json = """{"enabled":true,"rules":[
            {"action":"direct","ssid":"Office Wi-Fi, 5G"},
            {"action":"suspend","gateway":"192.0.2.1"},
            {"action":"keep","egress":"not_wifi"},
            {"action":"suspend","cidr":"198.51.100.0/24"}
        ],"appPackage":"io.nekohasekai.sagernet","userId":0}"""
        val policy = NetworkRules.parse(json).getOrThrow()
        assertTrue(policy.enabled)
        assertEquals(4, policy.rules.size)
        assertEquals(NetworkRuleAction.DIRECT, policy.rules[0].action)
        assertEquals("Office Wi-Fi, 5G", policy.rules[0].ssid)
        assertEquals(NetworkRuleAction.SUSPEND, policy.rules[1].action)
        assertEquals("192.0.2.1", policy.rules[1].gateway)
        assertEquals("not_wifi", policy.rules[2].egress)
        val back = NetworkRules.parse(NetworkRules.serialize(policy)).getOrThrow()
        assertEquals(policy, back)
    }

    @Test
    fun rejectsInvalidPolicies() {
        listOf(
            // 非法动作
            """{"enabled":true,"rules":[{"action":"pause","ssid":"x"}],"appPackage":"io.nekohasekai.sagernet","userId":0}""",
            // 无匹配器
            """{"enabled":true,"rules":[{"action":"suspend"}],"appPackage":"io.nekohasekai.sagernet","userId":0}""",
            // 非法 egress
            """{"enabled":true,"rules":[{"action":"direct","egress":"cellular"}],"appPackage":"io.nekohasekai.sagernet","userId":0}""",
            // 非法 CIDR
            """{"enabled":true,"rules":[{"action":"direct","cidr":"host.example"}],"appPackage":"io.nekohasekai.sagernet","userId":0}""",
            // 6 条规则超上限
            """{"enabled":true,"rules":[
                {"action":"keep","ssid":"a"},{"action":"keep","ssid":"b"},{"action":"keep","ssid":"c"},
                {"action":"keep","ssid":"d"},{"action":"keep","ssid":"e"},{"action":"keep","ssid":"f"}
            ],"appPackage":"io.nekohasekai.sagernet","userId":0}""",
            // 启用但无 userId
            """{"enabled":true,"rules":[],"appPackage":"io.nekohasekai.sagernet"}""",
        ).forEach { assertTrue(it, NetworkRules.parse(it).isFailure) }
        assertTrue(NetworkRules.parse("").getOrThrow().rules.isEmpty())
    }

    @Test
    fun ssidKeepsSpacesCaseAndCommas() {
        val policy = NetworkRules.parse(
            """{"enabled":true,"rules":[{"action":"direct","ssid":"Office Wi-Fi, 5G"}],"appPackage":"io.nekohasekai.sagernet","userId":0}"""
        ).getOrThrow()
        assertEquals("Office Wi-Fi, 5G", policy.rules[0].ssid)
    }

    @Test
    fun migrateFromSmartAutoStopPreservesOrderAsSuspend() {
        val migrated = NetworkRules.migrateFromSmartAutoStop(
            enabled = true,
            rulesText = "192.0.2.9; GATEWAY:2001:db8::/32",
            appPackage = "io.nekohasekai.sagernet",
            userId = 10,
        ).getOrThrow()
        assertTrue(migrated.enabled)
        assertEquals(2, migrated.rules.size)
        assertTrue(migrated.rules.all { it.action == NetworkRuleAction.SUSPEND })
        assertEquals("192.0.2.9", migrated.rules[0].cidr)
        assertEquals("2001:db8::/32", migrated.rules[1].gateway)
        assertNull(migrated.rules[1].cidr)
    }

    @Test
    fun migrateFailsWithoutDroppingLegacyData() {
        val result = NetworkRules.migrateFromSmartAutoStop(
            enabled = true,
            rulesText = "host.example",
            appPackage = "io.nekohasekai.sagernet",
            userId = 0,
        )
        assertTrue(result.isFailure)
    }

    @Test
    fun newRuleDefaultsToKeep() {
        assertEquals(NetworkRuleAction.KEEP, NetworkRules.defaultNewRule().action)
        assertFalse(NetworkRules.defaultNewRule().hasMatcher())
    }
}
