// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.bg


import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import android.os.SystemClock
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.RootInboundBackend
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.route.ROOT_PORT_REJECT_ROUTE_MARKER
import io.nekohasekai.sagernet.route.projectRootRouteAppUIDRules
import io.nekohasekai.sagernet.route.projectRootAppIdentityDNSRules
import io.nekohasekai.sagernet.route.projectRootAppIdentityDomainDNSRules
import io.nekohasekai.sagernet.route.projectRootAppUIDDomainOutboundRules
import io.nekohasekai.sagernet.route.projectRootAppUIDRuleSetOutboundRules
import io.nekohasekai.sagernet.route.projectRootAppUIDDomainRuleSetOutboundRules
import io.nekohasekai.sagernet.route.ROOT_NETWORK_REJECT_ROUTE_MARKER
import io.nekohasekai.sagernet.route.ROOT_NETWORK_REJECT_MAX_RULE_ID
import io.nekohasekai.sagernet.route.parseRootNetworkRejectNetworks
import io.nekohasekai.sagernet.route.ROOT_PORT_REJECT_MAX_RULE_ID
import io.nekohasekai.sagernet.route.parseRootPortRejectPorts
import io.nekohasekai.sagernet.route.ROOT_NETWORK_PORT_REJECT_ROUTE_MARKER
import io.nekohasekai.sagernet.route.ROOT_NETWORK_PORT_REJECT_MAX_RULE_ID
import io.nekohasekai.sagernet.route.ROOT_SOURCE_PORT_REJECT_ROUTE_MARKER
import io.nekohasekai.sagernet.route.ROOT_SOURCE_PORT_OUTBOUND_ROUTE_MARKER
import io.nekohasekai.sagernet.route.ROOT_SOURCE_PORT_REJECT_MAX_RULE_ID
import io.nekohasekai.sagernet.route.ROOT_PROTOCOL_REJECT_ROUTE_MARKER
import io.nekohasekai.sagernet.route.ROOT_PROTOCOL_REJECT_MAX_RULE_ID
import io.nekohasekai.sagernet.route.parseRootProtocolRejectProtocols
import io.nekohasekai.sagernet.route.ROOT_SOURCE_IP_REJECT_ROUTE_MARKER
import io.nekohasekai.sagernet.route.ROOT_SOURCE_IP_REJECT_MAX_RULE_ID
import io.nekohasekai.sagernet.route.parseRootSourceIPRejectCIDRs
import io.nekohasekai.sagernet.route.ROOT_SOURCE_DESTINATION_IP_REJECT_ROUTE_MARKER
import io.nekohasekai.sagernet.route.ROOT_SOURCE_DESTINATION_IP_REJECT_MAX_RULE_ID
import io.nekohasekai.sagernet.route.ROOT_IP_REJECT_ROUTE_MARKER
import io.nekohasekai.sagernet.route.ROOT_IP_REJECT_MAX_RULE_ID
import io.nekohasekai.sagernet.route.parseRootIPRejectCIDRs
import io.nekohasekai.sagernet.route.ROOT_DOMAIN_REJECT_ROUTE_MARKER
import io.nekohasekai.sagernet.route.ROOT_DOMAIN_REJECT_MAX_RULE_ID
import io.nekohasekai.sagernet.route.parseRootDomainRejectDomains
import io.nekohasekai.sagernet.route.ROOT_DOMAIN_SUFFIX_REJECT_ROUTE_MARKER
import io.nekohasekai.sagernet.route.ROOT_DOMAIN_SUFFIX_REJECT_MAX_RULE_ID
import io.nekohasekai.sagernet.route.parseRootDomainSuffixRejectDomains
import io.nekohasekai.sagernet.route.ROOT_DOMAIN_KEYWORD_REJECT_ROUTE_MARKER
import io.nekohasekai.sagernet.route.ROOT_DOMAIN_KEYWORD_REJECT_MAX_RULE_ID
import io.nekohasekai.sagernet.route.parseRootDomainKeywordRejectKeywords
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


/** Shift a raw route position into the module's insertion state. Earlier materialized
 * generated rules that originally followed this rule have already been inserted ahead. */
internal fun rootRoutePrivateBypassModuleIndex(originalIndex: Int, earlierProjectionOriginalIndexes: List<Int>): Int =
    originalIndex - earlierProjectionOriginalIndexes.count { it > originalIndex }

/** Shift an existing projected route rule when the newly projected sniff rule originally precedes it. */
internal fun rootRouteIndexAfterSniff(originalIndex: Int, sniffOriginalIndex: Int?): Int =
    originalIndex - if (sniffOriginalIndex != null && sniffOriginalIndex < originalIndex) 1 else 0

/** Root-only CLI bridge; the App never owns core processes. @author 雾晚 */
object RootModuleClient {
    // @author 雾晚: this is the manager-owned root module, never App private storage.
    @android.annotation.SuppressLint("SdCardPath")
    const val CLI = "/data/adb/modules/wanbox/bin/wanboxctl"
    private val commands = setOf("status", "module", "logs", "dashboard", "start", "stop", "restart", "reload",
        "config apply", "config validate", "autostart on", "autostart off", "data prepare", "data finish", "data rollback")
    private val changes = Mutex()
    // @author 雾晚: 2s TTL status cache. The home observer, service tiles and node
    // dialogs all poll status; without this each poll is a `su` round-trip.
    private const val STATUS_TTL_MS = 2000L
    // Status and dashboard are distinct protocol views; never serve one in place of the other.
    private val statusCache = RootModuleStatusCache(STATUS_TTL_MS) { SystemClock.elapsedRealtime() }
    // @author 雾晚: coalesce concurrent status fetches; each su spawn costs 100-300ms.
    private val statusMutex = Mutex()
    private val readOnlyCommands = setOf("status", "module", "logs", "dashboard", "config validate")
    internal fun mutatesModule(command: String): Boolean = command !in readOnlyCommands
    /** Drop every cached protocol view before and after state-changing calls. */
    fun invalidateStatusCache() { statusCache.invalidate() }
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
        if (json["schemaVersion"]?.asInt != 1) throw IOException("module_protocol_mismatch")
        if (json["ok"]?.asBoolean != true) {
            val code = json["error"]?.asString.orEmpty()
            throw IOException(code.takeIf { it.matches(Regex("[a-z_]{1,80}")) } ?: "module_operation_failed")
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
    suspend fun call(command: String, input: File? = null): Status = withContext(Dispatchers.IO) {
        require(command in commands || command.matches(Regex("data selection-finish [a-f0-9]{32}"))
                || command.matches(Regex("node select [1-9][0-9]{0,18}")))
        require((command == "config apply" || command == "config validate") == (input != null))
        val query = when (command) {
            "status" -> RootModuleReadQuery.STATUS
            "dashboard" -> RootModuleReadQuery.DASHBOARD
            else -> null
        }
        val mutating = mutatesModule(command)
        // Invalidate before a state change too: a failed/timeout response may still have
        // reached the module, so retaining the previous view would be unsafe.
        if (mutating) statusCache.invalidate()
        try {
            if (query != null) {
                val cacheGeneration = statusCache.generation()
                statusCache.get(query)?.let { return@withContext it }
                // Coalesce concurrent reads while preserving separate status/dashboard entries.
                // A mutation can invalidate while doCall is in flight; only cache the result if
                // no invalidation occurred since this read started.
                return@withContext statusMutex.withLock {
                    statusCache.get(query) ?: doCall(command, input).also {
                        statusCache.putIfGeneration(query, it, cacheGeneration)
                    }
                }
            }
            return@withContext doCall(command, input)
        } finally {
            if (mutating) statusCache.invalidate()
        }
    }

    private suspend fun doCall(command: String, input: File? = null): Status {
        val process = try { ProcessBuilder("su", "-c", "exec $CLI $command").start() }
            catch (_: IOException) { throw IOException("root_required") }
        try {
            return coroutineScope {
                val output = async(Dispatchers.IO) {
                    process.inputStream.use { stream ->
                        val buffer = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(8192)
                        while (true) {
                            val count = stream.read(chunk); if (count < 0) break
                            if (buffer.size() + count > 1_048_576) throw IOException("module_response_too_large")
                            buffer.write(chunk, 0, count)
                        }
                        val bytes = buffer.toByteArray()
                        if (bytes.size > 1_048_576) throw IOException("module_response_too_large")
                        bytes.toString(Charsets.UTF_8)
                    }
                }
                val errors = launch(Dispatchers.IO) { process.errorStream.use { it.copyTo(object : java.io.OutputStream() { override fun write(value: Int) = Unit; override fun write(data: ByteArray, offset: Int, length: Int) = Unit }) } }
                val writer = launch(Dispatchers.IO) { process.outputStream.use { pipe -> input?.inputStream()?.use { it.copyTo(pipe) } } }
                try {
                    withTimeout(if (command in setOf("status", "module", "logs") || command.startsWith("node select ")) 15_000L else 720_000L) { runInterruptible(Dispatchers.IO) { process.waitFor() } }
                    writer.join(); errors.join()
                    try {
                        parseResponse(output.await())
                    }
                    catch (e: IOException) { throw e }
                    catch (_: Exception) { throw IOException("root_or_module_unavailable") }
                } finally {
                    process.destroy()
                    runCatching { process.inputStream.close() }; runCatching { process.errorStream.close() }; runCatching { process.outputStream.close() }
                    writer.cancel(); errors.cancel(); output.cancel()
                }
            }
        } finally { process.destroy() }
    }
    fun safeError(error: Exception): String = error.message?.takeIf { it.matches(Regex("[a-z_]{1,80}")) } ?: "module_operation_failed"
    suspend fun startOrReload(startIfStopped: Boolean = true) {
        RootModuleDataUpdate.applyInstallerSelection()
        if (!startIfStopped) changes.lock()
        else if (!changes.tryLock()) throw IOException("module_busy")
        try {
            val before = call("status")
            // @author 雾晚: rule edits use module truth, never a stale UI/Binder state.
            if (RootModuleDataUpdate.pending()) throw IOException("data_update_pending")
            if (before.phase == "disabled") throw IOException("module_disabled_or_missing")
            val profile = SagerDatabase.proxyDao.getById(DataStore.selectedProxy)
                ?: if (!startIfStopped && !before.state.canStop) return else throw IOException("profile_missing")
            // @author 雾晚: refresh cross-user UIDs (TTL-cached) so per-app rules
            // cover secondary users / cloned apps.
            runCatching { io.nekohasekai.sagernet.utils.PackageCache.refreshMultiUserUids() }
            suspend fun applyBackend(backend: RootInboundBackend, expectedRevision: String): Status {
                val instance = ProxyInstance(profile, rootInboundBackend = backend)
                val file = File.createTempFile("module-snapshot-", ".json", SagerNet.application.cacheDir)
                try {
                    instance.init()
                    file.writeText(RootModuleSnapshot(instance, expectedRevision, backend).build().toString())
                    if (file.length() > 192L * 1024 * 1024) throw IOException("snapshot_too_large")
                    val applied = call("config apply", file)
                    // Apply owns active-core restart/rollback; passive edits never start a stopped module.
                    if (startIfStopped) {
                        call("autostart ${if (DataStore.persistAcrossReboot) "on" else "off"}")
                        if (!applied.state.canStop) call("start")
                    }
                    return applied
                } finally { file.delete(); instance.close() }
            }
            try {
                applyBackend(RootInboundBackend.EBPF, before.revision)
            } catch (error: IOException) {
                // If this kernel/core cannot activate eBPF, retry the same validated profile
                // with the preserved root-TUN path. Other module/permission errors stay visible.
                if (error.message !in setOf("core_config_invalid", "new_core_failed_rolled_back", "core_start_failed", "startup_timeout")) throw error
                Logs.w("Root eBPF inbound failed; retrying with the TUN fallback (${safeError(error)})")
                val current = call("status")
                applyBackend(RootInboundBackend.TUN, current.revision)
            }
        } finally { changes.unlock() }
    }
    suspend fun stop() = changes.withLock { call("stop") }
    /**
     * Hot-switch the running core to another node without restart.
     * Serialized with config applies; any failure must make the caller fall back
     * to [startOrReload]. Never throws for user data, only fixed error codes.
     * @author 雾晚
     */
    suspend fun selectNode(profileId: Long): Status {
        require(profileId > 0)
        return changes.withLock { call("node select $profileId") }
    }
    internal suspend fun finishInstallerSelection(id: String) {
        require(id.matches(Regex("[a-f0-9]{32}")))
        call("data selection-finish $id")
    }
    internal suspend fun <T> dataUpdate(action: suspend () -> T): T = changes.withLock { action() }
    suspend fun setAutoStart(enabled: Boolean) = changes.withLock { call("autostart ${if (enabled) "on" else "off"}") }
}

/** Move only ConfigBuilder's exact Root connection-probe rule from the first route position. */
internal fun projectRootRouteProbeRule(config: JsonObject, probeHost: String): JsonObject? {
    if (probeHost.isBlank() || probeHost.length > 253 || probeHost.any { it.isWhitespace() || it.isISOControl() }) return null
    val route = config.getAsJsonObject("route") ?: return null
    val rules = route.getAsJsonArray("rules") ?: return null
    fun exact(value: com.google.gson.JsonElement): String? {
        if (!value.isJsonObject) return null
        val rule = value.asJsonObject
        if (rule.keySet() != setOf("inbound", "domain", "outbound")) return null
        fun onlyString(key: String, expected: String): Boolean {
            val array = rule.getAsJsonArray(key) ?: return false
            return array.size() == 1 && array[0].isJsonPrimitive && array[0].asJsonPrimitive.isString && array[0].asString == expected
        }
        val outbound = rule.get("outbound")
        if (!onlyString("inbound", "mixed") || !onlyString("domain", probeHost) ||
            outbound == null || !outbound.isJsonPrimitive || !outbound.asJsonPrimitive.isString ||
            outbound.asString.isBlank() || outbound.asString.length > 256 || outbound.asString.any { it == '\\' || it.isISOControl() }) return null
        return outbound.asString
    }
    val matches = (0 until rules.size()).filter { exact(rules[it]) != null }
    if (matches.size != 1 || matches.single() != 0) return null
    val index = matches.single()
    val outbound = exact(rules[index]) ?: return null
    rules.remove(index)
    return JsonObject().apply { addProperty("index", index); addProperty("host", probeHost); addProperty("outbound", outbound) }
}

/** Move only a valid route final that names an existing generated outbound. */
internal fun projectRootRouteFinal(config: JsonObject): JsonObject? {
    val route = config.getAsJsonObject("route") ?: return null
    val final = route.get("final")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: return null
    if (final.isBlank() || final.length > 256 || final.any { it == '\\' || it.code < 0x20 }) return null
    val outbounds = config.getAsJsonArray("outbounds") ?: return null
    if (outbounds.none { it.isJsonObject && it.asJsonObject.get("tag")?.let { tag -> tag.isJsonPrimitive && tag.asJsonPrimitive.isString && tag.asString == final } == true }) return null
    route.remove("final")
    return JsonObject().apply { addProperty("final", final) }
}

/** Move only ConfigBuilder's fixed address-family DNS rejection rule when it is first and unique. */
internal fun projectRootDNSIPv6QueryRule(config: JsonObject): JsonObject? {
    val dns = config.getAsJsonObject("dns") ?: return null
    val strategy = dns.get("strategy")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: return null
    val queryType = when (strategy) {
        "ipv4_only" -> "AAAA"
        "ipv6_only" -> "A"
        else -> return null
    }
    val rules = dns.getAsJsonArray("rules") ?: return null
    fun exact(value: com.google.gson.JsonElement): Boolean {
        if (!value.isJsonObject) return false
        val rule = value.asJsonObject
        if (rule.keySet() != setOf("query_type", "action")) return false
        val query = rule.get("query_type")
        val action = rule.get("action")
        return query != null && query.isJsonArray && query.asJsonArray.size() == 1 &&
            query.asJsonArray[0].let { it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString == queryType } &&
            action != null && action.isJsonPrimitive && action.asJsonPrimitive.isString && action.asString == "reject"
    }
    val matches = (0 until rules.size()).filter { exact(rules[it]) }
    if (matches.size != 1 || matches.single() != 0) return null
    rules.remove(0)
    return JsonObject().apply { addProperty("index", 0); addProperty("queryType", queryType) }
}

/** Move only ConfigBuilder's unconditional outbound-any DNS resolver guard. */
internal fun projectRootDNSOutboundAnyRule(config: JsonObject): JsonObject? {
    val dns = config.getAsJsonObject("dns") ?: return null
    val rules = dns.getAsJsonArray("rules") ?: return null
    fun exact(value: com.google.gson.JsonElement): Boolean {
        if (!value.isJsonObject) return false
        val rule = value.asJsonObject
        if (rule.keySet() != setOf("outbound", "server")) return false
        val outbound = rule.get("outbound")
        val server = rule.get("server")
        return outbound != null && outbound.isJsonArray && outbound.asJsonArray.size() == 1 &&
            outbound.asJsonArray[0].let { it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString == "any" } &&
            server != null && server.isJsonPrimitive && server.asJsonPrimitive.isString && server.asString == "dns-direct"
    }
    val matches = (0 until rules.size()).filter { exact(rules[it]) }
    if (matches.size != 1) return null
    val index = matches.single()
    rules.remove(index)
    return JsonObject().apply { addProperty("index", index) }
}

/** Move only ConfigBuilder's exact optional bypass-LAN safety rule into module input. */
internal fun projectRootRouteBypassLan(config: JsonObject): JsonObject? {
    val route = config.getAsJsonObject("route") ?: return null
    val rules = route.getAsJsonArray("rules") ?: return null
    val expectedCidrs = listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7")
    fun exact(value: com.google.gson.JsonElement): Boolean {
        if (!value.isJsonObject) return false
        val rule = value.asJsonObject
        if (rule.keySet() != setOf("ip_cidr", "outbound")) return false
        val cidrs = rule.get("ip_cidr")
        val outbound = rule.get("outbound")
        return outbound != null && outbound.isJsonPrimitive && outbound.asJsonPrimitive.isString && outbound.asString == "direct" &&
            cidrs != null && cidrs.isJsonArray && cidrs.asJsonArray.size() == expectedCidrs.size &&
            expectedCidrs.indices.all { i -> cidrs.asJsonArray[i].let { it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString == expectedCidrs[i] } }
    }
    val matches = (0 until rules.size()).filter { exact(rules[it]) }
    if (matches.size != 1) return null
    val index = matches.single()
    rules.remove(index)
    return JsonObject().apply { addProperty("index", index) }
}

/** Move only ConfigBuilder's exact fixed DNS port-53 route guard into module input. */
internal fun projectRootRouteDNSPort53Rule(config: JsonObject): JsonObject? {
    val route = config.getAsJsonObject("route") ?: return null
    val rules = route.getAsJsonArray("rules") ?: return null
    val outbounds = config.getAsJsonArray("outbounds") ?: return null
    fun exact(value: com.google.gson.JsonElement): Boolean {
        if (!value.isJsonObject) return false
        val rule = value.asJsonObject
        if (rule.keySet() != setOf("port", "outbound")) return false
        val port = rule.get("port")
        val outbound = rule.get("outbound")
        return port != null && port.isJsonArray && port.asJsonArray.size() == 1 &&
            port.asJsonArray[0].let { it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asInt == 53 } &&
            outbound != null && outbound.isJsonPrimitive && outbound.asJsonPrimitive.isString && outbound.asString.isNotBlank() &&
            outbounds.any { item -> item.isJsonObject && item.asJsonObject.get("tag")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString == outbound.asString } == true }
    }
    val matches = (0 until rules.size()).filter { exact(rules[it]) }
    if (matches.size != 1) return null
    val index = matches.single()
    val outbound = rules[index].asJsonObject.get("outbound").asString
    rules.remove(index)
    return JsonObject().apply { addProperty("index", index); addProperty("outbound", outbound) }
}

/** Move only ConfigBuilder's exact DNS protocol safety rule to module input. */
internal fun projectRootRouteDNSProtocolRule(config: JsonObject): JsonObject? {
    val route = config.getAsJsonObject("route") ?: return null
    val rules = route.getAsJsonArray("rules") ?: return null
    val outbounds = config.getAsJsonArray("outbounds") ?: return null
    fun exact(value: com.google.gson.JsonElement): Boolean {
        if (!value.isJsonObject) return false
        val rule = value.asJsonObject
        if (rule.keySet() != setOf("protocol", "outbound")) return false
        val protocol = rule.get("protocol")
        val outbound = rule.get("outbound")
        return protocol != null && protocol.isJsonArray && protocol.asJsonArray.size() == 1 &&
            protocol.asJsonArray[0].let { it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString == "dns" } &&
            outbound != null && outbound.isJsonPrimitive && outbound.asJsonPrimitive.isString && outbound.asString.isNotBlank() &&
            outbounds.any { item -> item.isJsonObject && item.asJsonObject.get("tag")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString == outbound.asString } == true }
    }
    val matches = (0 until rules.size()).filter { exact(rules[it]) }
    if (matches.size != 1) return null
    val index = matches.single()
    val outbound = rules[index].asJsonObject.get("outbound").asString
    rules.remove(index)
    return JsonObject().apply { addProperty("index", index); addProperty("outbound", outbound) }
}

/** Move only ConfigBuilder's fixed port-53 DNS hijack rule into module input. */
internal fun projectRootRouteDNSHijackPort53Rule(config: JsonObject): JsonObject? {
    val route = config.getAsJsonObject("route") ?: return null
    val rules = route.getAsJsonArray("rules") ?: return null
    fun exact(value: com.google.gson.JsonElement): Boolean {
        if (!value.isJsonObject) return false
        val rule = value.asJsonObject
        if (rule.keySet() != setOf("port", "action")) return false
        val port = rule.get("port")
        val action = rule.get("action")
        return port != null && port.isJsonArray && port.asJsonArray.size() == 1 &&
            port.asJsonArray[0].let { it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asInt == 53 } &&
            action != null && action.isJsonPrimitive && action.asJsonPrimitive.isString && action.asString == "hijack-dns"
    }
    val matches = (0 until rules.size()).filter { exact(rules[it]) }
    if (matches.size != 1) return null
    val index = matches.single()
    rules.remove(index)
    return JsonObject().apply { addProperty("index", index) }
}

/** Move only ConfigBuilder's exact protocol-DNS hijack rule into module input. */
internal fun projectRootRouteDNSProtocolHijackRule(config: JsonObject): JsonObject? {
    val route = config.getAsJsonObject("route") ?: return null
    val rules = route.getAsJsonArray("rules") ?: return null
    fun exact(value: com.google.gson.JsonElement): Boolean {
        if (!value.isJsonObject) return false
        val rule = value.asJsonObject
        if (rule.keySet() != setOf("protocol", "action")) return false
        val protocol = rule.get("protocol")
        val action = rule.get("action")
        return protocol != null && protocol.isJsonArray && protocol.asJsonArray.size() == 1 &&
            protocol.asJsonArray[0].let { it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString == "dns" } &&
            action != null && action.isJsonPrimitive && action.asJsonPrimitive.isString && action.asString == "hijack-dns"
    }
    val matches = (0 until rules.size()).filter { exact(rules[it]) }
    if (matches.size != 1) return null
    val index = matches.single()
    rules.remove(index)
    return JsonObject().apply { addProperty("index", index) }
}

/** Move only ConfigBuilder's exact optional sniff action into module input. */
internal fun projectRootRouteSniffRule(config: JsonObject): JsonObject? {
    val route = config.getAsJsonObject("route") ?: return null
    val rules = route.getAsJsonArray("rules") ?: return null
    fun exact(value: com.google.gson.JsonElement): Boolean {
        if (!value.isJsonObject) return false
        val rule = value.asJsonObject
        if (rule.keySet() != setOf("action")) return false
        val action = rule.get("action")
        return action != null && action.isJsonPrimitive && action.asJsonPrimitive.isString && action.asString == "sniff"
    }
    val matches = (0 until rules.size()).filter { exact(rules[it]) }
    if (matches.size != 1) return null
    val index = matches.single()
    rules.remove(index)
    return JsonObject().apply { addProperty("index", index) }
}

/** Move only ConfigBuilder's unique resolve action when it follows extracted Root probe/sniff rules. */
internal fun projectRootRouteResolveRule(config: JsonObject): JsonObject? {
    val route = config.getAsJsonObject("route") ?: return null
    val rules = route.getAsJsonArray("rules") ?: return null
    fun strategy(value: com.google.gson.JsonElement): String? {
        if (!value.isJsonObject) return null
        val rule = value.asJsonObject
        if (rule.keySet() != setOf("action", "strategy")) return null
        val action = rule.get("action")
        val strategy = rule.get("strategy")
        if (action == null || !action.isJsonPrimitive || !action.asJsonPrimitive.isString || action.asString != "resolve" ||
            strategy == null || !strategy.isJsonPrimitive || !strategy.asJsonPrimitive.isString ||
            strategy.asString !in setOf("ipv4_only", "ipv6_only", "prefer_ipv4", "prefer_ipv6")) return null
        return strategy.asString
    }
    val matches = (0 until rules.size()).mapNotNull { index -> strategy(rules[index])?.let { index to it } }
    if (matches.size != 1 || matches.single().first != 0) return null
    val (index, strategy) = matches.single()
    rules.remove(index)
    return JsonObject().apply { addProperty("strategy", strategy) }
}

/** Move only ConfigBuilder's unique adjacent global/direct mode-rule pair. */
internal fun projectRootRouteModeRules(config: JsonObject): JsonObject? {
    val route = config.getAsJsonObject("route") ?: return null
    val rules = route.getAsJsonArray("rules") ?: return null
    val candidates = (0 until rules.size()).filter { index ->
        val rule = rules[index].takeIf { it.isJsonObject }?.asJsonObject ?: return@filter false
        rule.has("clash_mode")
    }
    if (candidates.size != 2) return null
    val first = candidates[0]
    val second = candidates[1]
    if (second != first + 1) return null
    fun exact(index: Int, mode: String, outbound: String): Boolean {
        val rule = rules[index].takeIf { it.isJsonObject }?.asJsonObject ?: return false
        if (rule.keySet() != setOf("clash_mode", "outbound")) return false
        val actualMode = rule.get("clash_mode")
        val actualOutbound = rule.get("outbound")
        return actualMode != null && actualMode.isJsonPrimitive && actualMode.asJsonPrimitive.isString && actualMode.asString == mode &&
            actualOutbound != null && actualOutbound.isJsonPrimitive && actualOutbound.asJsonPrimitive.isString && actualOutbound.asString == outbound
    }
    if (!exact(first, "global", "proxy") || !exact(second, "direct", "direct")) return null
    rules.remove(second)
    rules.remove(first)
    return JsonObject().apply { addProperty("index", first) }
}

/** Move only ConfigBuilder's ordered DNS-target route block immediately before its DNS fallback guards. */
internal fun projectRootRouteDNSTargetRules(config: JsonObject): JsonObject? {
    val route = config.getAsJsonObject("route") ?: return null
    val rules = route.getAsJsonArray("rules") ?: return null
    fun isPort53Fallback(value: com.google.gson.JsonElement): Boolean {
        if (!value.isJsonObject) return false
        val rule = value.asJsonObject
        if (rule.keySet() != setOf("port", "outbound")) return false
        val ports = rule.get("port")
        val outbound = rule.get("outbound")
        return ports != null && ports.isJsonArray && ports.asJsonArray.size() == 1 &&
            ports.asJsonArray[0].let { it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asInt == 53 } &&
            outbound != null && outbound.isJsonPrimitive && outbound.asJsonPrimitive.isString && outbound.asString.isNotBlank()
    }
    fun isDnsProtocolFallback(value: com.google.gson.JsonElement): Boolean {
        if (!value.isJsonObject) return false
        val rule = value.asJsonObject
        if (rule.keySet() != setOf("protocol", "outbound")) return false
        val protocol = rule.get("protocol")
        val outbound = rule.get("outbound")
        return protocol != null && protocol.isJsonArray && protocol.asJsonArray.size() == 1 &&
            protocol.asJsonArray[0].let { it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString == "dns" } &&
            outbound != null && outbound.isJsonPrimitive && outbound.asJsonPrimitive.isString && outbound.asString.isNotBlank()
    }
    val portMatches = (0 until rules.size()).filter { isPort53Fallback(rules[it]) }
    val protocolMatches = (0 until rules.size()).filter { isDnsProtocolFallback(rules[it]) }
    if (portMatches.size != 1 || protocolMatches.size != 1 || protocolMatches.single() != portMatches.single() + 1) return null
    val fallbackIndex = portMatches.single()
    val fallbackPortOutbound = rules[fallbackIndex].asJsonObject.get("outbound").asString
    val fallbackProtocolOutbound = rules[fallbackIndex + 1].asJsonObject.get("outbound").asString
    if (fallbackPortOutbound != fallbackProtocolOutbound) return null

    fun targetRule(value: com.google.gson.JsonElement): Pair<Int, JsonObject>? {
        if (!value.isJsonObject) return null
        val rule = value.asJsonObject
        if (rule.keySet() !in setOf(setOf("domain", "outbound"), setOf("ip_cidr", "outbound"))) return null
        val key = if (rule.has("domain")) "domain" else "ip_cidr"
        val values = rule.get(key)
        val outbound = rule.get("outbound")
        if (values == null || !values.isJsonArray || values.asJsonArray.size() == 0 ||
            outbound == null || !outbound.isJsonPrimitive || !outbound.asJsonPrimitive.isString || outbound.asString.isBlank()) return null
        val targets = values.asJsonArray
        if (targets.any { !it.isJsonPrimitive || !it.asJsonPrimitive.isString || it.asString.isBlank() || it.asString != it.asString.trim() }) return null
        if (targets.map { it.asString }.toSet().size != targets.size()) return null
        val category = when {
            key == "domain" && outbound.asString == "direct" -> 0
            key == "ip_cidr" && outbound.asString == "direct" -> 1
            key == "domain" -> 2
            else -> 3
        }
        return category to rule
    }

    fun looksLikeTargetRule(value: com.google.gson.JsonElement): Boolean {
        if (!value.isJsonObject) return false
        val rule = value.asJsonObject
        return (rule.has("domain") || rule.has("ip_cidr")) && rule.has("outbound")
    }

    val block = mutableListOf<Pair<Int, JsonObject>>()
    var index = fallbackIndex - 1
    var priorCategory = 4
    while (index >= 0 && block.size < 4) {
        val value = rules[index]
        val candidate = targetRule(value)
        if (candidate == null) {
            if (looksLikeTargetRule(value)) return null
            break
        }
        // We scan backwards from the fallback, so categories must decrease here;
        // prepending them restores ConfigBuilder's direct-domain/IP then remote-domain/IP order.
        if (candidate.first >= priorCategory) return null
        block.add(0, candidate)
        priorCategory = candidate.first
        index--
    }
    if (block.isEmpty()) return null
    if (block.any { (category, rule) -> if (category < 2) rule.get("outbound").asString != "direct" else rule.get("outbound").asString != fallbackPortOutbound }) return null
    // These exact generated categories must be unique across the route. Ambiguous matches stay legacy.
    val blockTexts = block.map { it.second.toString() }
    if (blockTexts.any { text -> (0 until rules.size()).count { rules[it].toString() == text } != 1 }) return null
    val start = fallbackIndex - block.size
    val projected = block.map { (category, rule) -> JsonObject().apply {
        addProperty("kind", if (category == 0 || category == 2) "domain" else "ip_cidr")
        add("targets", rule.get(if (category == 0 || category == 2) "domain" else "ip_cidr").deepCopy())
        add("outbound", rule.get("outbound").deepCopy())
    } }
    for (removeIndex in fallbackIndex - 1 downTo start) rules.remove(removeIndex)
    return JsonObject().apply {
        addProperty("index", start)
        add("rules", com.google.gson.JsonArray().apply { projected.forEach(::add) })
    }
}

/** Remove only a uniquely matching ordered target block previously captured from the same route. */
internal fun removeRootRouteDNSTargetRules(config: JsonObject, projection: JsonObject): Boolean {
    val route = config.getAsJsonObject("route") ?: return false
    val rules = route.getAsJsonArray("rules") ?: return false
    val typed = projection.getAsJsonArray("rules") ?: return false
    if (typed.size() == 0) return false
    val expected = typed.map { item ->
        if (!item.isJsonObject) return false
        val target = item.asJsonObject
        if (target.keySet() != setOf("kind", "targets", "outbound")) return false
        val kind = target.get("kind")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: return false
        if (kind !in setOf("domain", "ip_cidr")) return false
        val targets = target.get("targets")?.takeIf { it.isJsonArray && it.asJsonArray.size() > 0 } ?: return false
        if (targets.asJsonArray.any { !it.isJsonPrimitive || !it.asJsonPrimitive.isString }) return false
        val outbound = target.get("outbound")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString.isNotBlank() } ?: return false
        JsonObject().apply {
            add(kind, targets.deepCopy())
            add("outbound", outbound.deepCopy())
        }
    }
    if (expected.size > rules.size()) return false
    val starts = (0..(rules.size() - expected.size)).filter { start ->
        (expected.indices).all { offset -> rules[start + offset] == expected[offset] }
    }
    if (starts.size != 1) return false
    val start = starts.single()
    repeat(expected.size) { rules.remove(start) }
    return true
}

/** Move only ConfigBuilder's exact optional private-address bypass rule into module input. */
internal fun projectRootRoutePrivateBypass(config: JsonObject): JsonObject? {
    val route = config.getAsJsonObject("route") ?: return null
    val rules = route.getAsJsonArray("rules") ?: return null
    fun exact(value: com.google.gson.JsonElement): Boolean {
        if (!value.isJsonObject) return false
        val rule = value.asJsonObject
        if (rule.keySet() != setOf("ip_is_private", "outbound")) return false
        val private = rule.get("ip_is_private")
        val outbound = rule.get("outbound")
        return private != null && private.isJsonPrimitive && private.asJsonPrimitive.isBoolean && private.asBoolean &&
            outbound != null && outbound.isJsonPrimitive && outbound.asJsonPrimitive.isString && outbound.asString == "bypass"
    }
    val matches = (0 until rules.size()).filter { exact(rules[it]) }
    if (matches.size != 1) return null
    val index = matches.single()
    rules.remove(index)
    return JsonObject().apply { addProperty("index", index) }
}

/** Move only ConfigBuilder's exact IPv4/IPv6 rejection route rule into module input. */
internal fun projectRootRouteIPVersionRule(config: JsonObject, expectedVersion: Int): JsonObject? {
    if (expectedVersion !in setOf(4, 6)) return null
    val route = config.getAsJsonObject("route") ?: return null
    val rules = route.getAsJsonArray("rules") ?: return null
    fun version(value: com.google.gson.JsonElement): Int? {
        if (!value.isJsonObject) return null
        val rule = value.asJsonObject
        if (rule.keySet() != setOf("ip_version", "action")) return null
        val ipVersion = rule.get("ip_version") ?: return null
        val action = rule.get("action") ?: return null
        if (!ipVersion.isJsonPrimitive || !ipVersion.asJsonPrimitive.isNumber || ipVersion.asString !in setOf("4", "6") ||
            !action.isJsonPrimitive || !action.asJsonPrimitive.isString || action.asString != "reject") return null
        return ipVersion.asInt
    }
    val versions = (0 until rules.size()).mapNotNull { index -> version(rules[index])?.let { index to it } }
    if (versions.size != 1 || versions.single().second != expectedVersion) return null
    val index = versions.single().first
    val ipVersion = expectedVersion
    rules.remove(index)
    return JsonObject().apply { addProperty("index", index); addProperty("ipVersion", ipVersion) }
}

/** Move only ConfigBuilder's exact fixed loopback/link-local safety rule into module input. */
internal fun projectRootRouteLoopback(config: JsonObject): JsonObject? {
    val route = config.getAsJsonObject("route") ?: return null
    val rules = route.getAsJsonArray("rules") ?: return null
    val expectedCidrs = listOf("127.0.0.0/8", "::1/128", "224.0.0.0/4", "169.254.0.0/16", "fe80::/10")
    fun isExact(rule: com.google.gson.JsonElement): Boolean {
        if (!rule.isJsonObject) return false
        val obj = rule.asJsonObject
        if (obj.keySet() != setOf("ip_cidr", "outbound")) return false
        val outbound = obj.get("outbound")
        val cidrs = obj.get("ip_cidr")
        return outbound != null && outbound.isJsonPrimitive && outbound.asJsonPrimitive.isString && outbound.asString == "direct" &&
            cidrs != null && cidrs.isJsonArray && cidrs.asJsonArray.size() == expectedCidrs.size &&
            expectedCidrs.indices.all { i -> cidrs.asJsonArray[i].let { it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString == expectedCidrs[i] } }
    }
    val matches = (0 until rules.size()).filter { isExact(rules[it]) }
    if (matches.size != 1) return null
    val index = matches.single()
    rules.remove(index)
    return JsonObject().apply { addProperty("index", index) }
}

/** Move only ConfigBuilder's exact multicast safety rule to module input. */
internal fun projectRootRouteMulticast(config: JsonObject): JsonObject? {
    val route = config.getAsJsonObject("route") ?: return null
    val rules = route.getAsJsonArray("rules") ?: return null
    val expected = listOf("224.0.0.0/3", "ff00::/8")
    fun exact(value: com.google.gson.JsonElement): Boolean {
        if (!value.isJsonObject) return false
        val rule = value.asJsonObject
        if (rule.keySet() != setOf("ip_cidr", "source_ip_cidr", "action")) return false
        fun cidrs(key: String) = rule.get(key)?.let { item -> item.isJsonArray && item.asJsonArray.size() == expected.size && expected.indices.all { i -> item.asJsonArray[i].let { it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString == expected[i] } } } == true
        val action = rule.get("action")
        return action != null && action.isJsonPrimitive && action.asJsonPrimitive.isString && action.asString == "reject" && cidrs("ip_cidr") && cidrs("source_ip_cidr")
    }
    val matches = (0 until rules.size()).filter { exact(rules[it]) }
    if (matches.size != 1) return null
    val index = matches.single()
    rules.remove(index)
    return JsonObject().apply { addProperty("index", index) }
}

/** Extract the static-host source for module-side parsing, removing its legacy JSON server. */
internal fun projectRootDNSHosts(config: JsonObject, rawHosts: String): JsonObject? {
    val dns = config.getAsJsonObject("dns") ?: return null
    val servers = dns.getAsJsonArray("servers") ?: return null
    if (rawHosts.isBlank()) return null
    if (rawHosts.length > 64 * 1024) throw IOException("root_dns_hosts_invalid")
    val index = (0 until servers.size()).firstOrNull { i ->
        val server = servers[i]
        server.isJsonObject && server.asJsonObject.get("type")?.asString == "hosts" &&
            server.asJsonObject.get("tag")?.asString == "dns-hosts"
    } ?: return null
    servers.remove(index)
    return JsonObject().apply { addProperty("raw", rawHosts) }
}

/** Move only ConfigBuilder's fixed leading local DNS resolver into typed module input. */
internal fun projectRootDNSLocalServer(config: JsonObject): JsonObject? {
    val dns = config.getAsJsonObject("dns") ?: return null
    val servers = dns.getAsJsonArray("servers") ?: return null
    if (servers.size() == 0) return null
    val server = servers[0]
    if (!server.isJsonObject) return null
    val obj = server.asJsonObject
    if (obj.keySet() != setOf("type", "tag", "detour") ||
        obj.get("type")?.let { !it.isJsonPrimitive || !it.asJsonPrimitive.isString || it.asString != "local" } != false ||
        obj.get("tag")?.let { !it.isJsonPrimitive || !it.asJsonPrimitive.isString || it.asString != "dns-local" } != false ||
        obj.get("detour")?.let { !it.isJsonPrimitive || !it.asJsonPrimitive.isString || it.asString != "direct" } != false) return null
    servers.remove(0)
    return JsonObject().apply { addProperty("tag", "dns-local"); addProperty("detour", "direct") }
}

/** Move only ConfigBuilder's exact fixed fallback direct resolver; customized DNS stays legacy. */
internal fun projectRootDNSDefaultDirectServer(config: JsonObject): JsonObject? {
    val dns = config.getAsJsonObject("dns") ?: return null
    val servers = dns.getAsJsonArray("servers") ?: return null
    if (servers.size() == 0 || !servers[0].isJsonObject) return null
    val server = servers[0].asJsonObject
    fun string(key: String, expected: String) = server.get(key)?.let {
        it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString == expected
    } == true
    val port = server.get("server_port")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
    if (server.keySet() != setOf("type", "tag", "server", "server_port", "path", "detour") ||
        !string("type", "https") || !string("tag", "dns-direct") ||
        !string("server", "223.5.5.5") || port != 443 ||
        !string("path", "/dns-query") || !string("detour", "direct")) return null
    servers.remove(0)
    return JsonObject().apply { addProperty("tag", "dns-direct") }
}

/** Pass raw resolver settings only when the generated DNS servers and selected proxy are unambiguous. */
internal fun projectRootDNSResolversSpec(
    root: JsonObject,
    directDns: List<String>,
    remoteDns: List<String>,
    ipv6Mode: Int,
    directDomainStrategy: String,
    remoteDomainStrategy: String
): JsonObject? {
    val servers = root.getAsJsonObject("dns")?.getAsJsonArray("servers") ?: return null
    fun uniqueServer(tag: String): JsonObject? {
        val matches = (0 until servers.size()).mapNotNull { i ->
            servers[i].takeIf { it.isJsonObject && it.asJsonObject.get("tag")?.let { value ->
                value.isJsonPrimitive && value.asString == tag
            } == true }?.asJsonObject
        }
        return matches.singleOrNull()
    }
    val local = uniqueServer("dns-local") ?: return null
    uniqueServer("dns-direct") ?: return null
    uniqueServer("dns-remote") ?: return null
    val allowedStrategies = setOf("auto", "prefer_ipv6", "prefer_ipv4", "ipv4_only", "ipv6_only")
    if (directDomainStrategy !in allowedStrategies || remoteDomainStrategy !in allowedStrategies) return null
    if (local.get("type")?.let { !it.isJsonPrimitive || it.asString != "local" } != false) return null
    val fallback = projectRootRouteDNSPort53Rule(root.deepCopy()) ?: return null
    val proxyTag = fallback.get("outbound")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        ?.takeIf { it.isNotBlank() } ?: return null
    return JsonObject().apply {
        add("direct", JsonArray().apply { directDns.forEach(::add) })
        add("remote", JsonArray().apply { remoteDns.forEach(::add) })
        addProperty("ipv6Mode", ipv6Mode)
        addProperty("directDomainStrategy", directDomainStrategy)
        addProperty("remoteDomainStrategy", remoteDomainStrategy)
        addProperty("proxyTag", proxyTag)
    }
}

/** Move only the selected DNS server and address-family strategy to typed module input. */
internal fun projectRootDNSPolicy(config: JsonObject): JsonObject? {
    val dns = config.getAsJsonObject("dns") ?: return null
    val servers = dns.getAsJsonArray("servers") ?: return null
    val selected = dns.get("final")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: return null
    if (selected.isBlank() || selected.length > 128 || selected.any { it == '\\' || it == '/' || it.code < 0x20 }) return null
    val hasServer = servers.any { it.isJsonObject && it.asJsonObject.get("tag")?.let { tag ->
        tag.isJsonPrimitive && tag.asJsonPrimitive.isString && tag.asString == selected
    } == true }
    if (!hasServer) return null
    val strategy = dns.get("strategy")?.takeUnless { it.isJsonNull }?.let { value ->
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString || value.asString !in setOf("ipv4_only", "ipv6_only", "prefer_ipv4", "prefer_ipv6")) return null
        value.asString
    }
    val independentCache = dns.get("independent_cache")?.let { value ->
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isBoolean) return null
        value.deepCopy()
    }
    val result = JsonObject().apply {
        addProperty("final", selected)
        strategy?.let { addProperty("strategy", it) }
        independentCache?.let { add("independentCache", it) }
    }
    dns.remove("final")
    dns.remove("strategy")
    if (independentCache != null) dns.remove("independent_cache")
    return result
}

/** Extract exact-domain reject rules from private builder markers without rewriting them in Kotlin. */
internal fun projectRootRouteDomainRejectRules(config: JsonObject): JsonObject? {
    val rules = config.getAsJsonObject("route")?.getAsJsonArray("rules") ?: return null
    val projected = JsonArray()
    val ids = hashSetOf<Long>()
    for (element in rules) {
        if (!element.isJsonObject) continue
        val rule = element.asJsonObject
        if (!rule.has(ROOT_DOMAIN_REJECT_ROUTE_MARKER)) continue
        if (rule.keySet() != setOf(ROOT_DOMAIN_REJECT_ROUTE_MARKER)) throw IOException("root_route_domain_reject_invalid")
        val marker = rule.get(ROOT_DOMAIN_REJECT_ROUTE_MARKER)
        if (!marker.isJsonObject || marker.asJsonObject.keySet() != setOf("ruleId", "domainText")) throw IOException("root_route_domain_reject_invalid")
        val payload = marker.asJsonObject
        val idValue = payload.get("ruleId")
        val id = if (idValue != null && idValue.isJsonPrimitive && idValue.asJsonPrimitive.isNumber) idValue.asString.toLongOrNull() else null
        val domain = payload.get("domainText")
        if (id == null || id !in 1..ROOT_DOMAIN_REJECT_MAX_RULE_ID || !ids.add(id) || domain == null ||
            !domain.isJsonPrimitive || !domain.asJsonPrimitive.isString || parseRootDomainRejectDomains(domain.asString).isNullOrEmpty()) {
            throw IOException("root_route_domain_reject_invalid")
        }
        projected.add(JsonObject().apply { addProperty("ruleId", id); addProperty("domainText", domain.asString) })
    }
    if (projected.size() == 0) return null
    return JsonObject().apply { add("rules", projected) }
}

/** Extract explicit domain-suffix reject rules from private builder markers without rewriting them in Kotlin. */
internal fun projectRootRouteDomainSuffixRejectRules(config: JsonObject): JsonObject? {
    val rules = config.getAsJsonObject("route")?.getAsJsonArray("rules") ?: return null
    val projected = JsonArray()
    val ids = hashSetOf<Long>()
    for (element in rules) {
        if (!element.isJsonObject) continue
        val rule = element.asJsonObject
        if (!rule.has(ROOT_DOMAIN_SUFFIX_REJECT_ROUTE_MARKER)) continue
        if (rule.keySet() != setOf(ROOT_DOMAIN_SUFFIX_REJECT_ROUTE_MARKER)) throw IOException("root_route_domain_suffix_reject_invalid")
        val marker = rule.get(ROOT_DOMAIN_SUFFIX_REJECT_ROUTE_MARKER)
        if (!marker.isJsonObject || marker.asJsonObject.keySet() != setOf("ruleId", "domainText")) throw IOException("root_route_domain_suffix_reject_invalid")
        val payload = marker.asJsonObject
        val idValue = payload.get("ruleId")
        val id = if (idValue != null && idValue.isJsonPrimitive && idValue.asJsonPrimitive.isNumber) idValue.asString.toLongOrNull() else null
        val domain = payload.get("domainText")
        if (id == null || id !in 1..ROOT_DOMAIN_SUFFIX_REJECT_MAX_RULE_ID || !ids.add(id) || domain == null ||
            !domain.isJsonPrimitive || !domain.asJsonPrimitive.isString || parseRootDomainSuffixRejectDomains(domain.asString).isNullOrEmpty()) {
            throw IOException("root_route_domain_suffix_reject_invalid")
        }
        projected.add(JsonObject().apply { addProperty("ruleId", id); addProperty("domainText", domain.asString) })
    }
    if (projected.size() == 0) return null
    return JsonObject().apply { add("rules", projected) }
}

/** Extract explicit keyword reject input from private builder markers without rewriting it in Kotlin. */
internal fun projectRootRouteDomainKeywordRejectRules(config: JsonObject): JsonObject? {
    val rules = config.getAsJsonObject("route")?.getAsJsonArray("rules") ?: return null
    val projected = JsonArray()
    val ids = hashSetOf<Long>()
    for (element in rules) {
        if (!element.isJsonObject) continue
        val rule = element.asJsonObject
        if (!rule.has(ROOT_DOMAIN_KEYWORD_REJECT_ROUTE_MARKER)) continue
        if (rule.keySet() != setOf(ROOT_DOMAIN_KEYWORD_REJECT_ROUTE_MARKER)) throw IOException("root_route_domain_keyword_reject_invalid")
        val marker = rule.get(ROOT_DOMAIN_KEYWORD_REJECT_ROUTE_MARKER)
        if (!marker.isJsonObject || marker.asJsonObject.keySet() != setOf("ruleId", "domainText")) throw IOException("root_route_domain_keyword_reject_invalid")
        val payload = marker.asJsonObject
        val idValue = payload.get("ruleId")
        val id = if (idValue != null && idValue.isJsonPrimitive && idValue.asJsonPrimitive.isNumber) idValue.asString.toLongOrNull() else null
        val text = payload.get("domainText")
        if (id == null || id !in 1..ROOT_DOMAIN_KEYWORD_REJECT_MAX_RULE_ID || !ids.add(id) || text == null ||
            !text.isJsonPrimitive || !text.asJsonPrimitive.isString || parseRootDomainKeywordRejectKeywords(text.asString).isNullOrEmpty()) {
            throw IOException("root_route_domain_keyword_reject_invalid")
        }
        projected.add(JsonObject().apply { addProperty("ruleId", id); addProperty("domainText", text.asString) })
    }
    if (projected.size() == 0) return null
    return JsonObject().apply { add("rules", projected) }
}

/** Extract typed raw route policy from private builder markers without rewriting it in Kotlin. */
internal fun projectRootRoutePortRejectRules(config: JsonObject): JsonObject? {
    val rules = config.getAsJsonObject("route")?.getAsJsonArray("rules") ?: return null
    val projected = JsonArray()
    val ids = hashSetOf<Long>()
    for (element in rules) {
        if (!element.isJsonObject) continue
        val rule = element.asJsonObject
        if (!rule.has(ROOT_PORT_REJECT_ROUTE_MARKER)) continue
        if (rule.keySet() != setOf(ROOT_PORT_REJECT_ROUTE_MARKER)) throw IOException("root_route_port_reject_invalid")
        val marker = rule.get(ROOT_PORT_REJECT_ROUTE_MARKER)
        if (!marker.isJsonObject || marker.asJsonObject.keySet() != setOf("ruleId", "portText")) {
            throw IOException("root_route_port_reject_invalid")
        }
        val payload = marker.asJsonObject
        val idValue = payload.get("ruleId")
        val ruleId = if (idValue != null && idValue.isJsonPrimitive && idValue.asJsonPrimitive.isNumber)
            idValue.asString.toLongOrNull() else null
        val portValue = payload.get("portText")
        if (ruleId == null || ruleId !in 1..ROOT_PORT_REJECT_MAX_RULE_ID || !ids.add(ruleId) ||
            portValue == null || !portValue.isJsonPrimitive || !portValue.asJsonPrimitive.isString ||
            parseRootPortRejectPorts(portValue.asString).isNullOrEmpty()) {
            throw IOException("root_route_port_reject_invalid")
        }
        projected.add(JsonObject().apply {
            addProperty("ruleId", ruleId)
            addProperty("portText", portValue.asString)
        })
    }
    if (projected.size() == 0) return null
    return JsonObject().apply { add("rules", projected) }
}

/** Extract original network-only reject rules from private builder markers. */
internal fun projectRootRouteNetworkRejectRules(config: JsonObject): JsonObject? {
    val rules = config.getAsJsonObject("route")?.getAsJsonArray("rules") ?: return null
    val projected = JsonArray()
    val ids = hashSetOf<Long>()
    for (element in rules) {
        if (!element.isJsonObject) continue
        val rule = element.asJsonObject
        if (!rule.has(ROOT_NETWORK_REJECT_ROUTE_MARKER)) continue
        if (rule.keySet() != setOf(ROOT_NETWORK_REJECT_ROUTE_MARKER)) throw IOException("root_route_network_reject_invalid")
        val marker = rule.get(ROOT_NETWORK_REJECT_ROUTE_MARKER)
        if (!marker.isJsonObject || marker.asJsonObject.keySet() != setOf("ruleId", "networkText")) {
            throw IOException("root_route_network_reject_invalid")
        }
        val payload = marker.asJsonObject
        val idValue = payload.get("ruleId")
        val ruleId = if (idValue != null && idValue.isJsonPrimitive && idValue.asJsonPrimitive.isNumber)
            idValue.asString.toLongOrNull() else null
        val networkValue = payload.get("networkText")
        if (ruleId == null || ruleId !in 1..ROOT_NETWORK_REJECT_MAX_RULE_ID || !ids.add(ruleId) ||
            networkValue == null || !networkValue.isJsonPrimitive || !networkValue.asJsonPrimitive.isString ||
            parseRootNetworkRejectNetworks(networkValue.asString).isNullOrEmpty()) {
            throw IOException("root_route_network_reject_invalid")
        }
        projected.add(JsonObject().apply {
            addProperty("ruleId", ruleId)
            addProperty("networkText", networkValue.asString)
        })
    }
    if (projected.size() == 0) return null
    return JsonObject().apply { add("rules", projected) }
}

/** Extract raw source-port-only reject rules from private builder markers. */
internal fun projectRootRouteSourcePortRejectRules(config: JsonObject): JsonObject? {
    val rules = config.getAsJsonObject("route")?.getAsJsonArray("rules") ?: return null
    val projected = JsonArray()
    val ids = hashSetOf<Long>()
    for (element in rules) {
        if (!element.isJsonObject) continue
        val rule = element.asJsonObject
        if (!rule.has(ROOT_SOURCE_PORT_REJECT_ROUTE_MARKER)) continue
        if (rule.keySet() != setOf(ROOT_SOURCE_PORT_REJECT_ROUTE_MARKER)) throw IOException("root_route_source_port_reject_invalid")
        val marker = rule.get(ROOT_SOURCE_PORT_REJECT_ROUTE_MARKER)
        if (!marker.isJsonObject || marker.asJsonObject.keySet() != setOf("ruleId", "sourcePortText")) {
            throw IOException("root_route_source_port_reject_invalid")
        }
        val payload = marker.asJsonObject
        val idValue = payload.get("ruleId")
        val ruleId = if (idValue != null && idValue.isJsonPrimitive && idValue.asJsonPrimitive.isNumber)
            idValue.asString.toLongOrNull() else null
        val portValue = payload.get("sourcePortText")
        if (ruleId == null || ruleId !in 1..ROOT_SOURCE_PORT_REJECT_MAX_RULE_ID || !ids.add(ruleId) ||
            portValue == null || !portValue.isJsonPrimitive || !portValue.asJsonPrimitive.isString ||
            parseRootPortRejectPorts(portValue.asString).isNullOrEmpty()) {
            throw IOException("root_route_source_port_reject_invalid")
        }
        projected.add(JsonObject().apply {
            addProperty("ruleId", ruleId)
            addProperty("sourcePortText", portValue.asString)
        })
    }
    if (projected.size() == 0) return null
    return JsonObject().apply { add("rules", projected) }
}

/** Extract raw protocol-only reject rules from private builder markers. */
internal fun projectRootRouteProtocolRejectRules(config: JsonObject): JsonObject? {
    val rules = config.getAsJsonObject("route")?.getAsJsonArray("rules") ?: return null
    val projected = JsonArray()
    val ids = hashSetOf<Long>()
    for (element in rules) {
        if (!element.isJsonObject) continue
        val rule = element.asJsonObject
        if (!rule.has(ROOT_PROTOCOL_REJECT_ROUTE_MARKER)) continue
        if (rule.keySet() != setOf(ROOT_PROTOCOL_REJECT_ROUTE_MARKER)) throw IOException("root_route_protocol_reject_invalid")
        val marker = rule.get(ROOT_PROTOCOL_REJECT_ROUTE_MARKER)
        if (!marker.isJsonObject || marker.asJsonObject.keySet() != setOf("ruleId", "protocolText")) {
            throw IOException("root_route_protocol_reject_invalid")
        }
        val payload = marker.asJsonObject
        val idValue = payload.get("ruleId")
        val ruleId = if (idValue != null && idValue.isJsonPrimitive && idValue.asJsonPrimitive.isNumber)
            idValue.asString.toLongOrNull() else null
        val protocolValue = payload.get("protocolText")
        if (ruleId == null || ruleId !in 1..ROOT_PROTOCOL_REJECT_MAX_RULE_ID || !ids.add(ruleId) ||
            protocolValue == null || !protocolValue.isJsonPrimitive || !protocolValue.asJsonPrimitive.isString ||
            parseRootProtocolRejectProtocols(protocolValue.asString).isNullOrEmpty()) {
            throw IOException("root_route_protocol_reject_invalid")
        }
        projected.add(JsonObject().apply {
            addProperty("ruleId", ruleId)
            addProperty("protocolText", protocolValue.asString)
        })
    }
    if (projected.size() == 0) return null
    return JsonObject().apply { add("rules", projected) }
}

/** Extract exact raw source-IP-CIDR-only reject rules from private builder markers. */
internal fun projectRootRouteSourceIPRejectRules(config: JsonObject): JsonObject? {
    val rules = config.getAsJsonObject("route")?.getAsJsonArray("rules") ?: return null
    val projected = JsonArray()
    val ids = hashSetOf<Long>()
    for (element in rules) {
        if (!element.isJsonObject) continue
        val rule = element.asJsonObject
        if (!rule.has(ROOT_SOURCE_IP_REJECT_ROUTE_MARKER)) continue
        if (rule.keySet() != setOf(ROOT_SOURCE_IP_REJECT_ROUTE_MARKER)) throw IOException("root_route_source_ip_reject_invalid")
        val marker = rule.get(ROOT_SOURCE_IP_REJECT_ROUTE_MARKER)
        if (!marker.isJsonObject || marker.asJsonObject.keySet() != setOf("ruleId", "sourceText")) {
            throw IOException("root_route_source_ip_reject_invalid")
        }
        val payload = marker.asJsonObject
        val idValue = payload.get("ruleId")
        val ruleId = if (idValue != null && idValue.isJsonPrimitive && idValue.asJsonPrimitive.isNumber)
            idValue.asString.toLongOrNull() else null
        val sourceValue = payload.get("sourceText")
        if (ruleId == null || ruleId !in 1..ROOT_SOURCE_IP_REJECT_MAX_RULE_ID || !ids.add(ruleId) ||
            sourceValue == null || !sourceValue.isJsonPrimitive || !sourceValue.asJsonPrimitive.isString ||
            parseRootSourceIPRejectCIDRs(sourceValue.asString).isNullOrEmpty()) {
            throw IOException("root_route_source_ip_reject_invalid")
        }
        projected.add(JsonObject().apply {
            addProperty("ruleId", ruleId)
            addProperty("sourceText", sourceValue.asString)
        })
    }
    if (projected.size() == 0) return null
    return JsonObject().apply { add("rules", projected) }
}

/** Extract exact raw destination-IP-CIDR-only reject rules from private builder markers. */
internal fun projectRootRouteIPRejectRules(config: JsonObject): JsonObject? {
    val rules = config.getAsJsonObject("route")?.getAsJsonArray("rules") ?: return null
    val projected = JsonArray()
    val ids = hashSetOf<Long>()
    for (element in rules) {
        if (!element.isJsonObject) continue
        val rule = element.asJsonObject
        if (!rule.has(ROOT_IP_REJECT_ROUTE_MARKER)) continue
        if (rule.keySet() != setOf(ROOT_IP_REJECT_ROUTE_MARKER)) throw IOException("root_route_ip_reject_invalid")
        val marker = rule.get(ROOT_IP_REJECT_ROUTE_MARKER)
        if (!marker.isJsonObject || marker.asJsonObject.keySet() != setOf("ruleId", "ipText")) {
            throw IOException("root_route_ip_reject_invalid")
        }
        val payload = marker.asJsonObject
        val idValue = payload.get("ruleId")
        val ruleId = if (idValue != null && idValue.isJsonPrimitive && idValue.asJsonPrimitive.isNumber)
            idValue.asString.toLongOrNull() else null
        val sourceValue = payload.get("ipText")
        if (ruleId == null || ruleId !in 1..ROOT_IP_REJECT_MAX_RULE_ID || !ids.add(ruleId) ||
            sourceValue == null || !sourceValue.isJsonPrimitive || !sourceValue.asJsonPrimitive.isString ||
            parseRootIPRejectCIDRs(sourceValue.asString).isNullOrEmpty()) {
            throw IOException("root_route_ip_reject_invalid")
        }
        projected.add(JsonObject().apply {
            addProperty("ruleId", ruleId)
            addProperty("ipText", sourceValue.asString)
        })
    }
    if (projected.size() == 0) return null
    return JsonObject().apply { add("rules", projected) }
}

/** Extract exact raw network+port reject conjunctions from private markers. */
internal fun projectRootRouteNetworkPortRejectRules(config: JsonObject): JsonObject? {
    val rules = config.getAsJsonObject("route")?.getAsJsonArray("rules") ?: return null
    val projected = JsonArray()
    val ids = hashSetOf<Long>()
    for (element in rules) {
        if (!element.isJsonObject) continue
        val rule = element.asJsonObject
        if (!rule.has(ROOT_NETWORK_PORT_REJECT_ROUTE_MARKER)) continue
        if (rule.keySet() != setOf(ROOT_NETWORK_PORT_REJECT_ROUTE_MARKER)) throw IOException("root_route_network_port_reject_invalid")
        val marker = rule.get(ROOT_NETWORK_PORT_REJECT_ROUTE_MARKER)
        if (!marker.isJsonObject || marker.asJsonObject.keySet() != setOf("ruleId", "networkText", "portText")) throw IOException("root_route_network_port_reject_invalid")
        val payload = marker.asJsonObject
        val idValue = payload.get("ruleId")
        val id = if (idValue != null && idValue.isJsonPrimitive && idValue.asJsonPrimitive.isNumber) idValue.asString.toLongOrNull() else null
        val networkValue = payload.get("networkText")
        val portValue = payload.get("portText")
        if (id == null || id !in 1..ROOT_NETWORK_PORT_REJECT_MAX_RULE_ID || !ids.add(id) ||
            networkValue == null || !networkValue.isJsonPrimitive || !networkValue.asJsonPrimitive.isString || parseRootNetworkRejectNetworks(networkValue.asString).isNullOrEmpty() ||
            portValue == null || !portValue.isJsonPrimitive || !portValue.asJsonPrimitive.isString || parseRootPortRejectPorts(portValue.asString).isNullOrEmpty()) {
            throw IOException("root_route_network_port_reject_invalid")
        }
        projected.add(JsonObject().apply { addProperty("ruleId", id); addProperty("networkText", networkValue.asString); addProperty("portText", portValue.asString) })
    }
    if (projected.size() == 0) return null
    return JsonObject().apply { add("rules", projected) }
}

/** Extract raw source+destination CIDR reject rules from private builder markers. */
internal fun projectRootRouteSourceDestinationIPRejectRules(config: JsonObject): JsonObject? {
    val rules = config.getAsJsonObject("route")?.getAsJsonArray("rules") ?: return null
    val projected = JsonArray()
    val ids = hashSetOf<Long>()
    for (element in rules) {
        if (!element.isJsonObject) continue
        val rule = element.asJsonObject
        if (!rule.has(ROOT_SOURCE_DESTINATION_IP_REJECT_ROUTE_MARKER)) continue
        if (rule.keySet() != setOf(ROOT_SOURCE_DESTINATION_IP_REJECT_ROUTE_MARKER)) throw IOException("root_route_source_destination_ip_reject_invalid")
        val marker = rule.get(ROOT_SOURCE_DESTINATION_IP_REJECT_ROUTE_MARKER)
        if (!marker.isJsonObject || marker.asJsonObject.keySet() != setOf("ruleId", "sourceText", "ipText")) throw IOException("root_route_source_destination_ip_reject_invalid")
        val payload = marker.asJsonObject
        val idValue = payload.get("ruleId")
        val id = if (idValue != null && idValue.isJsonPrimitive && idValue.asJsonPrimitive.isNumber) idValue.asString.toLongOrNull() else null
        val sourceValue = payload.get("sourceText")
        val ipValue = payload.get("ipText")
        if (id == null || id !in 1..ROOT_SOURCE_DESTINATION_IP_REJECT_MAX_RULE_ID || !ids.add(id) ||
            sourceValue == null || !sourceValue.isJsonPrimitive || !sourceValue.asJsonPrimitive.isString || parseRootSourceIPRejectCIDRs(sourceValue.asString).isNullOrEmpty() ||
            ipValue == null || !ipValue.isJsonPrimitive || !ipValue.asJsonPrimitive.isString || parseRootIPRejectCIDRs(ipValue.asString).isNullOrEmpty()) {
            throw IOException("root_route_source_destination_ip_reject_invalid")
        }
        projected.add(JsonObject().apply { addProperty("ruleId", id); addProperty("sourceText", sourceValue.asString); addProperty("ipText", ipValue.asString) })
    }
    if (projected.size() == 0) return null
    return JsonObject().apply { add("rules", projected) }
}

/** Portable snapshot of configs, helpers and resources, independent of App storage. @author 雾晚 */
internal class RootModuleSnapshot(private val instance: ProxyInstance, private val revision: String, private val backend: RootInboundBackend) {
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
        val rootInbound = config.asJsonObject.getAsJsonArray("inbounds")?.let { inbounds ->
            val index = (0 until inbounds.size()).firstOrNull { i ->
                inbounds[i].isJsonObject && inbounds[i].asJsonObject.get("tag")?.asString == "tun-in"
            }
            index?.let { i ->
                val inbound = inbounds[i].asJsonObject
                val spec = JsonObject().apply {
                    addProperty("backend", if (backend == RootInboundBackend.EBPF) "ebpf" else "tun")
                    addProperty("ipv6Mode", DataStore.ipv6Mode)
                    if (backend == RootInboundBackend.TUN) {
                        addProperty("mtu", inbound.get("mtu")?.asInt ?: DataStore.mtu)
                        addProperty("strictRoute", inbound.get("strict_route")?.asBoolean ?: DataStore.strictRoute)
                    } else {
                        val local = inbound.getAsJsonObject("local")
                        addProperty("bypassPrivateAddress", local?.get("bypass_private_address")?.asBoolean ?: DataStore.bypassLan)
                    }
                    val uidConfig = if (backend == RootInboundBackend.EBPF) inbound.getAsJsonObject("local") else inbound
                    uidConfig?.getAsJsonArray("include_uid")?.let { add("includeUids", it.deepCopy()) }
                    uidConfig?.getAsJsonArray("exclude_uid")?.let { add("excludeUids", it.deepCopy()) }
                }
                inbounds.remove(i)
                spec
            }
        }
        // Schema v40 passes only exact-domain reject input to the Root module.
        val rootRouteDomainRejectRules = if (rootInbound != null) projectRootRouteDomainRejectRules(config.asJsonObject) else null
        val rootRouteDomainSuffixRejectRules = if (rootInbound != null) projectRootRouteDomainSuffixRejectRules(config.asJsonObject) else null
        val rootRouteDomainKeywordRejectRules = if (rootInbound != null) projectRootRouteDomainKeywordRejectRules(config.asJsonObject) else null
        // Schema v30 passes original port-only reject rule input; the Root module parses/materializes it.
        val rootRoutePortRejectRules = if (rootInbound != null) projectRootRoutePortRejectRules(config.asJsonObject) else null
        val rootRouteNetworkPortRejectRules = if (rootInbound != null) projectRootRouteNetworkPortRejectRules(config.asJsonObject) else null
        val rootRouteNetworkRejectRules = if (rootInbound != null) projectRootRouteNetworkRejectRules(config.asJsonObject) else null
        val rootRouteSourcePortRejectRules = if (rootInbound != null) projectRootRouteSourcePortRejectRules(config.asJsonObject) else null
        val rootRouteProtocolRejectRules = if (rootInbound != null) projectRootRouteProtocolRejectRules(config.asJsonObject) else null
        val rootRouteSourceIPRejectRules = if (rootInbound != null) projectRootRouteSourceIPRejectRules(config.asJsonObject) else null
        val rootRouteIPRejectRules = if (rootInbound != null) projectRootRouteIPRejectRules(config.asJsonObject) else null
        val rootRouteSourceDestinationIPRejectRules = if (rootInbound != null) projectRootRouteSourceDestinationIPRejectRules(config.asJsonObject) else null
        val rootRouteSourceDestinationPortRejectRules = if (rootInbound != null) projectRootRouteSourceDestinationPortRejectRules(config.asJsonObject) else null
        val rootRouteProtocolPortRejectRules = if (rootInbound != null) projectRootRouteProtocolPortRejectRules(config.asJsonObject) else null
        // Capture the mode pair's coordinate in the complete generated route before prior projections.
        val rootRouteModeRulesOriginalIndex = if (rootInbound != null) projectRootRouteModeRules(config.asJsonObject.deepCopy())?.get("index")?.asInt else null
        // Schema v25 moves only ConfigBuilder's exact Root connection-probe route.
        // It is emitted at position zero from the configured test URL's host.
        val rootRouteProbeRule = if (rootInbound != null) {
            val probeHost = runCatching { java.net.URI(DataStore.connectionTestURL).host }.getOrNull()
            probeHost?.let { projectRootRouteProbeRule(config.asJsonObject, it) }
        } else null
        // Schema v6 moves the small, root-wide route controls into the module-owned
        // protocol. Match rules, DNS and rule-set contents remain in the transition config.
        val rootRoutePolicy = if (rootInbound != null) config.asJsonObject.getAsJsonObject("route")?.let { route ->
            val keys = listOf("auto_detect_interface", "override_android_vpn", "find_process", "default_domain_resolver", "default_network_strategy")
            if (keys.none(route::has)) return@let null
            val spec = JsonObject()
            var supported = true
            listOf("auto_detect_interface" to "autoDetectInterface", "override_android_vpn" to "overrideAndroidVpn", "find_process" to "findProcess").forEach { (source, target) ->
                route.get(source)?.let { value ->
                    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isBoolean) supported = false
                    else spec.add(target, value.deepCopy())
                }
            }
            route.get("default_domain_resolver")?.let { value ->
                if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) supported = false
                else spec.add("defaultDomainResolver", value.deepCopy())
            }
            route.get("default_network_strategy")?.let { value ->
                if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString || value.asString !in setOf("hybrid", "fallback")) supported = false
                else spec.add("defaultNetworkStrategy", value.deepCopy())
            }
            if (!supported || listOf("auto_detect_interface", "override_android_vpn", "find_process").any { !spec.has(when (it) { "auto_detect_interface" -> "autoDetectInterface"; "override_android_vpn" -> "overrideAndroidVpn"; else -> "findProcess" }) }) return@let null
            keys.forEach(route::remove)
            spec
        } else null
        // Capture the optional sniff action's source position before any route-rule projections.
        val rootRouteSniffOriginalIndex = if (rootInbound != null) projectRootRouteSniffRule(config.asJsonObject.deepCopy())?.get("index")?.asInt else null
        // Capture this optional rule's original route index before any route-rule projections.
        val rootRoutePrivateBypassOriginalIndex = if (rootInbound != null) projectRootRoutePrivateBypass(config.asJsonObject.deepCopy())?.get("index")?.asInt else null
        // Preserve original coordinates for rules that the module restores before private bypass.
        // The module inserts in its own fixed sequence, so a later-position generated rule already
        // restored ahead of private bypass shifts its insertion slot left by one.
        val rootRouteLoopbackOriginalIndex = if (rootInbound != null) projectRootRouteLoopback(config.asJsonObject.deepCopy())?.get("index")?.asInt else null
        val rootRouteMulticastOriginalIndex = if (rootInbound != null) projectRootRouteMulticast(config.asJsonObject.deepCopy())?.get("index")?.asInt else null
        val rootRouteBypassLanOriginalIndex = if (rootInbound != null) projectRootRouteBypassLan(config.asJsonObject.deepCopy())?.get("index")?.asInt else null
        // Schema v29 gives the Root module raw resolver preferences; it derives servers and targets itself.
        val rootDNSResolvers = if (rootInbound != null && projectRootDNSPolicy(config.asJsonObject.deepCopy()) != null) {
            projectRootDNSResolversSpec(
                config.asJsonObject,
                DataStore.directDns.split("\n"),
                DataStore.remoteDns.split("\n"),
                DataStore.ipv6Mode,
                DataStore.configurationStore.getString("domain_strategy_for_direct") ?: "auto",
                DataStore.configurationStore.getString("domain_strategy_for_remote") ?: "auto"
            )
        } else null
        val rootRouteDNSPort53OriginalIndex = if (rootInbound != null && rootDNSResolvers == null) projectRootRouteDNSPort53Rule(config.asJsonObject.deepCopy())?.get("index")?.asInt else null
        val rootRouteDNSProtocolOriginalIndex = if (rootInbound != null && rootDNSResolvers == null) projectRootRouteDNSProtocolRule(config.asJsonObject.deepCopy())?.get("index")?.asInt else null
        // Capture normalized DNS targets before route-rule projection. Remove them only after every other
        // source index has been captured and projected, so unrelated projection coordinates stay stable.
        val rootRouteDNSTargetRulesCandidate = if (rootInbound != null && rootDNSResolvers == null &&
            rootRouteDNSPort53OriginalIndex != null &&
            rootRouteDNSProtocolOriginalIndex == rootRouteDNSPort53OriginalIndex + 1
        ) projectRootRouteDNSTargetRules(config.asJsonObject.deepCopy()) else null
        val rootRouteIPVersionOriginalIndex = if (rootInbound != null) rootInbound.get("ipv6Mode")?.asInt?.let { mode ->
            val expectedVersion = when (mode) { 0 -> 6; 3 -> 4; else -> null }
            expectedVersion?.let { projectRootRouteIPVersionRule(config.asJsonObject.deepCopy(), it)?.get("index")?.asInt }
        } else null
        // A resolved destination strategy precedes ConfigBuilder's generated IP-family reject rule.
        // Detect it on a clone with the optional sniff rule removed; the live route still holds both here.
        val rootRouteResolveWillProject = if (rootInbound != null) config.asJsonObject.deepCopy().let { candidate ->
            projectRootRouteSniffRule(candidate)
            projectRootRouteResolveRule(candidate) != null
        } else false
        val rootRouteDNSHijackPort53OriginalIndex = if (rootInbound != null) projectRootRouteDNSHijackPort53Rule(config.asJsonObject.deepCopy())?.get("index")?.asInt else null
        val rootRouteDNSProtocolHijackOriginalIndex = if (rootInbound != null) projectRootRouteDNSProtocolHijackRule(config.asJsonObject.deepCopy())?.get("index")?.asInt else null
        // Schema v12 transfers only the validated default outbound tag; match rules stay legacy.
        val rootRouteFinal = if (rootInbound != null) projectRootRouteFinal(config.asJsonObject) else null
        // Schema v13 moves only ConfigBuilder's exact loopback/link-local safety rule.
        val rootRouteLoopback = if (rootInbound != null) projectRootRouteLoopback(config.asJsonObject)?.apply { rootRouteLoopbackOriginalIndex?.let { addProperty("index", rootRouteIndexAfterSniff(it, rootRouteSniffOriginalIndex)) } } else null
        // Schema v14 moves only ConfigBuilder's exact multicast safety rule.
        val rootRouteMulticast = if (rootInbound != null) projectRootRouteMulticast(config.asJsonObject)?.apply { rootRouteMulticastOriginalIndex?.let { addProperty("index", rootRouteIndexAfterSniff(it, rootRouteSniffOriginalIndex)) } } else null
        // Schema v19 moves only ConfigBuilder's exact optional direct bypass-LAN rule.
        val rootRouteBypassLan = if (rootInbound != null) projectRootRouteBypassLan(config.asJsonObject)?.apply { rootRouteBypassLanOriginalIndex?.let { addProperty("index", rootRouteIndexAfterSniff(it, rootRouteSniffOriginalIndex)) } } else null
        // Schema v28 transfers ConfigBuilder's normalized DNS target routes as one ordered block.
        // Remove only after other route projections have consumed their original source indices.
        // Schema v17 moves only the exact generated port-53 DNS leak-protection rule.
        val rootRouteDNSPort53Rule = if (rootInbound != null) projectRootRouteDNSPort53Rule(config.asJsonObject)?.apply { rootRouteDNSPort53OriginalIndex?.let { addProperty("index", rootRouteIndexAfterSniff(it, rootRouteSniffOriginalIndex)) } } else null
        // Schema v18 moves only ConfigBuilder's exact DNS-protocol protection rule.
        val rootRouteDNSProtocolRule = if (rootInbound != null) projectRootRouteDNSProtocolRule(config.asJsonObject)?.apply { rootRouteDNSProtocolOriginalIndex?.let { addProperty("index", rootRouteIndexAfterSniff(it, rootRouteSniffOriginalIndex)) } } else null
        // Schema v20 moves only ConfigBuilder's exact IPv4/IPv6 route rejection rule.
        val rootRouteIPVersionRule = rootInbound?.get("ipv6Mode")?.asInt?.let { mode ->
            val expectedVersion = when (mode) { 0 -> 6; 3 -> 4; else -> null }
            expectedVersion?.let { version -> projectRootRouteIPVersionRule(config.asJsonObject, version)?.apply {
                rootRouteIPVersionOriginalIndex?.let { raw ->
                    val afterSniff = rootRouteIndexAfterSniff(raw, rootRouteSniffOriginalIndex)
                    addProperty("index", afterSniff - if (rootRouteResolveWillProject && afterSniff > 0) 1 else 0)
                }
            } }
        }
        // Schema v21 moves only ConfigBuilder's exact optional private-address bypass rule.
        val rootRoutePrivateBypass = if (rootInbound != null) projectRootRoutePrivateBypass(config.asJsonObject)?.apply {
            rootRoutePrivateBypassOriginalIndex?.let { originalIndex ->
                val earlierProjectedIndexes = listOfNotNull(
                    if (rootRouteIPVersionRule != null) rootRouteIPVersionOriginalIndex else null,
                    if (rootRouteDNSProtocolRule != null) rootRouteDNSProtocolOriginalIndex else null,
                    if (rootRouteDNSPort53Rule != null) rootRouteDNSPort53OriginalIndex else null,
                    if (rootRouteBypassLan != null) rootRouteBypassLanOriginalIndex else null,
                    if (rootRouteMulticast != null) rootRouteMulticastOriginalIndex else null,
                    if (rootRouteLoopback != null) rootRouteLoopbackOriginalIndex else null
                )
                val priorProjectionAdjusted = rootRoutePrivateBypassModuleIndex(originalIndex, earlierProjectedIndexes)
                addProperty("index", rootRouteIndexAfterSniff(priorProjectionAdjusted, rootRouteSniffOriginalIndex))
            }
        } else null
        // Schema v22 moves only the exact generated port-53 DNS hijack rule.
        val rootRouteDNSHijackPort53Rule = if (rootInbound != null) projectRootRouteDNSHijackPort53Rule(config.asJsonObject)?.apply {
            rootRouteDNSHijackPort53OriginalIndex?.let { addProperty("index", rootRouteIndexAfterSniff(it, rootRouteSniffOriginalIndex)) }
        } else null
        // Schema v23 moves only the exact generated protocol-DNS hijack rule.
        val rootRouteDNSProtocolHijackRule = if (rootInbound != null) projectRootRouteDNSProtocolHijackRule(config.asJsonObject)?.apply {
            rootRouteDNSProtocolHijackOriginalIndex?.let { addProperty("index", rootRouteIndexAfterSniff(it, rootRouteSniffOriginalIndex)) }
        } else null
        // Schema v24 moves only ConfigBuilder's exact optional sniff action after older route projections.
        val rootRouteSniffRule = if (rootInbound != null) projectRootRouteSniffRule(config.asJsonObject)?.apply {
            rootRouteSniffOriginalIndex?.let { addProperty("index", it) }
        } else null
        // Schema v26 moves only ConfigBuilder's exact optional resolve action after probe/sniff.
        val rootRouteResolveRule = if (rootInbound != null) projectRootRouteResolveRule(config.asJsonObject) else null
        // Schema v27 moves the fixed mode pair after all preceding route projections are restored.
        val rootRouteModeRules = if (rootInbound != null) projectRootRouteModeRules(config.asJsonObject)?.apply {
            rootRouteModeRulesOriginalIndex?.let { addProperty("index", it) }
        } else null
        val rootRouteDNSTargetRules = rootRouteDNSTargetRulesCandidate?.takeIf {
            removeRootRouteDNSTargetRules(config.asJsonObject, it)
        }
        // Schema v15 moves only ConfigBuilder's exact outbound-any DNS direct-resolver guard.
        val rootDNSOutboundAnyRule = if (rootInbound != null) projectRootDNSOutboundAnyRule(config.asJsonObject) else null
        // Schema v16 moves only the fixed address-family DNS reject rule when uniquely first.
        val rootDNSIPv6QueryRule = if (rootInbound != null) projectRootDNSIPv6QueryRule(config.asJsonObject) else null
        // Schema v7 passes static host source to the module; it owns parsing and the sing-box hosts server.
        val rootDNSHosts = if (rootInbound != null) projectRootDNSHosts(config.asJsonObject, DataStore.dnsHosts) else null
        // Schema v8 moves only DNS final selection/address-family behavior; server definitions and DNS rules remain transitional.
        val rootDNSPolicy = if (rootInbound != null) projectRootDNSPolicy(config.asJsonObject) else null
        // Legacy projections remain active if the complete raw-input contract is unavailable.
        val rootDNSLocalServer = if (rootDNSResolvers == null && rootInbound != null && rootDNSPolicy != null) projectRootDNSLocalServer(config.asJsonObject) else null
        val rootDNSDirectServer = if (rootDNSResolvers == null && rootDNSLocalServer != null) projectRootDNSDefaultDirectServer(config.asJsonObject) else null
        // Snapshot v3 combines module-owned ingress and proxy-group generation. Keep the
        // legacy group intact unless the Root inbound is also represented structurally.
        val rootProxyGroup = if (rootInbound != null) config.asJsonObject.getAsJsonArray("outbounds")?.let { outbounds ->
            val index = (0 until outbounds.size()).firstOrNull { i ->
                val item = outbounds[i]
                item.isJsonObject && item.asJsonObject.get("tag")?.asString == "proxy" &&
                    item.asJsonObject.get("type")?.asString in setOf("selector", "urltest", "loadbalance")
            }
            index?.let { i ->
                val outbound = outbounds[i].asJsonObject
                val members = outbound.getAsJsonArray("outbounds") ?: return@let null
                if (members.size() == 0) return@let null
                val spec = JsonObject().apply {
                    addProperty("type", outbound.get("type").asString)
                    addProperty("tag", "proxy")
                    add("members", members.deepCopy())
                    outbound.get("default")?.takeIf { !it.isJsonNull }?.let { add("default", it.deepCopy()) }
                    outbound.get("strategy")?.takeIf { !it.isJsonNull }?.let { add("strategy", it.deepCopy()) }
                    outbound.get("url")?.takeIf { !it.isJsonNull }?.let { add("url", it.deepCopy()) }
                    outbound.get("interval")?.takeIf { !it.isJsonNull }?.let { add("interval", it.deepCopy()) }
                    outbound.get("tolerance")?.takeIf { !it.isJsonNull }?.let { add("tolerance", it.deepCopy()) }
                    outbound.get("idle_timeout")?.takeIf { !it.isJsonNull }?.let { add("idleTimeout", it.deepCopy()) }
                    outbound.get("interrupt_exist_connections")?.takeIf { !it.isJsonNull }?.let { add("interruptExisting", it.deepCopy()) }
                }
                outbounds.remove(i)
                spec
            }
        } else null
        // Transitional typed migration: the module materializes simple SOCKS and Shadowsocks
        // outbounds. Keep chained, muxed, or otherwise extended objects in legacy config until
        // their full typed protocol has parity coverage.
        val rootOutbounds = JsonArray()
        if (rootProxyGroup != null) {
            val outbounds = config.asJsonObject.getAsJsonArray("outbounds")
            val indexes = (0 until (outbounds?.size() ?: 0)).filter { i ->
                val item = outbounds!![i]
                if (!item.isJsonObject) return@filter false
                val obj = item.asJsonObject
                val protocol = obj.get("type")?.asString ?: return@filter false
                if (protocol !in setOf("socks", "shadowsocks", "trojan", "http")) return@filter false
                val allowed = when (protocol) {
                    "socks" -> setOf("type", "tag", "server", "server_port", "username", "password", "version", "domain_strategy", "udp_over_tcp")
                    "shadowsocks" -> setOf("type", "tag", "server", "server_port", "method", "password", "plugin", "plugin_opts", "domain_strategy", "udp_over_tcp")
                    "trojan" -> setOf("type", "tag", "server", "server_port", "password", "tls")
                    else -> setOf("type", "tag", "server", "server_port", "username", "password", "tls")
                }
                if (obj.keySet().any { it !in allowed }) return@filter false
                val tag = obj.get("tag")?.takeIf { it.isJsonPrimitive }?.asString ?: return@filter false
                val server = obj.get("server")?.takeIf { it.isJsonPrimitive }?.asString ?: return@filter false
                val port = obj.get("server_port")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: return@filter false
                if (tag.isBlank() || server.isBlank() || port !in 1..65535) return@filter false
                if (protocol == "socks" && obj.get("version")?.asString !in setOf("4", "4a", "5")) return@filter false
                if (protocol == "shadowsocks" && (obj.get("method")?.asString.isNullOrBlank() || obj.get("password")?.asString == null)) return@filter false
                if (protocol == "trojan" && obj.get("password")?.asString.isNullOrBlank()) return@filter false
                if (protocol in setOf("http", "trojan")) {
                    val tls = obj.get("tls")?.takeUnless { it.isJsonNull }
                    if (protocol == "trojan" && tls == null) return@filter false
                    if (tls != null) {
                        if (!tls.isJsonObject) return@filter false
                        val tlsObj = tls.asJsonObject
                        if (tlsObj.keySet().any { it !in setOf("enabled", "insecure", "server_name", "alpn") }) return@filter false
                        if (tlsObj.get("enabled")?.let { !it.isJsonPrimitive || !it.asJsonPrimitive.isBoolean || !it.asBoolean } != false) return@filter false
                        if (tlsObj.get("server_name")?.let { !it.isJsonNull && (!it.isJsonPrimitive || !it.asJsonPrimitive.isString || it.asString.length > 253) } == true) return@filter false
                        if (tlsObj.get("insecure")?.let { !it.isJsonNull && (!it.isJsonPrimitive || !it.asJsonPrimitive.isBoolean) } == true) return@filter false
                        if (tlsObj.get("alpn")?.let { !it.isJsonNull && (!it.isJsonArray || it.asJsonArray.any { item -> !item.isJsonPrimitive || !item.asJsonPrimitive.isString || item.asString.isBlank() || item.asString.length > 64 }) } == true) return@filter false
                    }
                }
                true
            }
            indexes.asReversed().forEach { index ->
                val obj = outbounds!![index].asJsonObject
                val protocol = obj.get("type").asString
                val spec = JsonObject().apply {
                    addProperty("protocol", protocol)
                    listOf("tag", "server", "server_port", "username", "password", "version", "method", "plugin", "plugin_opts", "domain_strategy", "udp_over_tcp").forEach { key ->
                        obj.get(key)?.let { add(if (key == "server_port") "serverPort" else when (key) { "plugin_opts" -> "pluginOptions"; "domain_strategy" -> "domainStrategy"; "udp_over_tcp" -> "udpOverTcp"; else -> key }, it.deepCopy()) }
                    }
                    if (protocol in setOf("http", "trojan")) obj.get("tls")?.takeUnless { it.isJsonNull }?.asJsonObject?.let { tls ->
                        addProperty("tls", true)
                        tls.get("server_name")?.takeUnless { it.isJsonNull }?.let { add("tlsServerName", it.deepCopy()) }
                        tls.get("insecure")?.takeUnless { it.isJsonNull }?.let { add("tlsInsecure", it.deepCopy()) }
                        tls.get("alpn")?.takeUnless { it.isJsonNull }?.let { add("tlsAlpn", it.deepCopy()) }
                    }
                }
                rootOutbounds.add(spec)
                outbounds.remove(index)
            }
        }
        // Keep domain_regex rules in the App-generated legacy config until full matcher/action
        // parity is proven against the actual ConfigBuilder and pinned core. The raw-payload
        // contract alone does not establish that semantic equivalence.
        // v44 network->outbound rules are independent and may still project in the same snapshot.
        val rootRouteNetworkOutboundRules = if (rootInbound != null) {
            projectRootRouteNetworkOutboundRules(config.asJsonObject, domainRegexProjected = false)
        } else null
        // v45 moves only package-identity-only route generation; App-side UID resolution remains platform-bound.
        val rootRouteAppUIDRules = if (rootInbound != null) projectRootRouteAppUIDRules(config.asJsonObject) else null
        val rootRemoteRuleSets = if (rootInbound != null) projectRootRemoteRuleSets(config.asJsonObject) else false
        // v54 lets Root generate the plain rule_set -> outbound route matcher when all
        // referenced binary sets have moved; richer predicates stay on the existing path.
        val rootRemoteRuleSetOutboundRoutes = if (rootInbound != null && rootRemoteRuleSets) {
            projectRootRemoteRuleSetOutboundRoutes(config.asJsonObject)
        } else false
        // v64 constructs the remote rule_set AND numeric destination-port conjunction in Root.
        val rootRemoteRuleSetPortOutboundRoutes = if (rootInbound != null && rootRemoteRuleSets) {
            projectRootRemoteRuleSetPortOutboundRoutes(config.asJsonObject)
        } else false
        // v65 generates a remote rule-set membership AND ip_version outbound route.
        val rootRemoteRuleSetIPVersionOutboundRoutes = if (rootInbound != null && rootRemoteRuleSets) {
            projectRootRemoteRuleSetIPVersionOutboundRoutes(config.asJsonObject)
        } else false
        val rootRemoteRuleSetSourcePortOutboundRoutes = if (rootInbound != null && rootRemoteRuleSets) {
            projectRootRemoteRuleSetSourcePortOutboundRoutes(config.asJsonObject)
        } else false
        val rootRemoteRuleSetPortRangeOutboundRoutes = if (rootInbound != null && rootRemoteRuleSets) {
            projectRootRemoteRuleSetPortRangeOutboundRoutes(config.asJsonObject)
        } else false
        // v68 constructs migrated remote rule_set AND DNS-safe domain matcher routes in Root.
        val rootRemoteRuleSetDomainOutboundRoutes = if (rootInbound != null && rootRemoteRuleSets) {
            projectRootRemoteRuleSetDomainOutboundRoutes(config.asJsonObject)
        } else false
        // v69 generates a supported destination-CIDR AND migrated remote ruleset conjunction.
        val rootRemoteRuleSetIPOutboundRoutes = if (rootInbound != null && rootRemoteRuleSets) {
            projectRootRemoteRuleSetIPOutboundRoutes(config.asJsonObject)
        } else false
        // v70 moves migrated remote rule-set AND TCP/UDP network route conjunctions to Root.
        val rootRemoteRuleSetNetworkOutboundRoutes = if (rootInbound != null && rootRemoteRuleSets) {
            projectRootRemoteRuleSetNetworkOutboundRoutes(config.asJsonObject)
        } else false
        // v77 reconstructs migrated remote rule-set AND supported protocol-list outbounds in Root.
        val rootRemoteRuleSetProtocolOutboundRoutes = if (rootInbound != null && rootRemoteRuleSets) {
            projectRootRemoteRuleSetProtocolOutboundRoutes(config.asJsonObject)
        } else false
        // v78 lets Root materialize protocol-only -> outbound rules; richer rules remain legacy.
        val rootProtocolOutboundRoutes = if (rootInbound != null) projectRootProtocolOutboundRoutes(config.asJsonObject) else false
        // v71 reconstructs exact migrated rule_set -> reject route rules in Root.
        val rootRemoteRuleSetRejectRoutes = if (rootInbound != null && rootRemoteRuleSets) {
            projectRootRemoteRuleSetRejectRoutes(config.asJsonObject)
        } else false
        // v72 reconstructs migrated rule_set AND TCP/UDP network reject conjunctions in Root.
        val rootRemoteRuleSetNetworkRejectRoutes = if (rootInbound != null && rootRemoteRuleSets) {
            projectRootRemoteRuleSetNetworkRejectRoutes(config.asJsonObject)
        } else false
        // v73 reconstructs migrated rule_set AND destination-CIDR reject conjunctions in Root.
        val rootRemoteRuleSetIPRejectRoutes = if (rootInbound != null && rootRemoteRuleSets) {
            projectRootRemoteRuleSetIPRejectRoutes(config.asJsonObject)
        } else false
        // v74 constructs the migrated remote rule_set AND DNS-safe domain reject conjunction in Root.
        val rootRemoteRuleSetDomainRejectRoutes = if (rootInbound != null && rootRemoteRuleSets) {
            projectRootRemoteRuleSetDomainRejectRoutes(config.asJsonObject)
        } else false
        // v75 reconstructs migrated remote rule_set AND IP-version reject conjunctions in Root.
        val rootRemoteRuleSetIPVersionRejectRoutes = if (rootInbound != null && rootRemoteRuleSets) {
            projectRootRemoteRuleSetIPVersionRejectRoutes(config.asJsonObject)
        } else false
        // v55 constructs supported domain matcher route rules in Root.
        val rootDomainOutboundRoutes = if (rootInbound != null) hasRootDomainOutboundRouteMarker(config.asJsonObject) else false
        // v47 carries complete generated route/DNS rule objects only when every referenced
        // remote binary set has moved. Logical groups, inversion and DNS effects remain exact.
        val rootRemoteRuleSetReferences = if (rootInbound != null && rootRemoteRuleSets) {
            projectRootRemoteRuleSetReferences(config.asJsonObject)
        } else false
        // v56 lets Root reconstruct simple domain-ruleset DNS routing/reject/FakeDNS effects.
        val rootRemoteRuleSetDNSEffects = if (rootInbound != null && rootRemoteRuleSets) {
            projectRootRemoteRuleSetDNSEffects(config.asJsonObject)
        } else false
        // v49 moves NOT(OR(domain, IP, ...)) wrapper construction to Root, retaining
        // the exact ConfigBuilder children/action and original slot.
        val rootInvertedGroups = if (rootInbound != null) projectRootInvertedGroups(config.asJsonObject) else false
        // v50 contains typed exact-domain outbound markers; Root validates and generates them.
        val rootExactDomainOutbound = if (rootInbound != null) hasRootExactDomainOutboundRouteMarker(config.asJsonObject) else false
        // v48 carries remaining non-regex route/DNS rule JSON after typed projections.
        val rootRawRules = if (rootInbound != null) projectRootRouteRawRules(config.asJsonObject) else false
        val rootCompositeDomainIPOutbound = if (rootInbound != null) rebaseRootCompositeDomainIPOutboundMarkers(config.asJsonObject) else false
        val rootIPOutboundRoute = if (rootInbound != null) rebaseRootIPOutboundMarkers(config.asJsonObject) else false
        // v60 generates the exact source-CIDR AND destination-CIDR outbound conjunction.
        val rootSourceDestinationIPOutboundRoutes = if (rootInbound != null) rebaseRootSourceDestinationIPOutboundMarkers(config.asJsonObject) else false
        // v61 generates source-CIDR-only outbound routes with an explicit marker and legacy fallback.
        val rootSourceIPOutboundRoutes = if (rootInbound != null) rebaseRootSourceIPOutboundMarkers(config.asJsonObject) else false
        // v85 carries closed destination/source port-range-only route markers.
        val rootPortRangeOutboundRoutes = if (rootInbound != null) rebaseRootPortRangeOutboundMarkers(config.asJsonObject) else false
        val rootSourcePortRangeOutboundRoutes = if (rootInbound != null) rebaseRootSourcePortRangeOutboundMarkers(config.asJsonObject) else false
        // v63 generates source-port-only outbound routes; other predicates remain legacy.
        val rootSourcePortOutboundRoutes = if (rootInbound != null) rebaseRootSourcePortOutboundMarkers(config.asJsonObject) else false
        // v82 generates exact numeric-port-only outbound routes; all richer predicates stay in ConfigBuilder.
        val rootPortOutboundRoutes = if (rootInbound != null) rebaseRootPortOutboundMarkers(config.asJsonObject) else false
        // v62 generates a standalone user IP-version outbound route; richer predicates remain legacy.
        val rootIPVersionOutboundRoutes = if (rootInbound != null) rebaseRootIPVersionOutboundMarkers(config.asJsonObject) else false
        // v59 generates supported domain AND numeric-port outbound route markers in Root.
        val rootDomainPortOutboundRoutes = if (rootInbound != null) rebaseRootDomainPortOutboundMarkers(config.asJsonObject) else false
        // v58 generates a bounded domain AND TCP/UDP network outbound route in Root.
        val rootDomainNetworkOutboundRoutes = if (rootInbound != null) rebaseRootDomainNetworkOutboundMarkers(config.asJsonObject) else false
        // v57 generates exact network AND destination-port outbound route markers in Root.
        val rootNetworkPortOutboundRoutes = if (rootInbound != null) rebaseRootNetworkPortOutboundRouteMarkers(config.asJsonObject) else false
        // v67 generates eligible domain AND IP-version outbound route conjunctions in Root.
        val rootDomainIPVersionOutboundRoutes = if (rootInbound != null) {
            rebaseRootDomainIPVersionOutboundMarkers(config.asJsonObject)
        } else false
        val rootDNSUserRules = if (rootInbound != null) config.asJsonObject.getAsJsonObject("dns")
            ?.getAsJsonArray("rules")?.any { it.isJsonObject && it.asJsonObject.has(io.nekohasekai.sagernet.route.ROOT_EXACT_DOMAIN_DNS_RULE_MARKER) } == true else false
        // v79 adds Root generation for package-identity-only DNS side effects.
        val rootAppIdentityDNSRules = if (rootInbound != null) projectRootAppIdentityDNSRules(config.asJsonObject) else false
        // v80 adds Root generation for package-identity AND supported-domain DNS effects.
        val rootAppIdentityDomainDNSRules = if (rootInbound != null) projectRootAppIdentityDomainDNSRules(config.asJsonObject) else false
        // v81 generates supported package identity AND domain routes in Root.
        val rootAppUIDDomainOutboundRoutes = if (rootInbound != null) projectRootAppUIDDomainOutboundRules(config.asJsonObject) else null
        val rootAppUIDRuleSetOutboundRoutes = if (rootInbound != null) projectRootAppUIDRuleSetOutboundRules(config.asJsonObject) else null
        val rootAppUIDDomainRuleSetOutboundRoutes = if (rootInbound != null) projectRootAppUIDDomainRuleSetOutboundRules(config.asJsonObject) else null
        val encoded = JsonObject(); files.forEach { (name, bytes) -> encoded.addProperty(name, Base64.getEncoder().encodeToString(bytes)) }
        return JsonObject().apply {
            val schema = when { rootPortRangeOutboundRoutes || rootSourcePortRangeOutboundRoutes -> 85; rootAppUIDDomainRuleSetOutboundRoutes != null -> 84; rootAppUIDRuleSetOutboundRoutes != null -> 83; rootPortOutboundRoutes -> 82; rootAppUIDDomainOutboundRoutes != null -> 81; rootAppIdentityDomainDNSRules -> 80; rootAppIdentityDNSRules -> 79; rootProtocolOutboundRoutes -> 78; rootRemoteRuleSetProtocolOutboundRoutes -> 77; rootRemoteRuleSetPortRangeOutboundRoutes -> 76; rootRemoteRuleSetIPVersionRejectRoutes -> 75; rootRemoteRuleSetDomainRejectRoutes -> 74; rootRemoteRuleSetIPRejectRoutes -> 73; rootRemoteRuleSetNetworkRejectRoutes -> 72; rootRemoteRuleSetRejectRoutes -> 71; rootRemoteRuleSetNetworkOutboundRoutes -> 70; rootRemoteRuleSetIPOutboundRoutes -> 69; rootRemoteRuleSetDomainOutboundRoutes -> 68; rootDomainIPVersionOutboundRoutes -> 67; rootRemoteRuleSetSourcePortOutboundRoutes -> 66; rootRemoteRuleSetIPVersionOutboundRoutes -> 65; rootRemoteRuleSetPortOutboundRoutes -> 64; rootSourcePortOutboundRoutes -> 63; rootIPVersionOutboundRoutes -> 62; rootSourceIPOutboundRoutes -> 61; rootSourceDestinationIPOutboundRoutes -> 60; rootDomainPortOutboundRoutes -> 59; rootDomainNetworkOutboundRoutes -> 58; rootNetworkPortOutboundRoutes -> 57; rootRemoteRuleSetDNSEffects -> 56; rootDomainOutboundRoutes -> 55; rootRemoteRuleSetOutboundRoutes -> 54; rootDNSUserRules -> 53; rootIPOutboundRoute -> 52; rootCompositeDomainIPOutbound -> 51; rootExactDomainOutbound -> 50; rootInvertedGroups -> 49; rootRawRules -> 48; rootRemoteRuleSetReferences -> 47; rootRemoteRuleSets -> 46; rootRouteAppUIDRules != null -> 45; rootRouteNetworkOutboundRules != null -> 44; rootRouteDomainKeywordRejectRules != null -> 42; rootRouteDomainSuffixRejectRules != null -> 41; rootRouteDomainRejectRules != null -> 40; rootRouteProtocolPortRejectRules != null -> 39; rootRouteSourceDestinationPortRejectRules != null -> 38; rootRouteSourceDestinationIPRejectRules != null -> 37; rootRouteIPRejectRules != null -> 36; rootRouteSourceIPRejectRules != null -> 35; rootRouteProtocolRejectRules != null -> 34; rootRouteSourcePortRejectRules != null -> 33; rootRouteNetworkPortRejectRules != null -> 32; rootRouteNetworkRejectRules != null -> 31; rootRoutePortRejectRules != null -> 30; rootDNSResolvers != null -> 29; rootRouteDNSTargetRules != null -> 28; rootRouteModeRules != null -> 27; rootRouteResolveRule != null -> 26; rootRouteProbeRule != null -> 25; rootRouteSniffRule != null -> 24; rootRouteDNSProtocolHijackRule != null -> 23; rootRouteDNSHijackPort53Rule != null -> 22; rootRoutePrivateBypass != null -> 21; rootRouteIPVersionRule != null -> 20; rootRouteBypassLan != null -> 19; rootRouteDNSProtocolRule != null -> 18; rootRouteDNSPort53Rule != null -> 17; rootDNSIPv6QueryRule != null -> 16; rootDNSOutboundAnyRule != null -> 15; rootRouteMulticast != null -> 14; rootRouteLoopback != null -> 13; rootRouteFinal != null -> 12; rootDNSDirectServer != null -> 11; rootDNSLocalServer != null -> 10; rootDNSPolicy?.has("independentCache") == true -> 9; rootDNSPolicy != null -> 8; rootDNSHosts != null -> 7; rootRoutePolicy != null -> 6; rootOutbounds.any { it.asJsonObject.get("protocol")?.asString in setOf("http", "trojan") } -> 5; rootOutbounds.size() > 0 -> 4; rootProxyGroup != null -> 3; rootInbound != null -> 2; else -> 1 }
            addProperty("schemaVersion", schema); addProperty("expectedRevision", revision); add("config", config); add("files", encoded); add("plugins", plugins)
            rootInbound?.let { add("rootInbound", it) }
            rootProxyGroup?.let { add("rootProxyGroup", it) }
            if (rootOutbounds.size() > 0) add("rootOutbounds", rootOutbounds)
            rootRoutePolicy?.let { add("rootRoutePolicy", it) }
            rootDNSHosts?.let { add("rootDnsHosts", it) }
            rootDNSPolicy?.let { add("rootDnsPolicy", it) }
            rootDNSLocalServer?.let { add("rootDnsLocalServer", it) }
            rootDNSDirectServer?.let { add("rootDnsDirectServer", it) }
            rootRouteFinal?.let { add("rootRouteFinal", it) }
            rootRouteLoopback?.let { add("rootRouteLoopback", it) }
            rootRouteMulticast?.let { add("rootRouteMulticast", it) }
            rootRouteBypassLan?.let { add("rootRouteBypassLan", it) }
            rootDNSOutboundAnyRule?.let { add("rootDnsOutboundAnyRule", it) }
            rootDNSIPv6QueryRule?.let { add("rootDnsIPv6QueryRule", it) }
            rootRouteDNSPort53Rule?.let { add("rootRouteDnsPort53Rule", it) }
            rootRouteDNSProtocolRule?.let { add("rootRouteDnsProtocolRule", it) }
            rootRouteIPVersionRule?.let { add("rootRouteIpVersionRule", it) }
            rootRoutePrivateBypass?.let { add("rootRoutePrivateBypass", it) }
            rootRouteDNSHijackPort53Rule?.let { add("rootRouteDnsHijackPort53Rule", it) }
            rootRouteDNSProtocolHijackRule?.let { add("rootRouteDnsProtocolHijackRule", it) }
            rootRouteSniffRule?.let { add("rootRouteSniffRule", it) }
            rootRouteProbeRule?.let { add("rootRouteProbeRule", it) }
            rootRouteResolveRule?.let { add("rootRouteResolveRule", it) }
            rootRouteModeRules?.let { add("rootRouteModeRules", it) }
            rootRouteDNSTargetRules?.let { add("rootRouteDnsTargetRules", it) }
            rootDNSResolvers?.let { add("rootDnsResolvers", it) }
            rootRoutePortRejectRules?.let { add("rootRoutePortRejectRules", it) }
            rootRouteNetworkRejectRules?.let { add("rootRouteNetworkRejectRules", it) }; rootRouteNetworkPortRejectRules?.let { add("rootRouteNetworkPortRejectRules", it) }
            rootRouteSourcePortRejectRules?.let { add("rootRouteSourcePortRejectRules", it) }
            rootRouteProtocolRejectRules?.let { add("rootRouteProtocolRejectRules", it) }
            rootRouteSourceIPRejectRules?.let { add("rootRouteSourceIPRejectRules", it) }
            rootRouteIPRejectRules?.let { add("rootRouteIPRejectRules", it) }
            rootRouteSourceDestinationIPRejectRules?.let { add("rootRouteSourceDestinationIPRejectRules", it) }
            rootRouteSourceDestinationPortRejectRules?.let { add("rootRouteSourceDestinationPortRejectRules", it) }
            rootRouteProtocolPortRejectRules?.let { add("rootRouteProtocolPortRejectRules", it) }
            rootRouteDomainRejectRules?.let { add("rootRouteDomainRejectRules", it) }
            rootRouteDomainSuffixRejectRules?.let { add("rootRouteDomainSuffixRejectRules", it) }; rootRouteDomainKeywordRejectRules?.let { add("rootRouteDomainKeywordRejectRules", it) }
            rootRouteNetworkOutboundRules?.let { add("rootRouteNetworkOutboundRules", it) }
            rootRouteAppUIDRules?.let { add("rootRouteAppUidRules", it) }
            rootAppUIDDomainOutboundRoutes?.let { add("rootAppUidDomainOutboundRoutes", it) }
            rootAppUIDRuleSetOutboundRoutes?.let { add("rootAppUidRuleSetOutboundRoutes", it) }
            rootAppUIDDomainRuleSetOutboundRoutes?.let { add("rootAppUidDomainRuleSetOutboundRoutes", it) }
            addProperty("autoStart", DataStore.persistAcrossReboot); addProperty("performancePriority", DataStore.performancePriorityMode); addProperty("profileId", instance.profile.id); addProperty("profileName", instance.displayProfileName)
        }
    }
}
