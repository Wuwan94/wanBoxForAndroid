// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.bg.proto.ProxyInstance
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * 构建下发给模块的「配置意图」（/v1/config 的请求体）。
 *
 * 与 preview.5 的 RootModuleSnapshot 关键区别：
 * - 不再把完整 sing-box config（含节点 outbounds）强推给模块；
 * - 只下发非 outbounds 的全局配置（route / inbounds / dns / experimental）
 *   与资源文件、插件；节点真值由模块 store/nodes.json 权威，
 *   模块负责把节点拼进 outbounds 并生成 selector。
 *
 * 资源收集（assets / yacd / plugins / profile_tags）完全复用
 * RootModuleSnapshot.build()，只是剥离 outbounds 字段，迁移零风险。
 *
 * @author 雾晚
 */
object ModuleConfigIntent {

    /**
     * 由选中的 profile 构建配置意图。节点 outbounds 不随意图下发。
     */
    suspend fun build(profile: ProxyEntity): JSONObject {
        val instance = ProxyInstance(profile)
        try {
            instance.init()
            // 复用现有快照生成：它负责收集资源 / 插件 / profile_tags，
            // 且 files 已是 base64 字符串 map，正好对齐模块 ConfigIntent.Files。
            val snapshot = RootModuleSnapshot(instance, "").build()
            val config = snapshot.getAsJsonObject("config")
            // 节点 outbounds 由模块 store 权威：剥离，避免 App 与模块节点数据打架。
            config.remove("outbounds")

            val intent = JSONObject()
            config.get("route")?.let { intent.put("router", JSONObject(it.toString())) }
            config.get("inbounds")?.let { intent.put("inbounds", JSONArray(it.toString())) }
            config.get("dns")?.let { intent.put("dns", JSONObject(it.toString())) }
            config.get("experimental")?.let { intent.put("experimental", JSONObject(it.toString())) }
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
}
