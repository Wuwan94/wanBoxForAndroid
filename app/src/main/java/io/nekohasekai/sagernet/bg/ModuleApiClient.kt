// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.bg

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.CertificatePinner
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
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

    data class Credentials(val baseUrl: String, val token: String, val certSha256: String)

    /** 节点：模块 store 的真实节点形态，App 只做展示与编辑。 */
    data class ModuleNode(
        val id: Long,
        val type: String,
        val name: String,
        val group: String?,
        val subscriptionId: Long,
        val outbound: JSONObject,
        val updatedAt: Long,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("type", type)
            .put("name", name)
            .put("subscriptionId", subscriptionId)
            .put("outbound", outbound)
            .also { group?.let { g -> it.put("group", g) } }

        companion object {
            fun fromJson(o: JSONObject) = ModuleNode(
                o.optLong("id"),
                o.optString("type"),
                o.optString("name"),
                o.optString("group").takeIf { it.isNotEmpty() },
                o.optLong("subscriptionId"),
                o.optJSONObject("outbound") ?: JSONObject(),
                o.optLong("updatedAt"),
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

    @Volatile private var credentials: Credentials? = null
    @Volatile private var client: OkHttpClient? = null
    @Volatile private var cachedStatus: RootModuleClient.Status? = null
    @Volatile private var cachedAt = 0L

    var bridge: CredentialBridge = ManagedFilesCredentialBridge()

    fun invalidateStatusCache() {
        cachedStatus = null
    }

    /** 丢弃凭据；证书或鉴权失败时调用，下次请求重新交付。 */
    fun invalidateCredentials() {
        credentials = null
        client = null
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

    fun events(): Flow<RootModuleClient.Status> = callbackFlow {
        var call: Call? = null
        try {
            val creds = ensureCredentials()
            val request = Request.Builder()
                .url(creds.baseUrl + "/v1/events")
                .header("Authorization", "Bearer ${creds.token}")
                .header("Accept", "text/event-stream")
                .build()
            call = client(creds).newCall(request)
            val response = call.execute()
            if (!response.isSuccessful) throw IOException("module_api_http_${response.code}")
            response.use {
                val source = it.body?.source() ?: throw IOException("module_api_empty_stream")
                while (!source.exhausted()) {
                    val line = source.readUtf8LineStrict()
                    if (!line.startsWith("data: ")) continue
                    val status = runCatching { RootModuleClient.parseResponse(payloadOf(line)) }.getOrNull()
                    if (status != null) trySend(status)
                }
            }
            close(IOException("module_api_stream_closed"))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            close(e)
        }
        awaitClose { call?.cancel() }
    }.flowOn(Dispatchers.IO)

    private fun payloadOf(line: String): String {
        val state = JSONObject(line.removePrefix("data: ")).optJSONObject("state") ?: JSONObject()
        return JSONObject()
            .put("schemaVersion", 1)
            .put("ok", true)
            .put("state", state)
            .toString()
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
            try {
                execute(method, path, body)
            } catch (e: ModuleApiException) {
                throw e
            } catch (e: IOException) {
                // 模块重启后端口会变，App 重装后凭据文件会被替换：丢掉缓存重读一次再试。
                invalidateCredentials()
                execute(method, path, body)
            }
        }

    private suspend fun execute(method: String, path: String, body: String?): String {
        val creds = ensureCredentials()
        val builder = Request.Builder()
            .url(creds.baseUrl + path)
            .header("Authorization", "Bearer ${creds.token}")
        when (method) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            else -> builder.method(method, (body ?: "{}").toRequestBody("application/json".toMediaType()))
        }
        val response = client(creds).newCall(builder.build()).execute()
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
        credentials?.let { return it }
        val loaded = bridge.load()
        credentials = loaded
        return loaded
    }

    private fun client(creds: Credentials): OkHttpClient {
        client?.let { return it }
        val trust = PinnedTrustManager(creds.certSha256)
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        return OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trust)
            .hostnameVerifier { hostname, _ -> hostname == "127.0.0.1" || hostname == "localhost" }
            .certificatePinner(
                CertificatePinner.Builder()
                    .add(hostOf(creds.baseUrl), "sha256/${creds.certSha256}")
                    .build()
            )
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
            .also { client = it }
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

    private fun parseCredentialText(text: String): Credentials {
        val json = JSONObject(text)
        val listen = json.optString("listen")
        val token = json.optString("token")
        val cert = json.optString("certSha256")
        if (listen.isBlank() || token.length < 32 || cert.length != 64) {
            throw IOException("module_credentials_invalid")
        }
        return Credentials("https://$listen", token, cert)
    }
}

/** 供 UI 判断管理面是否可用；不触发 Root 申请。 */
internal fun ModuleApiClient.Credentials.prettyHost(): String = baseUrl.removePrefix("https://")
