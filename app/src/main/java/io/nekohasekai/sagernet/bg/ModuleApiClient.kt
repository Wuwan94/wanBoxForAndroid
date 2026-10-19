// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.bg

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.Proxy
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * 模块本地 HTTPS 管理客户端。
 *
 * 传输层从 `su -c wanboxctl ...` 换成 loopback HTTPS：
 * - 凭据（端口 / token / 证书指纹）只在首次连接时经 [CredentialBridge] 取一次；
 * - 之后 App 不再执行任何 Root 命令，也不再申请 Root；
 * - 核心停止时接口仍可访问，因此状态与节点读取不依赖代理是否在跑。
 *
 * 本阶段新增：/v1/nodes、/v1/subscriptions、/v1/config、/v1/service/reload、
 * /v1/data 系列端点的完整封装；启停/切节点/配置应用/状态查询全部经本客户端。
 *
 * @author 雾晚
 */
object ModuleApiClient {

    /** 首次凭据交付。零 Root：只读模块写入 App 私有目录的凭据文件，不经过 su。 */
    interface CredentialBridge {
        suspend fun load(): Credentials
    }

    data class Credentials(val baseUrl: String, val token: String, val certSha256: String) {
        /** 避免整个对象被日志/异常打印时泄露 token。 */
        override fun toString(): String = "Credentials(baseUrl=$baseUrl, token=<redacted>, certSha256=$certSha256)"
    }

    /** 节点：模块 store 的真实节点形态，App 只做展示与编辑。 */
    data class ModuleNode(
        val id: Long,
        val type: String,
        val name: String,
        val group: String?,
        val subscriptionId: Long,
        val outbound: JSONObject,
        val updatedAt: Long,
        /** 来自配置顶层 endpoints 数组：模块写回 endpoints，不进入 selector 候选。 */
        val endpoint: Boolean = false,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("type", type)
            .put("name", name)
            .put("subscriptionId", subscriptionId)
            .put("outbound", outbound)
            .also { group?.let { g -> it.put("group", g) } }
            .also { if (endpoint) it.put("endpoint", true) }

        companion object {
            fun fromJson(o: JSONObject) = ModuleNode(
                o.optLong("id"),
                o.optString("type"),
                o.optString("name"),
                o.optString("group").takeIf { it.isNotEmpty() },
                o.optLong("subscriptionId"),
                o.optJSONObject("outbound") ?: JSONObject(),
                o.optLong("updatedAt"),
                o.optBoolean("endpoint"),
            )
        }
    }

    data class ModuleSubscription(
        val id: Long,
        val name: String,
        val url: String,
        val filter: JSONObject?,
        val lastUpdated: Long,
        val nodeCount: Int,
        val error: String,
    ) {
        companion object {
            fun fromJson(o: JSONObject) = ModuleSubscription(
                o.optLong("id"),
                o.optString("name"),
                o.optString("url"),
                o.optJSONObject("filter"),
                o.optLong("lastUpdated"),
                o.optInt("nodeCount"),
                o.optString("error"),
            )
        }
    }

    private const val STATUS_TTL_MS = 2_000L

    /**
     * 客户端缓存的 key 必须带凭据代次（baseUrl + certSha256）：
     * 模块重启后端口会变、证书会轮换，只按超时档位缓存会复用旧连接池，
     * 表现为「凭据已更新但仍连不上」。
     */
    private data class ClientKey(
        val baseUrl: String,
        val certSha256: String,
        val timeout: ApiTimeout,
    )

    @Volatile private var credentials: Credentials? = null
    /** 凭据读写与清空共用一把锁；[clients] 有自己的锁，两者不嵌套顺序反转。 */
    private val credentialsLock = Any()
    private val clients = HashMap<ClientKey, OkHttpClient>()
    @Volatile private var cachedStatus: RootModuleClient.Status? = null
    @Volatile private var cachedAt = 0L

    var bridge: CredentialBridge = ManagedFilesCredentialBridge()

    fun invalidateStatusCache() {
        cachedStatus = null
    }

    /** 丢弃凭据；证书或鉴权失败时调用，下次请求重新交付。 */
    fun invalidateCredentials() {
        synchronized(credentialsLock) { credentials = null }
        synchronized(clients) { clients.clear() }
        cachedStatus = null
    }

    // ---- 状态 / 生命周期（沿用 preview.5） ----

    suspend fun status(): RootModuleClient.Status {
        val cached = cachedStatus
        if (cached != null && SystemClock.elapsedRealtime() - cachedAt < STATUS_TTL_MS) return cached
        return requestStatus("GET", "/v1/status").also {
            cachedStatus = it
            cachedAt = SystemClock.elapsedRealtime()
        }
    }

    suspend fun dashboard(): RootModuleClient.Status = requestStatus("GET", "/v1/status").also {
        cachedStatus = it
        cachedAt = SystemClock.elapsedRealtime()
    }

    suspend fun start(): RootModuleClient.Status = requestStatus("POST", "/v1/service/start")
    suspend fun stop(): RootModuleClient.Status = requestStatus("POST", "/v1/service/stop")
    suspend fun reload(): RootModuleClient.Status = requestStatus("POST", "/v1/service/reload")

    /** 自启开关：轻量，只写 boot 标记，不重启核心。 */
    suspend fun setAutoStart(enabled: Boolean): RootModuleClient.Status =
        requestStatus("POST", "/v1/service/autostart", JSONObject().put("enabled", enabled).toString())

    /** 只切换运行态节点，不重启服务。失败由调用方决定是否回退到全量配置应用。 */
    suspend fun selectNode(profileId: Long): RootModuleClient.Status {
        require(profileId > 0)
        val body = JSONObject().put("profileId", profileId).toString()
        return requestStatus("POST", "/v1/select", body)
    }

    // ---- 节点 CRUD ----

    suspend fun nodes(): List<ModuleNode> {
        val arr = requestJson("GET", "/v1/nodes").optJSONArray("nodes") ?: JSONArray()
        return (0 until arr.length()).map { ModuleNode.fromJson(arr.getJSONObject(it)) }
    }

    suspend fun addNode(node: ModuleNode): ModuleNode =
        ModuleNode.fromJson(requestJson("POST", "/v1/nodes", node.toJson().toString()).getJSONObject("node"))

    /** 整表替换：只在模块 store 为空（首次迁移）或明确覆盖时使用。 */
    suspend fun replaceNodes(nodes: List<ModuleNode>): List<ModuleNode> {
        val array = JSONArray()
        nodes.forEach { array.put(it.toJson()) }
        val body = JSONObject().put("nodes", array).toString()
        val result = requestJson("PUT", "/v1/nodes", body).optJSONArray("nodes") ?: JSONArray()
        return (0 until result.length()).map { ModuleNode.fromJson(result.getJSONObject(it)) }
    }

    /** 模块事件环；核心停止时照样可读，替代 App 侧读 logcat。 */
    suspend fun logs(lines: Int = 200): List<String> {
        val array = requestJson("GET", "/v1/logs?lines=$lines").optJSONArray("logs") ?: JSONArray()
        return (0 until array.length()).map { array.getJSONObject(it).toString() }
    }

    suspend fun updateNode(id: Long, node: ModuleNode): ModuleNode =
        ModuleNode.fromJson(requestJson("PUT", "/v1/nodes/$id", node.toJson().toString()).getJSONObject("node"))

    suspend fun deleteNode(id: Long): RootModuleClient.Status =
        requestStatus("DELETE", "/v1/nodes/$id")

    // ---- 订阅 ----

    suspend fun subscriptions(): List<ModuleSubscription> {
        val arr = requestJson("GET", "/v1/subscriptions").optJSONArray("subscriptions") ?: JSONArray()
        return (0 until arr.length()).map { ModuleSubscription.fromJson(arr.getJSONObject(it)) }
    }

    suspend fun addSubscription(name: String, url: String): ModuleSubscription =
        ModuleSubscription.fromJson(
            requestJson("POST", "/v1/subscriptions", JSONObject().put("name", name).put("url", url).toString())
                .getJSONObject("subscription")
        )

    suspend fun updateSubscription(id: Long): RootModuleClient.Status =
        requestStatus("POST", "/v1/subscriptions/$id/update")

    suspend fun deleteSubscription(id: Long): RootModuleClient.Status =
        requestStatus("DELETE", "/v1/subscriptions/$id")

    // ---- 配置 ----

    suspend fun config(): JSONObject =
        requestJson("GET", "/v1/config").optJSONObject("config") ?: JSONObject()

    /** 应用配置意图：App 只下发增量，节点真值在模块 store。 */
    suspend fun applyConfig(intent: JSONObject): RootModuleClient.Status =
        requestStatus("PUT", "/v1/config", intent.toString())

    // ---- 数据迁移 ----

    suspend fun dataPrepare(): RootModuleClient.Status = requestStatus("POST", "/v1/data/prepare")
    suspend fun dataFinish(): RootModuleClient.Status = requestStatus("POST", "/v1/data/finish")
    suspend fun dataRollback(): RootModuleClient.Status = requestStatus("POST", "/v1/data/rollback")

    /** 安装器数据选择确认（原 CLI data selection-finish <id>）。 */
    suspend fun selectionFinish(id: String): RootModuleClient.Status =
        requestStatus("POST", "/v1/data/selection-finish", JSONObject().put("id", id).toString())

    // ---- SSE 状态流（沿用 preview.5） ----

    /**
     * 状态事件流。连接与读取放在 [callbackFlow] 内部独立的 IO 协程里执行：
     * STREAM 档读超时为 0（无限等待），只有取消 socket 才能唤醒阻塞读，
     * 所以页面退出时必须能在 awaitClose 里 cancel call + 取消协程。
     */
    fun events(): Flow<RootModuleClient.Status> = callbackFlow {
        val callRef = java.util.concurrent.atomic.AtomicReference<Call?>(null)
        val job = launch(Dispatchers.IO) {
            try {
                val creds = ensureCredentials()
                val request = Request.Builder()
                    .url(creds.baseUrl + "/v1/events")
                    .header("Authorization", "Bearer ${creds.token}")
                    .header("Accept", "text/event-stream")
                    .build()
                val call = client(creds, ApiTimeout.STREAM).newCall(request)
                callRef.set(call)
                val response = call.execute()
                if (!response.isSuccessful) throw IOException("module_api_http_${response.code}")
                response.use {
                    val source = it.body?.source() ?: throw IOException("module_api_empty_stream")
                    while (!source.exhausted()) {
                        val line = source.readUtf8LineStrict()
                        if (!line.startsWith("data: ")) continue
                        // 原样交给解析器：不补 ok=true，也不改写版本字段，
                        // 协议不符时按真实错误关闭事件流。
                        trySend(RootModuleClient.parseResponse(line.removePrefix("data: ")))
                    }
                }
                // 服务端正常结束：按真实原因关闭，不伪造成功。
                close(IOException("module_api_stream_closed"))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                close(e)
            }
        }
        awaitClose {
            // 先断 socket（读超时 0 时只有 cancel 能解除阻塞读），再取消协程。
            callRef.get()?.cancel()
            job.cancel()
        }
    }

    // ---- 传输层 ----

    /** 状态类接口：返回解析后的 Status，沿用 RootModuleClient.parseResponse 保证行为一致。 */
    private suspend fun requestStatus(method: String, path: String, body: String? = null): RootModuleClient.Status =
        withContext(Dispatchers.IO) {
            val text = raw(method, path, body)
            RootModuleClient.parseResponse(text)
        }

    /** 数据类接口：返回原始 JSONObject，由各方法自行取字段。 */
    private suspend fun requestJson(method: String, path: String, body: String? = null): JSONObject =
        withContext(Dispatchers.IO) { JSONObject(raw(method, path, body)) }

    private suspend fun raw(method: String, path: String, body: String?): String =
        withContext(Dispatchers.IO) {
            val timeout = timeoutFor(method, path)
            try {
                execute(method, path, body, timeout)
            } catch (e: ModuleApiException) {
                throw e
            } catch (e: IOException) {
                // 只重放安全读取：写请求一旦响应丢失，重放会重复创建/重复应用配置。
                if (method != "GET") throw e
                // 模块重启后端口会变、证书可能轮换：丢掉缓存重读一次凭据再试。
                invalidateCredentials()
                execute(method, path, body, timeout)
            }
        }

    private suspend fun execute(method: String, path: String, body: String?, timeout: ApiTimeout): String {
        val creds = ensureCredentials()
        val builder = Request.Builder()
            .url(creds.baseUrl + path)
            .header("Authorization", "Bearer ${creds.token}")
        when (method) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            else -> builder.method(method, (body ?: "{}").toRequestBody("application/json".toMediaType()))
        }
        val response = client(creds, timeout).newCall(builder.build()).execute()
        response.use {
            if (it.code == 401) {
                invalidateCredentials()
                throw ModuleApiException("module_api_unauthorized")
            }
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                val code = runCatching { JSONObject(text).optString("error") }.getOrNull()
                throw ModuleApiException(
                    code?.takeIf { c -> c.matches(Regex("[a-z_]{1,80}")) } ?: "module_api_http_${it.code}"
                )
            }
            return text
        }
    }

    private suspend fun ensureCredentials(): Credentials {
        synchronized(credentialsLock) { credentials }?.let { return it }
        val loaded = bridge.load()
        // 并发首次交付只让一个结果生效，避免前后两次读到不同端口/证书。
        val existing = synchronized(credentialsLock) {
            val current = credentials
            if (current != null) current else { credentials = loaded; null }
        }
        if (existing != null) return existing
        // 新代次凭据：旧代次的连接池一律丢弃，杜绝复用过期客户端。
        synchronized(clients) { clients.clear() }
        return loaded
    }

    private fun client(creds: Credentials, timeout: ApiTimeout = ApiTimeout.SHORT): OkHttpClient {
        val key = ClientKey(creds.baseUrl, creds.certSha256, timeout)
        synchronized(clients) {
            clients[key]?.let { return it }
            return buildPinnedClient(creds, timeout.readSeconds).also { clients[key] = it }
        }
    }

    /** 测试缝：与生产走同一条缓存路径，用来验证缓存键带凭据代次。 */
    internal fun clientForTest(creds: Credentials, timeout: ApiTimeout): OkHttpClient = client(creds, timeout)

    /** 测试缝：缓存条目数（不含任何凭据内容）。 */
    internal fun cachedClientCount(): Int = synchronized(clients) { clients.size }

    /**
     * 构建与生产一致的钉扎客户端。仅供本对象的连接池复用与本地 HTTPS 测试直接调用；
     * 测试用它验证「同一张证书通过、换一张证书失败」，避免用源码字符串断言代替真实 TLS。
     */
    internal fun buildPinnedClient(creds: Credentials, readSeconds: Long): OkHttpClient {
        val trust = PinnedTrustManager(creds.certSha256)
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        return OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trust)
            .hostnameVerifier { hostname, _ -> hostname == "127.0.0.1" || hostname == "localhost" }
            // 模块下发的 certSha256 是证书 DER 的 SHA-256 小写十六进制，由 PinnedTrustManager 严格比对。
            // OkHttp 的 CertificatePinner 需要 Base64 SPKI，两种格式不能混用；叠加会导致必然的
            // Certificate pinning failure，因此这里只用前者。
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(readSeconds, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }

    /** 管理面请求的三档超时；生命周期动作会等核心启动/停止，远超普通读。 */
    enum class ApiTimeout(val readSeconds: Long) {
        SHORT(15),
        LONG(300),
        STREAM(0),
    }

    private fun timeoutFor(method: String, path: String): ApiTimeout = when {
        path == "/v1/service/start" || path == "/v1/service/stop" -> ApiTimeout.LONG
        path == "/v1/service/reload" || path == "/v1/config" -> ApiTimeout.LONG
        path.startsWith("/v1/data/") -> ApiTimeout.LONG
        path.endsWith("/update") -> ApiTimeout.LONG
        else -> ApiTimeout.SHORT
    }

    private fun hostOf(baseUrl: String): String =
        baseUrl.removePrefix("https://").substringBefore(":").ifEmpty { "127.0.0.1" }

    private class PinnedTrustManager(private val expected: String) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            throw CertificateException("client_certificate_not_allowed")
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            val leaf = chain.firstOrNull() ?: throw CertificateException("certificate_missing")
            val actual = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
                .joinToString("") { "%02x".format(it) }
            if (!MessageDigest.isEqual(actual.toByteArray(), expected.toByteArray())) {
                throw CertificateException("certificate_pinned_mismatch")
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /**
     * 零 Root 凭据交付：模块在 serve 启动、端口变化和 App 重装后把 api.json 写进下面这些
     * 位置，App 只做普通文件读取，全程不执行 su，也不申请超级用户。
     * 顺序：no_backup → files → 外置私有目录；前两者由模块改属主，后者由系统授权。
     */
    class ManagedFilesCredentialBridge : CredentialBridge {
        override suspend fun load(): Credentials = withContext(Dispatchers.IO) {
            for (file in candidates()) {
                if (!file.isFile) continue
                val text = runCatching { file.readText() }.getOrNull() ?: continue
                val parsed = runCatching { parseCredentialText(text) }.getOrNull() ?: continue
                return@withContext parsed
            }
            throw IOException("module_credentials_unavailable")
        }

        private fun candidates(): List<File> {
            val context = io.nekohasekai.sagernet.SagerNet.application
            return buildList {
                add(File(context.noBackupFilesDir, CREDENTIAL_FILE))
                add(File(context.filesDir, CREDENTIAL_FILE))
                context.getExternalFilesDir(null)?.let { add(File(it, CREDENTIAL_FILE)) }
            }
        }

        private companion object {
            const val CREDENTIAL_FILE = "module-api.json"
        }
    }

    /** 模块返回的业务错误（HTTP 错误码或 `error` 字段），不触发重试。 */
    class ModuleApiException(val code: String) : IOException(code)

    internal fun parseCredentialText(text: String): Credentials {
        val json = JSONObject(text)
        val listen = json.optString("listen").trim()
        val token = json.optString("token").trim()
        val cert = json.optString("certSha256").trim()
        val match = listenPattern.matchEntire(listen)
            ?: throw IOException("module_credentials_invalid")
        val port = match.groupValues[2].toIntOrNull()
        if (port == null || port !in 1..65535) throw IOException("module_credentials_invalid")
        if (!hex64Pattern.matches(token)) throw IOException("module_credentials_invalid")
        if (!hex64Pattern.matches(cert)) throw IOException("module_credentials_invalid")
        return Credentials("https://$listen", token, cert)
    }

    /** 只接受数值 loopback 地址与端口，拒绝主机名、路径和 0 端口。 */
    private val listenPattern = Regex("""^(127\.0\.0\.1|\[::1]|::1):(\d{1,5})$""")
    private val hex64Pattern = Regex("^[a-f0-9]{64}$")
}

/** 供 UI 判断管理面是否可用；不触发 Root 申请。 */
internal fun ModuleApiClient.Credentials.prettyHost(): String = baseUrl.removePrefix("https://")
