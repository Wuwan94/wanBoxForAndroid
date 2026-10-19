// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.bg.ModuleApiClient
import io.nekohasekai.sagernet.bg.ModuleConfigIntent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配置意图的依赖拆分：只按 profileTagMap 的 tag 归属划分，绝不按协议类型猜测。
 *
 * 这是核心回归点：preview.11 把整段 outbounds 丢掉，导致 route/dns 引用的
 * direct/bypass、链式 detour、顶层 endpoints 与 log 全部缺失，真实核心校验必然失败。
 * @author 雾晚
 */
class ModuleConfigIntentDependencyTest {

    private fun config(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    private fun tags(array: JsonArray): List<String> =
        (0 until array.size()).map { i -> array[i].asJsonObject.get("tag").asString }

    private fun sampleConfig(): JsonObject = config(
        """
        {
          "log": {"level": "warn", "timestamp": true},
          "outbounds": [
            {"type": "shadowsocks", "tag": "node-1", "server": "1.1.1.1"},
            {"type": "direct", "tag": "direct"},
            {"type": "direct", "tag": "bypass", "detour": "direct"},
            {"type": "wireguard", "tag": "node-2", "peers": []},
            {"type": "selector", "tag": "proxy", "outbounds": ["node-1", "node-2", "direct"]}
          ],
          "endpoints": [
            {"type": "wireguard", "tag": "node-3", "peers": []},
            {"type": "wireguard", "tag": "extra-endpoint", "peers": []}
          ],
          "route": {"rules": [{"outbound": "bypass"}]}
        }
        """.trimIndent(),
    )

    @Test fun auxiliaryOutboundsKeepOrderAndExcludeNodesAndSelector() {
        val dependencies = ModuleConfigIntent.splitDependencies(
            sampleConfig(),
            setOf("node-1", "node-2", "node-3"),
        )
        // 顺序与配置一致（链式 detour 依赖声明顺序），节点与模块自建 selector 都被剔除。
        assertEquals(listOf("direct", "bypass"), tags(dependencies.auxiliary))
        // 链式 detour 的引用必须原样保留，否则模块侧拼出的配置会指向不存在的出站。
        assertEquals("direct", dependencies.auxiliary[1].asJsonObject["detour"].asString)
    }

    @Test fun endpointNodesAreNotSentAsPassthroughDependencies() {
        val dependencies = ModuleConfigIntent.splitDependencies(
            sampleConfig(),
            setOf("node-1", "node-2", "node-3"),
        )
        // node-3 是端点形态的节点，已经随 /v1/nodes 下发；重复上送会让模块看到重复 tag。
        assertEquals(listOf("extra-endpoint"), tags(dependencies.endpoints))
    }

    @Test fun sameProtocolTypeIsSplitByTagNotByType() {
        // direct 类型同时出现在节点与辅助出站里：按 tag 划分时它必须留在节点侧，
        // 其余 tag 不受影响（此处只有 direct 是节点，node-1/node-2 才是辅助）。
        val dependencies = ModuleConfigIntent.splitDependencies(
            sampleConfig(),
            setOf("direct"),
        )
        assertEquals(listOf("node-1", "bypass", "node-2"), tags(dependencies.auxiliary))
        assertFalse(tags(dependencies.auxiliary).contains("direct"))
    }

    @Test fun emptyConfigYieldsEmptyDependencies() {
        val dependencies = ModuleConfigIntent.splitDependencies(config("{}"), emptySet())
        assertTrue(dependencies.auxiliary.size() == 0)
        assertTrue(dependencies.endpoints.size() == 0)
    }

    @Test fun selectorOnlyConfigDoesNotProduceSelfReferencingOutbound() {
        val dependencies = ModuleConfigIntent.splitDependencies(
            config("""{"outbounds":[{"type":"selector","tag":"proxy","outbounds":[]}]}"""),
            emptySet(),
        )
        // 模块按 store 节点重建 selector；App 再送一份会撞 tag（node_tag_duplicate）。
        assertTrue(dependencies.auxiliary.size() == 0)
    }

    private fun node(id: Long, endpoint: Boolean = false) = ModuleApiClient.ModuleNode(
        id = id,
        type = "shadowsocks",
        name = "node-$id",
        group = null,
        subscriptionId = 0,
        outbound = JSONObject().put("type", "shadowsocks").put("tag", "node-$id"),
        updatedAt = 0,
        endpoint = endpoint,
    )

    @Test fun missingProfileIsUpsertedAloneWhenModuleHasOtherNodes() {
        // 模块已有 1、2 号节点，当前选中 3 号：只补 3 号一条，绝不整表回写，
        // 否则用户在模块里新增/编辑过的节点会被 App 的旧配置冲掉。
        val missing = ModuleConfigIntent.missingProfileNode(
            known = listOf(node(1), node(2)),
            migrated = listOf(node(3), node(4)),
            profileId = 3,
        )
        assertEquals(3L, missing?.id)
    }

    @Test fun nothingIsUpsertedWhenModuleAlreadyKnowsTheProfile() {
        val missing = ModuleConfigIntent.missingProfileNode(
            known = listOf(node(1), node(2)),
            migrated = listOf(node(1), node(2)),
            profileId = 2,
        )
        assertEquals(null, missing)
    }

    @Test fun emptyModuleStoreIsLeftToBulkMigration() {
        // 模块还没有节点：交给整表迁移，不走单条补写（单条补写会漏掉链式节点）。
        assertEquals(
            null,
            ModuleConfigIntent.missingProfileNode(emptyList(), listOf(node(1)), 1),
        )
    }

    @Test fun unknownProfileIsNotInvented() {
        // App 侧也没还原出这个节点：返回 null，让模块如实报 profile_missing，
        // 而不是拿别的节点顶上（选 A 实际走 B 且无提示比直接报错危险得多）。
        assertEquals(
            null,
            ModuleConfigIntent.missingProfileNode(listOf(node(1)), listOf(node(2)), 99),
        )
        assertEquals(
            null,
            ModuleConfigIntent.missingProfileNode(listOf(node(1)), listOf(node(1)), 0),
        )
    }

    @Test fun endpointProfileIsUpsertedWithItsFlag() {
        // 当前 profile 是端点形态：照补（节点真值要进模块），端点标记不能丢。
        val missing = ModuleConfigIntent.missingProfileNode(
            known = listOf(node(1)),
            migrated = listOf(node(2, endpoint = true)),
            profileId = 2,
        )
        assertEquals(2L, missing?.id)
        assertEquals(true, missing?.endpoint)
    }

    @Test fun entriesWithoutTagAreKeptAsDependencies() {
        // 无 tag 的出站不可能被 route 引用，但也不能丢：模块侧会按固定错误码拒绝。
        val dependencies = ModuleConfigIntent.splitDependencies(
            config("""{"outbounds":[{"type":"direct"},{"type":"block","tag":"block"}]}"""),
            setOf("node-1"),
        )
        assertEquals(2, dependencies.auxiliary.size())
    }
}
