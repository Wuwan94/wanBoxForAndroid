// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.bg


import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.bg.proto.ProxyInstance
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.util.Base64

/** App-side management facade over the module HTTPS API; no Root shell. @author 雾晚 */
object RootModuleClient {
    private val changes = Mutex()
    /** Drop the cached status (delegated to ModuleApiClient); call after any mutation. */
    fun invalidateStatusCache() { ModuleApiClient.invalidateStatusCache() }
    data class InstallSelection(val id: String, val mode: Int)
    data class Status(val phase: String, val revision: String, val runningRevision: String,
        val profileId: Long, val profileName: String, val stats: RootNotificationSample?, val error: String = "",
        val installData: InstallSelection? = null,
        val telemetry: ModuleTelemetry? = null) {
        val connected get() = phase == "connected" && installData == null
        val state get() = if (installData != null) BaseService.State.Stopped else when (phase) {
            "connected" -> BaseService.State.Connected
            "starting" -> BaseService.State.Connecting
            else -> BaseService.State.Stopped
        }
    }
    fun parseResponse(text: String): Status {
        val json = JsonParser.parseString(text).asJsonObject
        // 模块 HTTP 响应写 schema，早期快照写 schemaVersion；两者都表示协议版本。
        // 只接受 1，未知版本、类型错误或两字段冲突一律拒绝。
        if (schemaOf(json) != 1) throw IOException("module_protocol_mismatch")
        val ok = json["ok"]
        if (ok != null) {
            if (!ok.isJsonPrimitive || !ok.asJsonPrimitive.isBoolean) throw IOException("module_protocol_mismatch")
            if (!ok.asBoolean) {
                val code = json["error"]?.asString.orEmpty()
                throw IOException(code.takeIf { it.matches(Regex("[a-z_]{1,80}")) } ?: "module_operation_failed")
            }
        } else if (json["state"] == null) {
            // 没有 ok 也没有 state：既不是响应也不是事件帧。
            throw IOException("module_protocol_mismatch")
        }
        val state = json.getAsJsonObject("state") ?: throw IOException("module_status_invalid")
        val phase = state["phase"]?.asString ?: throw IOException("module_status_invalid")
        if (phase !in setOf("connected", "starting", "stopped", "failed", "disabled", "cleanup_required")) throw IOException("module_status_invalid")
        val selection = state["installData"]?.takeUnless { it.isJsonNull }?.let {
            if (!it.isJsonObject) throw IOException("install_data_selection_invalid")
            val entry = it.asJsonObject
            val id = entry["id"]?.asString.orEmpty()
            if (!id.matches(Regex("[a-f0-9]{32}"))) throw IOException("install_data_selection_invalid")
            val mode = when (entry["mode"]?.asString) {
                "fresh" -> RootModuleDataUpdate.CLEAN
                "nodes" -> RootModuleDataUpdate.NODES_ONLY
                else -> throw IOException("install_data_selection_invalid")
            }
            InstallSelection(id, mode)
        }
        val stats = state["stats"]?.takeIf { it.isJsonObject && phase == "connected" && selection == null }
            ?.let { RootNotificationSample.parse(RootNotificationSample.PREFIX + it.toString()) }
        // @author 雾晚：解析模块端遥测，仅 connected 时有效
        val telemetry = state["telemetry"]?.takeIf { it.isJsonObject && phase == "connected" && selection == null }
            ?.let {
                try {
                    val obj = org.json.JSONObject(it.toString())
                    ModuleTelemetry.fromJson(obj)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    null
                }
            }
        return Status(phase, state["revision"]?.asString.orEmpty(), state["runningRevision"]?.asString.orEmpty(),
            state["profileId"]?.asLong ?: 0, state["profileName"]?.asString.orEmpty(), stats,
            state["error"]?.asString.orEmpty().takeIf { it.matches(Regex("[a-z_]{1,80}")) }.orEmpty(), selection,
            telemetry)
    }
    /** 读取协议版本：兼容 schema（当前）与 schemaVersion（历史），冲突或非法类型直接拒绝。 */
    private fun schemaOf(json: com.google.gson.JsonObject): Int? {
        fun read(name: String): Int? {
            val element = json[name] ?: return null
            if (element.isJsonNull) return null
            if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) {
                throw IOException("module_protocol_mismatch")
            }
            return element.asInt
        }
        val legacy = read("schemaVersion")
        val current = read("schema")
        if (legacy != null && current != null && legacy != current) {
            throw IOException("module_protocol_mismatch")
        }
        return current ?: legacy
    }

    suspend fun call(command: String, input: File? = null): Status = when {
        command == "status" -> ModuleApiClient.status()
        command == "dashboard" -> ModuleApiClient.dashboard()
        command == "data prepare" -> ModuleApiClient.dataPrepare()
        command == "data finish" -> ModuleApiClient.dataFinish()
        command == "data rollback" -> ModuleApiClient.dataRollback()
        command.startsWith("node select") -> {
            // 注入防护保留：非法 id 一律 IllegalArgumentException，不进入 HTTPS 调用。
            val id = command.removePrefix("node select").trim().toLongOrNull()
                ?: throw IllegalArgumentException("node_select_invalid")
            ModuleApiClient.selectNode(id)
        }
        command.startsWith("data selection-finish ") -> ModuleApiClient.selectionFinish(command.removePrefix("data selection-finish ").trim())
        command.startsWith("autostart ") -> ModuleApiClient.setAutoStart(command.removePrefix("autostart ").trim() == "on")
        else -> throw IOException("module_command_unsupported")
    }
    fun safeError(error: Exception): String = error.message?.takeIf { it.matches(Regex("[a-z_]{1,80}")) } ?: "module_operation_failed"
    suspend fun startOrReload(startIfStopped: Boolean = true) {
        RootModuleDataUpdate.applyInstallerSelection()
        if (!startIfStopped) changes.lock()
        else if (!changes.tryLock()) throw IOException("module_busy")
        try {
            val before = ModuleApiClient.status()
            // @author 雾晚: rule edits use module truth, never a stale UI/Binder state.
            if (RootModuleDataUpdate.pending()) throw IOException("data_update_pending")
            if (before.phase == "disabled") throw IOException("module_disabled_or_missing")
            val profile = SagerDatabase.proxyDao.getById(DataStore.selectedProxy)
                ?: if (!startIfStopped && !before.state.canStop) return else throw IOException("profile_missing")
            // @author 雾晚: refresh cross-user UIDs (TTL-cached) so per-app rules
            // cover secondary users / cloned apps.
            runCatching { io.nekohasekai.sagernet.utils.PackageCache.refreshMultiUserUids() }
            // 首次迁移：模块 store 为空时把当前节点的 outbounds 一次性灌入模块。
            // 之后模块是节点真值，App 不再整表回写，避免覆盖模块侧新增/编辑的节点。
            if (ModuleApiClient.nodes().isEmpty()) {
                val migrated = ModuleConfigIntent.migrateNodes(profile)
                if (migrated.isNotEmpty()) ModuleApiClient.replaceNodes(migrated)
            }
            // 只下发配置意图：节点 outbounds 由模块 store 权威，App 不再强推完整快照。
            val applied = ModuleApiClient.applyConfig(ModuleConfigIntent.build(profile))
            // Apply owns active-core restart/rollback; passive edits never start a stopped module.
            if (startIfStopped) {
                ModuleApiClient.setAutoStart(DataStore.persistAcrossReboot)
                if (!applied.state.canStop) ModuleApiClient.start()
            }
        } finally { changes.unlock() }
    }
    suspend fun stop() = changes.withLock { ModuleApiClient.stop() }
    /**
     * Hot-switch the running core to another node without restart.
     * Serialized with config applies; any failure must make the caller fall back
     * to [startOrReload]. Never throws for user data, only fixed error codes.
     * @author 雾晚
     */
    suspend fun selectNode(profileId: Long): Status {
        require(profileId > 0)
        return changes.withLock { ModuleApiClient.selectNode(profileId) }
    }
    internal suspend fun finishInstallerSelection(id: String) {
        require(id.matches(Regex("[a-f0-9]{32}")))
        ModuleApiClient.selectionFinish(id)
    }
    internal suspend fun <T> dataUpdate(action: suspend () -> T): T = changes.withLock { action() }
    suspend fun setAutoStart(enabled: Boolean) = changes.withLock { ModuleApiClient.setAutoStart(enabled) }
}

/** Portable snapshot of configs, helpers and resources, independent of App storage. @author 雾晚 */
internal class RootModuleSnapshot(private val instance: ProxyInstance, private val revision: String) {
    private val files = linkedMapOf<String, ByteArray>()
    private val mappedPaths = linkedMapOf<String, String>()
    private var total = 0L
    private fun add(name: String, source: File) {
        if (!source.isFile || source.length() > 64L * 1024 * 1024) throw IOException("resource_missing_or_large")
        if (files.size >= 512) throw IOException("too_many_resources")
        val bytes = source.inputStream().use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                if (out.size() + count > 64 * 1024 * 1024) throw IOException("resource_too_large")
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
        addBytes(name, bytes); mappedPaths[source.absolutePath] = "../$name"
    }
    private fun addBytes(name: String, bytes: ByteArray) {
        if (files.size >= 512 || files.containsKey(name)) throw IOException("too_many_or_duplicate_resources")
        if (bytes.size > 64L * 1024 * 1024) throw IOException("resource_too_large")
        total += bytes.size
        if (total > 128L * 1024 * 1024) throw IOException("resources_too_large")
        files[name] = bytes
    }
    // @author 雾晚: detect and copy App-private resources into the independent snapshot.
    @android.annotation.SuppressLint("SdCardPath")
    private fun rewrite(value: com.google.gson.JsonElement): com.google.gson.JsonElement {
        if (value.isJsonObject) value.asJsonObject.entrySet().toList().forEach { (key, child) -> value.asJsonObject.add(key, rewrite(child)) }
        else if (value.isJsonArray) for (i in 0 until value.asJsonArray.size()) value.asJsonArray.set(i, rewrite(value.asJsonArray[i]))
        else if (value.isJsonPrimitive && value.asJsonPrimitive.isString) {
            var text = value.asString
            if (text == "../cache/cache.db") text = "cache.db"
            mappedPaths[text]?.let { return com.google.gson.JsonPrimitive(it) }
            if (text.startsWith("/data/user/") || text.startsWith("/data/data/") || text.startsWith("/storage/emulated/")) {
                val name = "files/resource-${files.size}"; add(name, File(text))
                return com.google.gson.JsonPrimitive("../$name")
            }
            if (text != value.asString) return com.google.gson.JsonPrimitive(text)
        }
        return value
    }
    fun build(): JsonObject {
        val app = SagerNet.application
        app.externalAssets.walkTopDown().filter { it.isFile && it.extension.lowercase() in setOf("db", "dat", "srs", "pem") }.forEach {
            val relative = it.relativeTo(app.externalAssets).invariantSeparatorsPath
            val name = if (relative.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._/-]{0,180}"))) relative
                else "resource-" + java.security.MessageDigest.getInstance("SHA-256").digest(relative.toByteArray())
                    .take(8).joinToString("") { byte -> "%02x".format(byte) } + "." + it.extension
            add("assets/$name", it)
        }
        File(app.filesDir, "yacd").let { dir -> if (dir.isDirectory) dir.walkTopDown().filter { it.isFile }.forEach {
            add("files/yacd/" + it.relativeTo(dir).invariantSeparatorsPath, it)
        } }
        val plugins = com.google.gson.JsonArray()
        instance.config.externalIndex.forEach { (chain) -> chain.forEach { (port, profile) ->
            val bean = profile.requireBean()
            val kind = when (bean) {
                is TrojanGoBean -> "trojan-go"; is MieruBean -> "mieru"; is NaiveBean -> "naive"; is HysteriaBean -> "hysteria"
                else -> throw IOException("external_plugin_unsupported")
            }
            val binary = instance.pluginPath["$kind-plugin"]?.path ?: throw IOException("plugin_missing")
            val binaryName = "plugins/$kind/core"
            if (!files.containsKey(binaryName)) {
                add(binaryName, File(binary))
                File(binary).parentFile?.listFiles { item -> item.isFile && item.extension == "so" && item.absolutePath != binary }
                    ?.forEach { add("plugins/$kind/" + it.name, it) }
            }
            val configName = "files/plugin-$port.json"
            val plugin = JsonObject().apply { addProperty("kind", kind); addProperty("binary", binaryName); addProperty("config", configName) }
            if (bean is NaiveBean && bean.certificates.isNotBlank()) {
                val name = "files/plugin-$port.crt"; addBytes(name, bean.certificates.toByteArray()); plugin.addProperty("certificate", name)
            }
            val content = instance.pluginConfigs[port]?.second ?: throw IOException("plugin_config_missing")
            addBytes(configName, rewrite(JsonParser.parseString(content)).toString().toByteArray()); plugins.add(plugin)
        } }
        val config = rewrite(JsonParser.parseString(instance.config.config))
        // @author 雾晚: hot-switch: publish profileId -> outbound tag (+display name) as a
        // staged file (not the strict snapshot schema) so older modules still accept
        // the snapshot; the module moves its runtime selector from this map.
        runCatching {
            val tagMap = instance.config.profileTagMap
            if (tagMap.isNotEmpty()) {
                val names = SagerDatabase.proxyDao.getEntities(tagMap.keys.toList()).associate { it.id to it.displayName() }
                val profileTags = JsonObject()
                tagMap.forEach { (id, tag) ->
                    if (tag.isNotBlank()) {
                        val entry = JsonObject()
                        entry.addProperty("tag", tag)
                        entry.addProperty("name", names[id].orEmpty().take(512))
                        profileTags.add(id.toString(), entry)
                    }
                }
                if (profileTags.size() > 0) addBytes("files/profile_tags.json", profileTags.toString().toByteArray())
            }
        }
        val encoded = JsonObject(); files.forEach { (name, bytes) -> encoded.addProperty(name, Base64.getEncoder().encodeToString(bytes)) }
        return JsonObject().apply {
            addProperty("schemaVersion", 1); addProperty("expectedRevision", revision); add("config", config); add("files", encoded); add("plugins", plugins)
            addProperty("autoStart", DataStore.persistAcrossReboot); addProperty("performancePriority", DataStore.performancePriorityMode); addProperty("profileId", instance.profile.id); addProperty("profileName", instance.displayProfileName)
        }
    }
}
