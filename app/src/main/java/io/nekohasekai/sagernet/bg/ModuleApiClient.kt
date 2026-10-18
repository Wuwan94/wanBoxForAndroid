// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.bg

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.CertificatePinner
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
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
 * @author 雾晚
 */
object ModuleApiClient {

    /** 首次凭据交付。默认经 su 读取模块生成的 api.json，可替换为私有 Provider。 */
    interface CredentialBridge {
        suspend fun load(): Credentials
    }

    data class Credentials(val baseUrl: String, val token: String, val certSha256: String)

    private const val STATUS_TTL_MS = 2_000L

    @Volatile private var credentials: Credentials? = null
    @Volatile private var client: OkHttpClient? = null
    @Volatile private var cachedStatus: RootModuleClient.Status? = null
    @Volatile private var cachedAt = 0L

    var bridge: CredentialBridge = SuCredentialBridge()

    fun invalidateStatusCache() {
        cachedStatus = null
    }

    /** 丢弃凭据；证书或鉴权失败时调用，下次请求重新交付。 */
    fun invalidateCredentials() {
        credentials = null
        client = null
        cachedStatus = null
    }

    suspend fun status(): RootModuleClient.Status {
        val cached = cachedStatus
        if (cached != null && SystemClock.elapsedRealtime() - cachedAt < STATUS_TTL_MS) return cached
        return request("GET", "/v1/status").also {
            cachedStatus = it
            cachedAt = SystemClock.elapsedRealtime()
        }
    }

    suspend fun dashboard(): RootModuleClient.Status = request("GET", "/v1/status").also {
        cachedStatus = it
        cachedAt = SystemClock.elapsedRealtime()
    }

    suspend fun start(): RootModuleClient.Status = request("POST", "/v1/service/start")
    suspend fun stop(): RootModuleClient.Status = request("POST", "/v1/service/stop")

    /**
     * 只切换运行态节点，不重启服务。失败不抛给 UI 的通用错误，
     * 由调用方决定是否回退到全量配置应用。
     */
    suspend fun selectNode(profileId: Long): RootModuleClient.Status {
        require(profileId > 0)
        val body = JSONObject().put("profileId", profileId).toString()
        return request("POST", "/v1/select", body)
    }

    /** SSE 状态流；断线明确失败，不静默回退轮询。 */
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
    }

    /** SSE 帧里是 {"schema":1,"state":{...}}，转成 CLI 同构的响应体复用解析。 */
    private fun payloadOf(line: String): String {
        val state = JSONObject(line.removePrefix("data: ")).optJSONObject("state") ?: JSONObject()
        return JSONObject()
            .put("schemaVersion", 1)
            .put("ok", true)
            .put("state", state)
            .toString()
    }

    private suspend fun request(method: String, path: String, body: String? = null): RootModuleClient.Status =
        withContext(Dispatchers.IO) {
            val creds = ensureCredentials()
            val builder = Request.Builder()
                .url(creds.baseUrl + path)
                .header("Authorization", "Bearer ${creds.token}")
            when (method) {
                "GET" -> builder.get()
                else -> builder.post((body ?: "{}").toRequestBody("application/json".toMediaType()))
            }
            val response = client(creds).newCall(builder.build()).execute()
            response.use {
                if (it.code == 401) {
                    invalidateCredentials()
                    throw IOException("module_api_unauthorized")
                }
                val text = it.body?.string().orEmpty()
                if (!it.isSuccessful) {
                    val code = runCatching { JSONObject(text).optString("error") }.getOrNull()
                    throw IOException(code?.takeIf { c -> c.matches(Regex("[a-z_]{1,80}")) } ?: "module_api_http_${it.code}")
                }
                RootModuleClient.parseResponse(text)
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
            // 自签证书 + 指纹钉扎：主机名验证交由信任管理器完成，避免依赖系统 CA。
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

    /** 只信任指纹完全一致的本机证书；不回落系统 CA，也不接受任意证书。 */
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

    /** 过渡实现：仅首次读取凭据时用一次 su，之后全部走 HTTPS。 */
    class SuCredentialBridge : CredentialBridge {
        @android.annotation.SuppressLint("SdCardPath")
        override suspend fun load(): Credentials = withContext(Dispatchers.IO) {
            val process = try {
                ProcessBuilder("su", "-c", "cat /data/adb/wanbox/api.json").start()
            } catch (_: IOException) {
                throw IOException("module_credentials_unavailable")
            }
            val text = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            if (process.exitValue() != 0 || text.isBlank()) throw IOException("module_credentials_unavailable")
            val json = JSONObject(text)
            val listen = json.optString("listen")
            val token = json.optString("token")
            val cert = json.optString("certSha256")
            if (listen.isBlank() || token.length < 32 || cert.length != 64) {
                throw IOException("module_credentials_invalid")
            }
            Credentials("https://$listen", token, cert)
        }
    }
}

/** 供 UI 判断管理面是否可用；不触发 Root 申请。 */
internal fun ModuleApiClient.Credentials.prettyHost(): String = baseUrl.removePrefix("https://")
