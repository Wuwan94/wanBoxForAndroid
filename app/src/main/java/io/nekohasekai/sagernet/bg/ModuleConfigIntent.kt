// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.bg

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.nekohasekai.sagernet.bg.proto.ProxyInstance
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * 构建下发给模块的「配置意图」（/v1/config 的请求体）。
 *
 * 与 preview.5 的 RootModuleSnapshot 关键区别：
 * - 不再把节点 outbounds 强推给模块，节点真值由模块 store/nodes.json 权威；
 * - 但**不能只发 route/dns**：路由与 DNS 里引用的 direct/bypass 出站、链式 detour、
 *   顶层 endpoints、顶层 log 都是必需依赖，缺一样真实核心校验就会失败。
 *   因此按 profileTagMap 的 tag 集合把 outbounds 分成「节点」与「辅助」两组：
 *   节点走 /v1/nodes，辅助与顶层 endpoints/log 随意图上送。
 *
 * 划分只依据 tag 归属，绝不按协议类型猜测：同一个 `direct` 类型既可能是节点
 * （用户把 direct 当成一个可选节点），也可能是辅助出站，只有 tag 能区分。
 *
 * 资源收集（assets / yacd / plugins）完全复用 RootModuleSnapshot.build()。
 *
 * @author 雾晚
 */
object ModuleConfigIntent {

    /** 模块自行重建的 selector tag，必须与 rootmodule 的 selectorTag("proxy") 一致。 */
    private const val SELECTOR_TAG = "proxy"

    /** 依赖拆分结果：辅助出站 + 非节点的透传 endpoints。 */
    data class Dependencies(val auxiliary: JsonArray, val endpoints: JsonArray)

    /**
     * 按 tag 归属拆分 outbounds / endpoints。
     *
     * 属于节点集合（profileTagMap 的 tag）的条目一律不在这里出现：它们走 /v1/nodes，
     * 由模块按 store 权威数据写回 outbounds 或顶层 endpoints。模块自己重建的 selector
     * 也剔除。划分只看 tag，绝不看协议类型——`direct` 既可能是用户选中的节点，
     * 也可能是路由引用的辅助出站，只有 tag 能区分。
     */
    internal fun splitDependencies(config: JsonObject, nodeTags: Set<String>): Dependencies {
        fun tagOf(element: com.google.gson.JsonElement?): String {
            val obj = element as? JsonObject ?: return ""
            return obj.get("tag")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
        }
        val auxiliary = JsonArray()
        config.getAsJsonArray("outbounds")?.forEach { element ->
            val tag = tagOf(element)
            if (tag.isNotEmpty() && tag in nodeTags) return@forEach
            if (tag == SELECTOR_TAG) return@forEach
            (element as? JsonObject)?.let { auxiliary.add(it) }
        }
        val endpoints = JsonArray()
        config.getAsJsonArray("endpoints")?.forEach { element ->
            val tag = tagOf(element)
            if (tag.isNotEmpty() && tag in nodeTags) return@forEach
            (element as? JsonObject)?.let { endpoints.add(it) }
        }
        return Dependencies(auxiliary, endpoints)
    }

    /**
     * 由选中的 profile 构建配置意图。节点 outbounds 不随意图下发。
     */
    suspend fun build(profile: ProxyEntity): JSONObject {
        val instance = ProxyInstance(profile)
        try {
            instance.init()
            // 复用现有快照生成：它负责收集资源 / 插件，且 files 已是 base64 字符串 map，
            // 正好对齐模块 ConfigIntent.Files。
            val snapshot = RootModuleSnapshot(instance, "").build()
            val config = snapshot.getAsJsonObject("config") ?: throw IOException("config_missing")
            val nodeTags = instance.config.profileTagMap.values
                .filter { it.isNotBlank() }
                .toSet()
            // 节点之外的依赖必须保真：route/dns 引用的 direct/bypass、链式 detour、
            // 顶层 endpoints 与 log 缺一样，真实核心校验就会失败。
            val dependencies = splitDependencies(config, nodeTags)
            config.remove("outbounds")

            val intent = JSONObject()
            config.get("route")?.let { intent.put("router", JSONObject(it.toString())) }
            config.get("inbounds")?.let { intent.put("inbounds", JSONArray(it.toString())) }
            config.get("dns")?.let { intent.put("dns", JSONObject(it.toString())) }
            config.get("experimental")?.let { intent.put("experimental", JSONObject(it.toString())) }
            // 顶层 log 原样上送；endpoints 只保留「非节点」的透传项。
            config.get("log")?.let { intent.put("log", JSONObject(it.toString())) }
            intent.put("endpoints", JSONArray(dependencies.endpoints.toString()))
            // 辅助出站整段语义：即使为空也要发送，否则 App 删掉的 old 依赖会残留在模块。
            intent.put("auxiliaryOutbounds", JSONArray(dependencies.auxiliary.toString()))
            intent.put("autoStart", DataStore.persistAcrossReboot)
            intent.put("performancePriority", DataStore.performancePriorityMode)
            intent.put("profileId", profile.id)

            val files = JSONObject()
            snapshot.getAsJsonObject("files")?.entrySet()?.forEach { (name, value) ->
                files.put(name, value.asString)
            }
            intent.put("files", files)

            val plugins = snapshot.getAsJsonArray("plugins")
            if (plugins != null && plugins.size() > 0) {
                intent.put("plugins", JSONArray(plugins.toString()))
            }
            return intent
        } finally {
            instance.close()
        }
    }

    /**
     * 首次迁移：把 App 当前配置里的 outbounds / endpoints 还原成模块节点。
     *
     * 只在模块 store 为空时调用一次。outbound 的 tag 由 libcore 的 profileTagMap
     * 映射回 profileId，因此迁移后节点选择、热切换 tag 与旧配置保持一致。
     * 来自顶层 endpoints 数组的节点会带 endpoint 标记，模块按 endpoints 写回，不进 selector。
     *
     * 数据源必须是 RootModuleSnapshot 重写后的 config：它已把 App 私有绝对路径
     * 换成 ../files/... 便携形式；直接用 instance.config.config 会把 App 路径带进模块。
     * @author 雾晚
     */
    suspend fun migrateNodes(profile: ProxyEntity): List<ModuleApiClient.ModuleNode> {
        val instance = ProxyInstance(profile)
        try {
            instance.init()
            val tagMap = instance.config.profileTagMap
            if (tagMap.isEmpty()) return emptyList()
            val snapshot = RootModuleSnapshot(instance, "").build()
            val config = snapshot.getAsJsonObject("config") ?: return emptyList()
            val outbounds = collectByTag(config.getAsJsonArray("outbounds"))
            val endpoints = collectByTag(config.getAsJsonArray("endpoints"))
            val names = SagerDatabase.proxyDao.getEntities(tagMap.keys.toList())
                .associate { it.id to it.displayName() }
            val now = System.currentTimeMillis() / 1000
            return tagMap.mapNotNull { (id, tag) ->
                if (tag.isBlank()) return@mapNotNull null
                val endpoint = endpoints[tag]
                // 端点优先按 endpoints 归类，避免同时出现在两个数组时丢掉来源标记。
                val outbound = endpoint ?: outbounds[tag] ?: return@mapNotNull null
                ModuleApiClient.ModuleNode(
                    id = id,
                    type = outbound.get("type")?.takeIf { !it.isJsonNull }?.asString ?: "unknown",
                    name = names[id] ?: tag,
                    group = null,
                    subscriptionId = 0,
                    outbound = JSONObject(outbound.toString()),
                    updatedAt = now,
                    endpoint = endpoint != null,
                )
            }
        } finally {
            instance.close()
        }
    }

    /**
     * 在模块已有节点的前提下，挑出需要单条补写的当前 profile。
     *
     * 返回 null 表示无需补写：模块还没有节点（该走整表迁移）、模块已有这个节点、
     * 或 App 侧没能从当前配置还原出这个节点（交给模块如实报 profile_missing）。
     * 非 null 时调用方用 POST /v1/nodes 单条写入（id>0 是覆盖语义），
     * 只补这一条，绝不整表回写——否则会冲掉用户在模块里新增/编辑过的节点。
     */
    internal fun missingProfileNode(
        known: List<ModuleApiClient.ModuleNode>,
        migrated: List<ModuleApiClient.ModuleNode>,
        profileId: Long,
    ): ModuleApiClient.ModuleNode? {
        if (known.isEmpty() || profileId <= 0) return null
        if (known.any { it.id == profileId }) return null
        return migrated.firstOrNull { it.id == profileId }
    }

    /** 按 tag 索引一组 outbound 对象；缺 tag 或不是对象的元素直接跳过。 */
    private fun collectByTag(array: JsonArray?): Map<String, JsonObject> {
        if (array == null) return emptyMap()
        val result = LinkedHashMap<String, JsonObject>()
        array.forEach { element ->
            val obj = element as? JsonObject ?: return@forEach
            val tag = obj.get("tag")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
            if (tag.isNotBlank() && !result.containsKey(tag)) result[tag] = obj
        }
        return result
    }
}
