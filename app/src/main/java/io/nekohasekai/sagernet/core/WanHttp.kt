package io.nekohasekai.sagernet.core

import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.TlsVersion
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * @author 雾晚 (B 路线 · 核心只随模块分发)
 *
 * 纯 Kotlin 的 HTTP 客户端，方法名与历史上的 `libcore.HTTPClient` / `libcore.HTTPResponse`
 * 保持一致（`newRequest().setURL(..).execute()`、`contentString`、`getHeader(..)`、`writeTo(..)`），
 * 因此调用点只需改变类型来源，不必重写业务逻辑。
 *
 * 为什么要替换：旧实现由 Go 核心提供（可在核心内走代理出站 + uTLS 指纹）。瘦管理器没有
 * 内嵌核心，继续调用会在类初始化阶段 `UnsatisfiedLinkError`。订阅拉取、规则库更新、更新检查
 * 这些功能必须活下来，所以改由 OkHttp 直连完成。
 *
 * 已知取舍（写入发布报告）：
 * - `tryProxyOutbound()` 退化为空操作：`DataStore.serviceState.connected` 时不再经核心代理出站，
 *   而是直连。root 模式下核心跑在模块进程里，管理器自身的流量本来也不经过它。
 * - `restrictedTLS()` 用 OkHttp 的 TLS1.3-only ConnectionSpec 实现，语义与原实现一致。
 * - `allowInsecure()` 保留：为证书异常的订阅站提供与历史相同的"忽略证书"开关。
 */
class WanHttpResponse(
    /** 与 `libcore.HTTPResponse.contentString` 同名同类型（ByteArray），调用点无需改写。 */
    val contentString: ByteArray,
    private val headerMap: Map<String, String>,
    val code: Int,
) {

    /** 大小写不敏感取头，返回 ByteArray 以对齐旧的绑定签名。 */
    fun getHeader(name: String): ByteArray? {
        headerMap.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value?.toByteArray()?.let { return it }
        return null
    }

    /** 落盘（规则库 / 订阅文件下载用）。 */
    fun writeTo(path: String) {
        val target = File(path)
        target.parentFile?.mkdirs()
        // @author 雾晚: write-then-rename so an interrupted write never leaves a half file.
        val part = File(target.parentFile, target.name + ".part")
        part.writeBytes(contentString)
        if (!part.renameTo(target)) {
            target.delete()
            if (!part.renameTo(target)) {
                part.delete()
                throw java.io.IOException("cannot replace ${target.name}")
            }
        }
    }
}

class WanHttpRequest internal constructor(private val owner: WanHttpClient) {

    private var url: String = ""
    private var method: String = "GET"
    private var body: ByteArray? = null
    private var userAgent: String? = null
    private val headers = LinkedHashMap<String, String>()
    private var insecure: Boolean = false
    private var callTimeout: Pair<Long, TimeUnit>? = null

    fun setURL(url: String) = apply { this.url = url }

    fun setMethod(method: String) = apply { this.method = method.uppercase() }

    fun setContent(content: ByteArray) = apply { this.body = content }

    fun setUserAgent(ua: String) = apply { this.userAgent = ua }

    fun setHeader(name: String, value: String) = apply { headers[name] = value }

    fun allowInsecure() = apply { insecure = true }

    fun setCallTimeout(timeout: Long, unit: TimeUnit) = apply {
        require(timeout > 0)
        callTimeout = timeout to unit
    }

    private fun buildCall(): Call {
        require(url.isNotBlank()) { "empty url" }
        val builder = Request.Builder().url(url)
        userAgent?.let { builder.header("User-Agent", it) }
        headers.forEach { (k, v) -> builder.header(k, v) }
        when (method) {
            "GET" -> builder.get()
            "HEAD" -> builder.head()
            else -> builder.method(method, body?.toRequestBody(null))
        }
        val client = owner.buildClient(insecure, callTimeout)
        return client.newCall(builder.build())
    }

    fun execute(): WanHttpResponse = buildCall().execute().use(::readResponse)

    suspend fun executeCancellable(): WanHttpResponse = suspendCancellableCoroutine { continuation ->
        val call = buildCall()
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                continuation.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use(::readResponse)
                } catch (e: Throwable) {
                    continuation.resumeWith(Result.failure(e))
                    return
                }
                continuation.resumeWith(Result.success(result))
            }
        })
    }

    private fun readResponse(response: Response): WanHttpResponse {
        // @author 雾晚: an error page (404/403/5xx HTML) must never be treated as a
        // subscription or written over geoip.db / geosite.db.
        if (!response.isSuccessful) {
            throw java.io.IOException("HTTP ${response.code} for $url")
        }
        val respBody = response.body
        val declared = respBody?.contentLength() ?: -1L
        if (declared > WanHttpClient.MAX_BODY_BYTES) {
            throw java.io.IOException("response too large ($declared bytes) for $url")
        }
        val bytes = respBody?.source()?.let { source ->
            val buffer = okio.Buffer()
            while (source.read(buffer, 64 * 1024L) != -1L) {
                if (buffer.size > WanHttpClient.MAX_BODY_BYTES) {
                    throw java.io.IOException("response too large for $url")
                }
            }
            buffer.readByteArray()
        } ?: ByteArray(0)
        val map = LinkedHashMap<String, String>()
        for (i in 0 until response.headers.size) {
            map[response.headers.name(i)] = response.headers.value(i)
        }
        return WanHttpResponse(bytes, map, response.code)
    }
}

class WanHttpClient {

    private var tls13Only = false

    /** 现代 TLS（默认配置即可，保留以对齐旧调用点）。 */
    fun modernTLS() = apply { tls13Only = false }

    /** 仅 TLS 1.3（对齐旧 `restrictedTLS()`）。 */
    fun restrictedTLS() = apply { tls13Only = true }

    /** 连接复用由 OkHttp 连接池天然提供。 */
    fun keepAlive() = apply { }

    /**
     * 旧实现让请求经核心代理出站。管理器只跑 root 模式，自身流量由模块 TUN 接管，这里直连即可；
     * 保留方法是为了让调用点保持原样。
     */
    fun tryProxyOutbound() = apply { }

    fun newRequest(): WanHttpRequest = WanHttpRequest(this)

    /** 对齐旧 `HTTPClient.close()`。OkHttp 的连接池是进程级共享的，这里不做实际释放。 */
    fun close() = Unit

    /**
     * @author 雾晚: every variant is derived with `newBuilder()` from one immutable base.
     * The old code mutated a shared Builder, so after one "allow insecure" request the
     * normal client silently trusted every certificate and skipped hostname checks.
     */
    internal fun buildClient(insecure: Boolean, callTimeout: Pair<Long, TimeUnit>? = null): OkHttpClient {
        val versions = if (tls13Only) arrayOf(TlsVersion.TLS_1_3) else arrayOf(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
        if (!insecure && !tls13Only && callTimeout == null) return shared
        val builder = shared.newBuilder()
        if (insecure || tls13Only) {
            val spec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS).tlsVersions(*versions).build()
            builder.connectionSpecs(listOf(spec, ConnectionSpec.CLEARTEXT))
        }
        callTimeout?.let { (timeout, unit) -> builder.callTimeout(timeout, unit) }
        if (insecure) {
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val context = SSLContext.getInstance("TLS")
            context.init(null, arrayOf<javax.net.ssl.TrustManager>(trustAll), java.security.SecureRandom())
            builder.sslSocketFactory(context.socketFactory, trustAll).hostnameVerifier { _, _ -> true }
        }
        return builder.build()
    }

    companion object {
        /** Subscriptions and rule databases are far below this; anything larger is not ours. */
        const val MAX_BODY_BYTES = 64L * 1024 * 1024

        private val shared: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                // Blocking execute() ignores coroutine withTimeout; bound the whole call here.
                .callTimeout(90, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }
    }
}
