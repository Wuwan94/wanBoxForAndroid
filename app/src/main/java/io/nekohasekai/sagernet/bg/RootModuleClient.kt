// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.bg


import com.google.gson.JsonObject
import com.google.gson.JsonParser
import android.os.SystemClock
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.core.CoreAssets
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.RootInboundBackend
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.bg.proto.ProxyInstance
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Base64

/** Root-only CLI bridge; the App never owns core processes. @author 雾晚 */
object RootModuleClient {
    class OutboundReferenceWarningRequired(val warning: OutboundReferenceWarning) : Exception("outbound_confirmation_required")

    private val pendingOutboundWarning = java.util.concurrent.atomic.AtomicReference<OutboundReferenceWarning?>()

    private suspend fun prepareOutboundWarningLocked(
        startIfStopped: Boolean,
        snapshot: io.nekohasekai.sagernet.route.RootRouteBuildSnapshot,
    ): OutboundReferenceWarning? {
        val issues = snapshot.issues()
        if (issues.isEmpty()) {
            pendingOutboundWarning.set(null)
            return null
        }
        return pendingOutboundWarning.get()?.takeIf { it.issues == issues && it.startIfStopped == startIfStopped }
            ?: OutboundReferenceWarning(java.util.UUID.randomUUID().toString(), startIfStopped, issues)
                .also { pendingOutboundWarning.set(it) }
    }

    suspend fun prepareOutboundWarning(startIfStopped: Boolean): OutboundReferenceWarning? =
        withContext(Dispatchers.IO) { changes.withLock {
            prepareOutboundWarningLocked(startIfStopped, io.nekohasekai.sagernet.route.RootRouteBuildSnapshot.capture())
        } }

    fun cancelOutboundWarning(requestToken: String) {
        val warning = pendingOutboundWarning.get()
        if (warning?.requestToken == requestToken) pendingOutboundWarning.compareAndSet(warning, null)
    }

    suspend fun continueOutboundWarning(requestToken: String, startIfStopped: Boolean) = withContext(Dispatchers.IO) {
        // Claim ownership once, without reading the database that installer restore may replace.
        val confirmedWarning = pendingOutboundWarning.get()
        if (confirmedWarning == null || confirmedWarning.requestToken != requestToken ||
            confirmedWarning.startIfStopped != startIfStopped ||
            !pendingOutboundWarning.compareAndSet(confirmedWarning, null))
            throw IOException("outbound_confirmation_expired")
        RootModuleDataUpdate.applyInstallerSelection()
        changes.withLock {
            // Revalidate restored/current rules under the same lock as config application.
            startOrReloadLocked(startIfStopped, confirmedWarning = confirmedWarning)
        }
    }

    // @author 雾晚: this is the manager-owned root module, never App private storage.
    @android.annotation.SuppressLint("SdCardPath")
    const val CLI = "/data/adb/modules/wanbox/bin/wanboxctl"
    private val commands = setOf("status", "module", "logs", "dashboard", "start", "stop", "resume", "restart", "reload",
        "config apply", "config validate", "autostart on", "autostart off", "data prepare", "data finish", "data rollback",
        "node failover", "node tags", "diagnostics export")
    private val changes = Mutex()
    // @author 雾晚: status cache TTL. The foreground observer samples every
    // 2s, but its effective period is the 2s delay plus the sample itself
    // (one su spawn costs 100-300ms), so a 2s TTL had already expired before
    // nearly every sample and the observer still paid a su spawn per cycle.
    // 4s keeps the freshness bound tight while letting the cache actually
    // serve the poller: observer spawns drop to roughly one per two cycles.
    // Decision paths never trust this cache (toggle/enable read fresh via
    // callFreshStatus, urlTest invalidates first, and every mutation
    // invalidates before and after), so only passive views age by <= TTL.
    private const val STATUS_TTL_MS = 4000L
    // Status and dashboard are distinct protocol views; never serve one in place of the other.
    private val statusCache = RootModuleStatusCache(STATUS_TTL_MS) { SystemClock.elapsedRealtime() }
    // @author 雾晚: coalesce concurrent status fetches; each su spawn costs 100-300ms.
    private val statusMutex = Mutex()
    private val readOnlyCommands = setOf("status", "module", "logs", "dashboard", "config validate", "node tags")
    internal fun mutatesModule(command: String): Boolean = command !in readOnlyCommands
    // @author 雾晚: loopback read channel (see RootModuleChannel.kt). Status
    // and dashboard reads prefer it over a fresh `su` spawn; any failure
    // drops back to the bridge and cools discovery down for 30s, so modules
    // without channel support pay at most one extra spawn per window.
    private const val CHANNEL_COOLDOWN_MS = 30_000L
    private val channelGate = RootModuleChannelGate(CHANNEL_COOLDOWN_MS) { SystemClock.elapsedRealtime() }
    /** Drop every cached protocol view before and after state-changing calls. */
    fun invalidateStatusCache() {
        statusCache.invalidate()
        // The supervisor may have been replaced alongside the views; force
        // channel rediscovery instead of trusting a stale port/token.
        channelGate.dropInfo()
    }

    // v3.0.15 hot-update awareness: the module broadcasts
    // "module_hot_update_committed:<versionCode>" on the status event
    // channel after a no-reboot switch commits. Every status poll and every
    // reconnect passes through parseResponse, so a foreground App learns the
    // new module version here and republishes it — no App restart needed.
    private val _moduleVersionFlow = MutableStateFlow(0)
    val moduleVersionFlow: StateFlow<Int> = _moduleVersionFlow.asStateFlow()

    private fun noteModuleEvents(events: List<ModuleEvent>) {
        for (event in events) {
            if (!event.code.startsWith("module_hot_update_committed:")) continue
            val code = event.code.substringAfter(':').toIntOrNull() ?: continue
            if (code > _moduleVersionFlow.value) _moduleVersionFlow.value = code
        }
    }
    data class InstallSelection(val id: String, val mode: Int)
    data class Status(val phase: String, val revision: String, val runningRevision: String,
        val profileId: Long, val profileName: String, val stats: RootNotificationSample?, val error: String = "",
        val installData: InstallSelection? = null,
        val telemetry: ModuleTelemetry? = null,
        // phase2: optional module health fields (older modules omit them)
        val health: String = "", val lastExit: String = "", val lastReason: String = "",
        val retryCount: Int = 0, val events: List<ModuleEvent> = emptyList(),
        val profileTags: Map<Long, String> = emptyMap(),
        val lastExitAt: Long = 0L) {
        /** 异常: module failed / needs cleanup, or connected but the data plane is degraded. */
        val abnormal get() = installData == null && (phase == "failed" || phase == "cleanup_required" ||
            (phase == "connected" && health == "degraded"))
        val connected get() = phase == "connected" && installData == null
        val state get() = if (installData != null) BaseService.State.Stopped else when (phase) {
            "connected" -> BaseService.State.Connected
            "suspended" -> BaseService.State.Suspended
            "starting" -> BaseService.State.Connecting
            else -> BaseService.State.Stopped
        }
    }
    data class ModuleEvent(val time: Long, val code: String)
    private val safeCode = Regex("[a-z_]{1,80}(:[0-9]{1,12})?")
    private fun JsonObject.safeString(key: String): String =
        this[key]?.takeIf { it.isJsonPrimitive }?.asString.orEmpty().takeIf { it.matches(safeCode) }.orEmpty()
    fun parseResponse(text: String): Status {
        val json = JsonParser.parseString(text).asJsonObject
        if (json["schemaVersion"]?.asInt != 1) throw IOException("module_protocol_mismatch")
        if (json["ok"]?.asBoolean != true) {
            val code = json["error"]?.asString.orEmpty()
            throw IOException(code.takeIf { it.matches(Regex("[a-z_]{1,80}")) } ?: "module_operation_failed")
        }
        val state = json.getAsJsonObject("state") ?: throw IOException("module_status_invalid")
        val phase = state["phase"]?.asString ?: throw IOException("module_status_invalid")
        if (phase !in setOf("connected", "starting", "suspended", "stopped", "failed", "disabled", "cleanup_required")) throw IOException("module_status_invalid")
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
        val profileTags = state["profileTags"]?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.mapNotNull { (rawId, value) ->
            val id = rawId.toLongOrNull()?.takeIf { it > 0L && it.toString() == rawId }
            val tag = value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                ?.takeIf { it.isNotBlank() && it.length <= 256 && it.none { ch -> ch.code < 0x20 || ch.code == 0x7f } }
            if (id != null && tag != null) id to tag else null
        }?.toMap().orEmpty()
        val events = state["events"]?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { item ->
            val obj = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val code = obj.safeString("code").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            ModuleEvent(obj["time"]?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L, code)
        }.orEmpty()
        noteModuleEvents(events)
        return Status(phase, state["revision"]?.asString.orEmpty(), state["runningRevision"]?.asString.orEmpty(),
            state["profileId"]?.asLong ?: 0, state["profileName"]?.asString.orEmpty(), stats,
            state["error"]?.asString.orEmpty().takeIf { it.matches(Regex("[a-z_]{1,80}")) }.orEmpty(), selection,
            telemetry,
            health = state.safeString("health"), lastExit = state.safeString("lastExit"),
            lastReason = state.safeString("lastReason"),
            retryCount = state["retryCount"]?.takeIf { it.isJsonPrimitive }?.asInt ?: 0,
            events = events, profileTags = profileTags,
            lastExitAt = state["lastExitAt"]?.takeUnless { it.isJsonNull }?.asLong ?: 0L)
    }
    suspend fun call(command: String, input: File? = null): Status = withContext(Dispatchers.IO) {
        require(command in commands || command.matches(Regex("data selection-finish [a-f0-9]{32}"))
                || command.matches(Regex("node select [1-9][0-9]{0,18}"))
                || command.matches(Regex("node (urltest|speedtest) [1-9][0-9]{0,18}")))
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
                    val value = statusCache.get(query) ?: fetchReadLocked(query).also {
                        statusCache.putIfGeneration(query, it, cacheGeneration)
                    }
                    refreshStaleSiblingLocked(query, cacheGeneration)
                    value
                }
            }
            return@withContext doCall(command, input)
        } finally {
            if (mutating) statusCache.invalidate()
        }
    }

    /**
     * Read the module's current status without trusting the UI polling TTL cache.
     * Freshness is about bypassing the cache, not about the transport: the
     * loopback channel is a live round-trip to the same supervisor that owns
     * the state the su bridge reads, so a channel GET satisfies the decision
     * paths (toggle/enable) exactly like a bridge read, and any channel
     * failure falls back to the bridge before a decision is made.
     */
    suspend fun callFreshStatus(): Status = withContext(Dispatchers.IO) {
        statusMutex.withLock {
            statusCache.invalidate()
            val cacheGeneration = statusCache.generation()
            fetchReadLocked(RootModuleReadQuery.STATUS).also {
                statusCache.putIfGeneration(RootModuleReadQuery.STATUS, it, cacheGeneration)
            }
        }
    }

    internal var commandProcess: (String) -> Process = { command ->
        ProcessBuilder("su", "-c", "exec $CLI $command").start()
    }

    /**
     * Fetch one read view: loopback channel first, su bridge on any channel
     * failure. Caller must hold [statusMutex]; channel discovery may spawn
     * one bridge call inside the lock, the same cost profile the bridge-only
     * read always had. Mutations never pass through here.
     */
    private suspend fun fetchReadLocked(query: RootModuleReadQuery): Status =
        channelRead(query) ?: doCall(readCommand(query))

    private fun readCommand(query: RootModuleReadQuery): String = when (query) {
        RootModuleReadQuery.STATUS -> "status"
        RootModuleReadQuery.DASHBOARD -> "dashboard"
    }

    /**
     * One read over the loopback channel, or null when the channel is
     * unavailable or fails (the gate has then dropped it into cooldown and
     * the caller falls back to the bridge, whose result or error is the one
     * surfaced — a channel error never reaches a caller directly).
     */
    private suspend fun channelRead(query: RootModuleReadQuery): Status? {
        val info = channelInfoLocked() ?: return null
        return try {
            parseModuleResponse(RootModuleChannelTransport.get(info, query))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            channelGate.fail()
            null
        }
    }

    /**
     * Discover the channel coordinates through the bridge's `channel`
     * command and cache them process-wide. Discovery failure (an older
     * module without the command, malformed output) enters the same
     * cooldown as a channel failure. Caller must hold [statusMutex].
     */
    private suspend fun channelInfoLocked(): RootModuleChannelInfo? {
        channelGate.info()?.let { return it }
        if (!channelGate.attemptAllowed()) return null
        val info = try {
            parseChannelInfo(doCallRaw("channel"))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (info == null) {
            channelGate.fail()
            return null
        }
        channelGate.store(info)
        return info
    }

    /**
     * Long-poll the status view over the loopback channel: the supervisor
     * holds the request until the module revision leaves [sinceRevision]
     * or [waitSeconds] elapse, then answers with the current view, which is
     * also published into the read cache so passive readers reuse it
     * instead of issuing their own request. Returns null when the channel
     * is unavailable or fails; callers then keep their normal cadence.
     * Caller cancellation never trips the channel gate — a cancelled poll
     * says nothing about channel health.
     */
    suspend fun awaitStatusChange(sinceRevision: String, waitSeconds: Int = 25): Status? =
        withContext(Dispatchers.IO) {
            require(waitSeconds in 1..30)
            // Captured before the wait: any invalidation during the poll
            // (a mutation landed meanwhile) must veto the cache refill.
            val cacheGeneration = statusCache.generation()
            val info = statusMutex.withLock { channelInfoLocked() } ?: return@withContext null
            val status = try {
                parseModuleResponse(RootModuleChannelTransport.get(
                    info, RootModuleReadQuery.STATUS,
                    waitSeconds = waitSeconds, sinceRevision = sinceRevision))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                channelGate.fail()
                return@withContext null
            }
            statusMutex.withLock {
                statusCache.putIfGeneration(RootModuleReadQuery.STATUS, status, cacheGeneration)
            }
            status
        }

    /**
     * Aggregated polling for the two read views. Status and dashboard stay
     * distinct protocol views (one is never served in place of the other),
     * but their consumers poll in the same foreground cycles, so when a read
     * already paid for [statusMutex] it also refreshes the sibling view if a
     * previous consumer left a stale entry behind; the sibling's next read
     * is then a cache hit instead of a second su spawn moments later. A view
     * nobody ever requested is never fetched speculatively. Sibling failures
     * are swallowed: the requested read keeps its own result and error
     * contract, and the sibling's own read will spawn and surface its own
     * error, so every actual read stays fail-closed. The value is cached
     * under the caller's generation, so a mutation that invalidated while
     * the sibling fetch was in flight still wins. Caller must hold
     * [statusMutex]; [callFreshStatus] deliberately skips this aggregation
     * because decision paths must not wait on an unrelated view.
     */
    private suspend fun refreshStaleSiblingLocked(fetched: RootModuleReadQuery, cacheGeneration: Long) {
        val sibling = when (fetched) {
            RootModuleReadQuery.STATUS -> RootModuleReadQuery.DASHBOARD
            RootModuleReadQuery.DASHBOARD -> RootModuleReadQuery.STATUS
        }
        if (!statusCache.hasStaleEntry(sibling)) return
        val value = try {
            fetchReadLocked(sibling)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        statusCache.putIfGeneration(sibling, value, cacheGeneration)
    }

    private suspend fun doCall(command: String, input: File? = null): Status =
        parseModuleResponse(doCallRaw(command, input))

    /**
     * The one validation path for module responses: bridge output and
     * loopback-channel output are byte-identical by contract and both land
     * here, so the channel never relaxes a check the bridge enforces.
     */
    private fun parseModuleResponse(response: String): Status = try {
        parseResponse(response)
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        throw e
    } catch (_: Exception) {
        throw IOException("root_or_module_unavailable")
    }

    /** Run one bridge command and return its raw stdout, unparsed. */
    private suspend fun doCallRaw(command: String, input: File? = null): String {
        val process = try { commandProcess(command) }
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
                    withCallTimeout(command) {
                        runInterruptible(Dispatchers.IO) { process.waitFor() }
                        writer.join(); errors.join()
                        output.await()
                    }
                } finally {
                    process.destroy()
                    runCatching { process.inputStream.close() }; runCatching { process.errorStream.close() }; runCatching { process.outputStream.close() }
                    writer.cancel(); errors.cancel(); output.cancel()
                }
            }
        } finally { process.destroy() }
    }
    internal suspend fun <T> withCallTimeout(command: String, block: suspend () -> T): T {
        val timeoutMs = when {
            command in setOf("status", "dashboard", "module", "logs", "channel", "node tags") || command.startsWith("node select ") -> 15_000L
            command == "node failover" -> 60_000L
            // B5（N1）：Root 测试会话的 Root 侧时限为超时 + 60/120 秒收尾；CLI 侧为 5 分钟。
            command.startsWith("node urltest ") || command.startsWith("node speedtest ") -> 300_000L
            else -> 720_000L
        }
        return try {
            withTimeout(timeoutMs) { block() }
        } catch (error: TimeoutCancellationException) {
            // Only this operation's deadline becomes a read failure; caller cancellation stays cancellation.
            currentCoroutineContext().ensureActive()
            throw IOException("module_timeout", error)
        }
    }
    fun safeError(error: Exception): String = error.message?.takeIf { it.matches(Regex("[a-z_]{1,80}")) } ?: "module_operation_failed"

    // B5：结果返回型命令（diagnostics export / node urltest / node speedtest）
    // 的 stdout 是结果 JSON 或 {"error":"<stable_token>"}；非 JSON 一律按协议错误处理。
    private fun parseResultJson(raw: String): JSONObject = try {
        JSONObject(raw)
    } catch (_: Exception) {
        throw IOException("module_status_invalid")
    }

    private fun resultError(json: JSONObject): String? =
        json.optString("error").takeIf { it.isNotEmpty() }

    // B5（N2）：导出脱敏诊断包。stdin 传 DiagnosticsRequest JSON；成功时返回
    // 缺失条目清单（无缺失为空列表），失败时抛稳定错误 token 的 IOException。
    // 目标必须是系统 Download 目录下的 wanbox-diagnostics-*.tar.gz，
    // 由 wanboxctl 再校验一次；不改变模块状态，不走状态缓存。
    suspend fun exportDiagnostics(destination: String, versions: DiagnosticsVersions): List<String> =
        withContext(Dispatchers.IO) {
            val file = File.createTempFile("diagnostics-request-", ".json", SagerNet.application.cacheDir)
            try {
                file.writeText(JSONObject().apply {
                    put("destination", destination)
                    put("appVersion", versions.appVersion)
                    put("appVersionCode", versions.appVersionCode)
                    put("previewVersion", versions.previewVersion)
                    put("previewVersionCode", versions.previewVersionCode)
                    put("singBoxVersion", versions.singBoxVersion)
                }.toString())
                val json = parseResultJson(doCallRaw("diagnostics export", file))
                resultError(json)?.let { throw IOException(it) }
                val missing = json.optJSONArray("missing") ?: throw IOException("module_status_invalid")
                List(missing.length()) { missing.optString(it) }
            } finally {
                file.delete()
            }
        }

    // B5（N1）：停止态 URL 探测。configJson 为 App 生成的最小测试配置
    // （forTest=true），经 stdin 传给 wanboxctl，由 Root 侧短生命周期
    // 测试会话执行；返回毫秒延迟。
    suspend fun urlTest(profileId: Long, configJson: String, url: String, timeoutMs: Int): Int =
        withContext(Dispatchers.IO) {
            require(profileId > 0)
            require(timeoutMs in 1_000..60_000)
            val file = File.createTempFile("node-urltest-", ".json", SagerNet.application.cacheDir)
            try {
                file.writeText(JSONObject().apply {
                    put("config", configJson)
                    put("url", url)
                    put("timeoutMs", timeoutMs)
                }.toString())
                val json = parseResultJson(doCallRaw("node urltest $profileId", file))
                resultError(json)?.let { throw IOException(it) }
                val delay = json.optInt("delayMs", -1)
                if (delay !in 1..60_000) throw IOException("node_test_invalid_result")
                delay
            } finally {
                file.delete()
            }
        }

    // B5（N1）：停止态速度测试。与 urlTest 同为 Root 侧短生命周期测试会话；
    // 结果 JSON 的字段名与 libcore.SpeedTestResult 的 Go 字段名一致。
    suspend fun speedTest(
        profileId: Long,
        configJson: String,
        mode: String,
        timeoutMs: Int,
        serverListURL: String,
        fallbackServerListURL: String,
        simpleDownloadURL: String,
    ): RootSpeedTestResult = withContext(Dispatchers.IO) {
        require(profileId > 0)
        val file = File.createTempFile("node-speedtest-", ".json", SagerNet.application.cacheDir)
        try {
            file.writeText(JSONObject().apply {
                put("config", configJson)
                put("mode", mode)
                put("timeoutMs", timeoutMs)
                put("serverListURL", serverListURL)
                put("fallbackServerListURL", fallbackServerListURL)
                put("simpleDownloadURL", simpleDownloadURL)
            }.toString())
            val json = parseResultJson(doCallRaw("node speedtest $profileId", file))
            resultError(json)?.let { throw IOException(it) }
            RootSpeedTestResult.parse(json)
        } finally {
            file.delete()
        }
    }
    suspend fun startOrReload(startIfStopped: Boolean = true): Status = withContext(Dispatchers.IO) {
        RootModuleDataUpdate.applyInstallerSelection()
        if (!startIfStopped) changes.lock()
        else if (!changes.tryLock()) throw IOException("module_busy")
        try {
            startOrReloadLocked(startIfStopped)
        } finally { changes.unlock() }
    }

    internal suspend fun <T> tryWithChangesLock(block: suspend () -> T): T {
        if (!changes.tryLock()) throw IOException("module_busy")
        try { return block() } finally { changes.unlock() }
    }

    /** Atomically read fresh state, decide the toggle, and execute it under the shared mutation lock. */
    internal suspend fun toggleService(profileId: Long = -1L): ServiceToggleAction = withContext(Dispatchers.IO) {
        RootModuleDataUpdate.applyInstallerSelection()
        tryWithChangesLock {
            val status = callFreshStatus()
            when (val action = serviceToggleAction(status.state, profileId, status.profileId)) {
                ServiceToggleAction.IGNORE -> action
                ServiceToggleAction.STOP -> {
                    call("stop")
                    action
                }
                ServiceToggleAction.RESUME -> { call("resume"); action }
                ServiceToggleAction.START -> {
                    if (profileId >= 0L) DataStore.selectedProxy = profileId
                    startOrReloadLocked(startIfStopped = true, beforeStatus = status)
                    action
                }
            }
        }
    }

    internal suspend fun enableService() = withContext(Dispatchers.IO) {
        RootModuleDataUpdate.applyInstallerSelection()
        tryWithChangesLock {
            val status = callFreshStatus()
            if (status.state == BaseService.State.Suspended) call("resume")
            else if (!status.state.canStop) startOrReloadLocked(startIfStopped = true, beforeStatus = status)
        }
    }

    private suspend fun startOrReloadLocked(startIfStopped: Boolean, beforeStatus: Status? = null, confirmedWarning: OutboundReferenceWarning? = null, restoringGroupDraft: Boolean = false, routeSnapshot: io.nekohasekai.sagernet.route.RootRouteBuildSnapshot? = null): Status {
        val routes = routeSnapshot ?: io.nekohasekai.sagernet.route.RootRouteBuildSnapshot.capture()
        val warning = prepareOutboundWarningLocked(startIfStopped, routes)
        if (warning != null && (warning.issues != confirmedWarning?.issues ||
                warning.startIfStopped != confirmedWarning?.startIfStopped)) throw OutboundReferenceWarningRequired(warning)
        if (warning != null && !pendingOutboundWarning.compareAndSet(warning, null))
            throw IOException("outbound_confirmation_expired")
        if (!restoringGroupDraft && io.nekohasekai.sagernet.group.editor.GroupLocalPreparation.pending()) throw IOException("group_editor_recovery_required")
        val before = beforeStatus ?: callFreshStatus()
        // @author 雾晚: rule edits use module truth, never a stale UI/Binder state.
        if (RootModuleDataUpdate.pending()) throw IOException("data_update_pending")
        if (before.phase == "disabled") throw IOException("module_disabled_or_missing")
        val profile = routes.allProfiles().firstOrNull { it.id == DataStore.selectedProxy }
            ?: if (!startIfStopped && !before.state.canStop) return before else throw IOException("profile_missing")
        // @author 雾晚: refresh cross-user UIDs (TTL-cached) so per-app rules
        // cover secondary users / cloned apps.
        runCatching { io.nekohasekai.sagernet.utils.PackageCache.refreshMultiUserUids() }
        suspend fun applyBackend(backend: RootInboundBackend, expectedRevision: String): Status {
            val instance = ProxyInstance(profile, rootInboundBackend = backend, rootRouteSnapshot = routes)
            val file = File.createTempFile("module-snapshot-", ".json", SagerNet.application.cacheDir)
            try {
                instance.init()
                file.writeText(RootModuleSnapshot(instance, expectedRevision).build().toString())
                if (file.length() > 192L * 1024 * 1024) throw IOException("snapshot_too_large")
                val applied = call("config apply", file)
                // Apply owns active-core restart/rollback; passive edits never start a stopped module.
                if (startIfStopped) {
                    call("autostart ${if (DataStore.persistAcrossReboot) "on" else "off"}")
                    if (!applied.state.canStop && applied.state != BaseService.State.Suspended) call("start")
                }
                return applied
            } finally { file.delete(); instance.close() }
        }
        return try {
            applyBackend(RootInboundBackend.EBPF, before.revision)
        } catch (error: IOException) {
            // If this kernel/core cannot activate eBPF, retry the same validated profile
            // with the preserved root-TUN path. Other module/permission errors stay visible.
            if (error.message !in setOf("core_config_invalid", "new_core_failed_rolled_back", "core_start_failed", "startup_timeout")) throw error
            Logs.w("Root eBPF inbound failed; retrying with the TUN fallback (${safeError(error)})")
            val current = callFreshStatus()
            applyBackend(RootInboundBackend.TUN, current.revision)
        }
    }

    internal var editorSnapshotProcess: (String) -> Process = { revision ->
        // revision is strictly validated below; never interpolate user names or paths.
        ProcessBuilder("su", "-c", "test \"\$(cat /data/adb/wanbox/current)\" = '$revision' && " +
            "cat /data/adb/wanbox/generations/$revision/snapshot.json && " +
            "test \"\$(cat /data/adb/wanbox/current)\" = '$revision'").redirectErrorStream(true).start()
    }

    private suspend fun readEditorBaseline(revision: String): JsonObject {
        if (!revision.matches(Regex("[a-f0-9]{64}"))) throw IOException("baseline_revision_invalid")
        val process = editorSnapshotProcess(revision)
        return coroutineScope {
            val output = async(Dispatchers.IO) { process.inputStream.use { it.readBytesLimited(192 * 1024 * 1024) } }
            try {
                val bytes = withCallTimeout("status") {
                    val exit = runInterruptible(Dispatchers.IO) { process.waitFor() }
                    val bytes = output.await()
                    if (exit != 0) throw IOException("baseline_unavailable")
                    bytes
                }
                io.nekohasekai.sagernet.group.editor.EditorBaselineReader.read(revision,
                    { bytes }, { callFreshStatus().revision })
            } finally {
                process.destroy()
                runCatching { process.inputStream.close() }
                output.cancel()
            }
        }
    }

    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (output.size() > limit - count) throw IOException("snapshot_too_large")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    /** Explicit recovery of an interrupted editor transaction; no core start. */
    suspend fun recoverGroupDraft(): Status = withContext(Dispatchers.IO) {
        changes.withLock {
            val store = io.nekohasekai.sagernet.group.editor.GroupLocalPreparation
            if (!store.pending()) return@withLock callFreshStatus()
            try {
                var confirmed: Status? = null
                val host = object : io.nekohasekai.sagernet.group.editor.GroupRecoveryHost {
                    override fun pending() = store.pending()
                    override fun baseline() = store.undoRecord().baseline
                    override fun restoreLocal() = store.restorePendingLocal()
                    override fun clearJournal() = store.clearRecord()
                    override suspend fun status(): io.nekohasekai.sagernet.group.editor.CandidateStatus {
                        val status = callFreshStatus()
                        confirmed = status
                        return io.nekohasekai.sagernet.group.editor.CandidateStatus(status.revision, status.phase, status.runningRevision)
                    }
                    override suspend fun command(command: String, candidate: io.nekohasekai.sagernet.group.editor.CandidateSnapshot) =
                        sendGroupCandidate(command, candidate)
                }
                io.nekohasekai.sagernet.group.editor.GroupDraftRecovery.recover(host)
                confirmed ?: throw IOException("rollback_not_confirmed")
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) { throw IOException("group_editor_recovery_required", error) }
        }
    }

    /**
     * P1-7 escape hatch when [recoverGroupDraft] cannot restore (e.g. the saved baseline no longer
     * validates): restore the journaled local state, rebuild if running, and confirm fresh status
     * before clearing the recovery marker. Never starts a stopped or suspended module.
     */
    suspend fun abandonGroupDraft(): Status = withContext(Dispatchers.IO) {
        changes.withLock {
            val store = io.nekohasekai.sagernet.group.editor.GroupLocalPreparation
            var rebuilt: Status? = null
            io.nekohasekai.sagernet.group.editor.GroupDraftAbandon.abandon(object : io.nekohasekai.sagernet.group.editor.GroupAbandonHost {
                override fun pending() = store.pending()
                override fun restoreLocal() = store.restorePendingLocal()
                override fun clearJournal() = store.clearRecord()
                override suspend fun rebuild() {
                    val before = callFreshStatus()
                    if (before.installData != null || before.phase !in setOf("connected", "stopped", "suspended"))
                        throw IOException("module_not_ready")
                    if (before.phase != "connected") {
                        rebuilt = before
                        return
                    }
                    // Discard reapplies the very configuration the user was running before the edit,
                    // so an unresolved outbound reference in that same state is not a new decision:
                    // it must not turn the only escape hatch into a loop. The warning computed from
                    // this one snapshot stands in for its confirmation, and the same snapshot is what
                    // gets applied, so the skipped warning cannot describe a different configuration.
                    // Normal start/reload keeps its own barrier and raises a fresh warning again.
                    val routes = io.nekohasekai.sagernet.route.RootRouteBuildSnapshot.capture()
                    val currentWarning = prepareOutboundWarningLocked(startIfStopped = false, routes)
                    // Only this locked recovery path may rebuild while retaining its journal.
                    val applied = startOrReloadLocked(startIfStopped = false, beforeStatus = before,
                        confirmedWarning = currentWarning, restoringGroupDraft = true, routeSnapshot = routes)
                    val confirmed = callFreshStatus()
                    if (applied.revision.isBlank() || confirmed.installData != null ||
                        confirmed.phase !in setOf("connected", "suspended") ||
                        confirmed.revision != applied.revision || confirmed.runningRevision != applied.revision)
                        throw IOException("rollback_not_confirmed")
                    rebuilt = confirmed
                }
            })
            rebuilt ?: callFreshStatus()
        }
    }

    private suspend fun sendGroupCandidate(command: String,
        candidate: io.nekohasekai.sagernet.group.editor.CandidateSnapshot): io.nekohasekai.sagernet.group.editor.CandidateStatus {
        val file = File.createTempFile("group-candidate-", ".json", SagerNet.application.cacheDir)
        try {
            file.writeText(candidate.json, Charsets.UTF_8)
            val result = call(command, file)
            return io.nekohasekai.sagernet.group.editor.CandidateStatus(result.revision, result.phase, result.runningRevision)
        } finally { file.delete() }
    }

    /** Editor-only branch: no confirmation token, no implicit start, no fallback rebuild after validation. */
    suspend fun applyGroupDraft(rawDraft: io.nekohasekai.sagernet.group.editor.GroupDraft, creating: Boolean = false): Status =
        withContext(Dispatchers.IO) {
            changes.withLock {
                if (io.nekohasekai.sagernet.group.editor.GroupLocalPreparation.pending()) throw IOException("group_editor_recovery_required")
                val draft = rawDraft.normalized()
                if (creating && SagerDatabase.groupDao.getById(draft.id) != null) throw IOException("input_version_conflict")
                val issues = io.nekohasekai.sagernet.group.editor.GroupEditorService.validate(draft)
                if (issues.isNotEmpty()) throw IOException("group_validation_failed")
                if (RootModuleDataUpdate.pending()) throw IOException("data_update_pending")
                var confirmedStatus: Status? = null
                var capturedProfile: io.nekohasekai.sagernet.database.ProxyEntity? = null
                val local = io.nekohasekai.sagernet.group.editor.GroupLocalPreparation(draft)
                val host = object : io.nekohasekai.sagernet.group.editor.CandidateHost {
                    override suspend fun prepare(candidate: io.nekohasekai.sagernet.group.editor.CandidateSnapshot) = local.prepare(candidate.baselineJson)
                    override suspend fun restore() = local.restore()
                    override suspend fun finish() = local.finish()
                    override suspend fun inputVersion() = io.nekohasekai.sagernet.group.editor.GroupEditorService.inputVersion()
                    override suspend fun freshStatus(): io.nekohasekai.sagernet.group.editor.CandidateStatus {
                        val status = callFreshStatus()
                        confirmedStatus = status
                        return io.nekohasekai.sagernet.group.editor.CandidateStatus(status.revision, status.phase, status.runningRevision)
                    }
                    override suspend fun capture(expectedRevision: String, inputVersion: String): io.nekohasekai.sagernet.group.editor.CandidateSnapshot {
                        val routes = io.nekohasekai.sagernet.route.RootRouteBuildSnapshot.capture()
                        // Editor errors never enter the global confirmation path or drop unresolved rules.
                        if (routes.issues().isNotEmpty()) throw IOException("missing_outbound_reference")
                        // No export command exists: read the module-owned complete
                        // snapshot. Never rebuild from possibly unapplied App settings.
                        if (expectedRevision.isEmpty()) throw IOException("baseline_unavailable")
                        val baseline = readEditorBaseline(expectedRevision)
                        val profile = routes.allProfiles().firstOrNull { it.id == baseline["profileId"]?.asLong }
                            ?: throw IOException("profile_missing")
                        capturedProfile = profile
                        val baselineConfig = baseline["config"].asJsonObject
                        val baselineTags = (baselineConfig.getAsJsonArray("outbounds").toList() +
                            baselineConfig.getAsJsonArray("endpoints")?.toList().orEmpty())
                            .mapNotNull { it.asJsonObject["tag"]?.asString }
                        val runtimeTag = if (draft.id == profile.groupId) "proxy" else draft.name
                        if (draft.name in baselineTags && (creating || draft.name != runtimeTag)) throw IOException("duplicate_tag")
                        val candidate = io.nekohasekai.sagernet.group.editor.GroupEditor.patchSnapshot(
                            baseline, draft, runtimeTag,
                            DataStore.connectionTestURL, profile.id.takeIf { draft.id == profile.groupId })
                            .apply { addProperty("expectedRevision", expectedRevision) }
                        fun canonical(value: com.google.gson.JsonElement): com.google.gson.JsonElement = when {
                            value.isJsonObject -> JsonObject().apply {
                                value.asJsonObject.entrySet().sortedBy { it.key }.forEach { (key, child) -> add(key, canonical(child)) }
                            }
                            value.isJsonArray -> com.google.gson.JsonArray().apply { value.asJsonArray.forEach { add(canonical(it)) } }
                            else -> value.deepCopy()
                        }
                        val json = canonical(candidate).toString()
                        if (json.toByteArray(Charsets.UTF_8).size > 192L * 1024 * 1024) throw IOException("snapshot_too_large")
                        return io.nekohasekai.sagernet.group.editor.CandidateSnapshot(json, inputVersion, expectedRevision, baseline.toString())
                    }
                    override suspend fun command(command: String, candidate: io.nekohasekai.sagernet.group.editor.CandidateSnapshot): io.nekohasekai.sagernet.group.editor.CandidateStatus {
                        return sendGroupCandidate(command, candidate)
                    }
                }
                try { io.nekohasekai.sagernet.group.editor.GroupCandidateCommit.apply(host) }
                catch (error: IOException) {
                    if (error.message == "cleanup_required") throw IOException("group_editor_recovery_required", error)
                    throw error
                }
                // N3: only after the module confirmed the candidate, the UI selection follows the
                // explicit fallback member so it never shows A while traffic uses another node.
                capturedProfile?.let { profile ->
                    io.nekohasekai.sagernet.group.editor.GroupEditor.selectionAfterApply(draft, profile.id, profile.groupId)
                        ?.let { DataStore.selectedProxy = it }
                }
                confirmedStatus ?: throw IOException("apply_not_confirmed")
            }
        }

    suspend fun stop() = changes.withLock { call("stop") }
    suspend fun resume() = changes.withLock { call("resume") }
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
        // @author 雾晚 (B 路线): 快照必须带 geoip/geosite 规则库，模块侧缺失即
        // config_resource_missing。启动时若解压失败，这里再补一次（幂等）。
        // yacd 已移除：配置不再下发 external_ui，模块侧也不再要求 files/yacd。
        CoreAssets.ensure(app)
        app.externalAssets.walkTopDown().filter { it.isFile && it.extension.lowercase() in setOf("db", "dat", "srs", "pem") }.forEach {
            val relative = it.relativeTo(app.externalAssets).invariantSeparatorsPath
            val name = if (relative.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._/-]{0,180}"))) relative
                else "resource-" + java.security.MessageDigest.getInstance("SHA-256").digest(relative.toByteArray())
                    .take(8).joinToString("") { byte -> "%02x".format(byte) } + "." + it.extension
            add("assets/$name", it)
        }
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
        // phase2: node health-check policy for the module (files/health_check.json).
        // Default notify-only; "auto" lets the module switch to the fastest node of the same group.
        runCatching {
            val policy = JsonObject().apply {
                addProperty("mode", DataStore.healthCheckMode.takeIf { it in setOf("off", "notify", "auto") } ?: "notify")
                addProperty("url", DataStore.connectionTestURL)
            }
            addBytes("files/health_check.json", policy.toString().toByteArray())
        }
        // B6（N3）：统一按网络规则表（单规则表，F1 与 N3 合并；旧 smart_auto_stop.json
        // 不再写入——同发布集的模块读新表；旧快照的旧文件由 Root 回退迁移）。
        runCatching {
            val policy = io.nekohasekai.sagernet.bg.NetworkRules.currentPolicyForSnapshot()
            addBytes("files/network_rules.json", io.nekohasekai.sagernet.bg.NetworkRules.serialize(policy).toByteArray())
        }
        // B6（N4）：共享接管配置（默认关闭；Root 在 supervise 前预检，失败拒绝启动）。
        runCatching {
            val shared = io.nekohasekai.sagernet.bg.SharedInterception.parse(DataStore.sharedInterceptionConfig)
                .getOrDefault(io.nekohasekai.sagernet.bg.SharedInterception.defaults())
                .copy(enabled = DataStore.sharedInterceptionEnabled, defaultInterface = DataStore.sharedInterceptionDefaultInterface)
            addBytes("files/shared_interception.json", io.nekohasekai.sagernet.bg.SharedInterception.serialize(shared).toByteArray())
        }
        val encoded = JsonObject(); files.forEach { (name, bytes) -> encoded.addProperty(name, Base64.getEncoder().encodeToString(bytes)) }
        return JsonObject().apply {
            addProperty("schemaVersion", 1); addProperty("expectedRevision", revision); add("config", config); add("files", encoded); add("plugins", plugins)
            addProperty("autoStart", DataStore.persistAcrossReboot); addProperty("performancePriority", DataStore.performancePriorityMode); addProperty("profileId", instance.profile.id); addProperty("profileName", instance.displayProfileName)
        }
    }
}

// B5（N2）：诊断包 README 里的版本信息。appVersionCode / previewVersionCode
// 取原始值（BuildConfig.VERSION_CODE_RAW / PRE_VERSION_CODE_RAW），
// 不是经 apkVersionCode 变换后的 BuildConfig.VERSION_CODE。
data class DiagnosticsVersions(
    val appVersion: String,
    val appVersionCode: Int,
    val previewVersion: String,
    val previewVersionCode: Int,
    val singBoxVersion: String,
)

// B5（N1）：Root 侧速度测试结果。字段名与 libcore.SpeedTestResult 的 Go
// 字段名一致（无 JSON tag），由 wanboxctl --speed-test 的 stdout 解析。
data class RootSpeedTestResult(
    val profileKey: String,
    val mode: String,
    val stage: String,
    val downloadBitsPerSecond: Long,
    val uploadBitsPerSecond: Long,
    val downloadBytes: Long,
    val uploadBytes: Long,
    val latencyMs: Long,
    val serverName: String,
    val serverCountry: String,
    val error: String,
    val cancelled: Boolean,
    val done: Boolean,
) {
    companion object {
        fun parse(json: JSONObject): RootSpeedTestResult = RootSpeedTestResult(
            profileKey = json.optString("ProfileKey"),
            mode = json.optString("Mode"),
            stage = json.optString("Stage"),
            downloadBitsPerSecond = json.optLong("DownloadBitsPerSecond"),
            uploadBitsPerSecond = json.optLong("UploadBitsPerSecond"),
            downloadBytes = json.optLong("DownloadBytes"),
            uploadBytes = json.optLong("UploadBytes"),
            latencyMs = json.optLong("LatencyMs"),
            serverName = json.optString("ServerName"),
            serverCountry = json.optString("ServerCountry"),
            error = json.optString("Error"),
            cancelled = json.optBoolean("Cancelled"),
            done = json.optBoolean("Done"),
        )
    }
}
