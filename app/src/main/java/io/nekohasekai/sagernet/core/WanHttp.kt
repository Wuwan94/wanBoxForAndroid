package io.nekohasekai.sagernet.core

import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.TlsVersion
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
        target.writeBytes(contentString)
    }
}

class WanHttpRequest internal constructor(private val owner: WanHttpClient) {

    private var url: String = ""
    private var method: String = "GET"
    private var body: ByteArray? = null
    private var userAgent: String? = null
    private val headers = LinkedHashMap<String, String>()
    private var insecure: Boolean = false

    fun setURL(url: String) = apply { this.url = url }

    fun setMethod(method: String) = apply { this.method = method.uppercase() }

    fun setContent(content: ByteArray) = apply { this.body = content }

    fun setUserAgent(ua: String) = apply { this.userAgent = ua }

    fun setHeader(name: String, value: String) = apply { headers[name] = value }

    fun allowInsecure() = apply { insecure = true }

    fun execute(): WanHttpResponse {
        require(url.isNotBlank()) { "empty url" }
        val builder = Request.Builder().url(url)
        userAgent?.let { builder.header("User-Agent", it) }
        headers.forEach { (k, v) -> builder.header(k, v) }
        when (method) {
            "GET" -> builder.get()
            "HEAD" -> builder.head()
            else -> builder.method(method, body?.toRequestBody(null))
        }
        val client = owner.buildClient(insecure)
        client.newCall(builder.build()).execute().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            val map = LinkedHashMap<String, String>()
            for (i in 0 until response.headers.size) {
                map[response.headers.name(i)] = response.headers.value(i)
            }
            if (!response.isSuccessful && bytes.isEmpty()) {
                throw IllegalStateException("HTTP ${response.code} for $url")
            }
            return WanHttpResponse(bytes, map, response.code)
        }
    }
}

class WanHttpClient {

    private val base: OkHttpClient.Builder = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)

    private var tls13Only = false

    /** 现代 TLS（默认配置即可，保留以对齐旧调用点）。 */
    fun modernTLS() = apply { tls13Only = false }

    /** 仅 TLS 1.3（对齐旧 `restrictedTLS()`）。 */
    fun restrictedTLS() = apply { tls13Only = true }

    /** 连接复用由 OkHttp 连接池天然提供。 */
    fun keepAlive() = apply { }

    /**
     * 旧实现让请求经核心代理出站。瘦管理器没有核心，这里退化为直连；
     * 保留方法是为了让 4 处调用点保持原样。
     */
    fun tryProxyOutbound() = apply { }

    fun newRequest(): WanHttpRequest = WanHttpRequest(this)

    /** 对齐旧 `HTTPClient.close()`。OkHttp 的连接池是进程级共享的，这里不做实际释放。 */
    fun close() = Unit

    private var client: OkHttpClient? = null
    private var insecureClient: OkHttpClient? = null

    internal fun buildClient(insecure: Boolean): OkHttpClient {
        if (!insecure && !tls13Only) {
            return client ?: base.build().also { client = it }
        }
        if (insecure) {
            return insecureClient ?: buildInsecure().also { insecureClient = it }
        }
        val spec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .tlsVersions(TlsVersion.TLS_1_3)
            .build()
        return base.connectionSpecs(listOf(spec, ConnectionSpec.CLEARTEXT)).build()
    }

    private fun buildInsecure(): OkHttpClient {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<javax.net.ssl.TrustManager>(trustAll), java.security.SecureRandom())
        val spec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
            .build()
        return base
            .sslSocketFactory(context.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .connectionSpecs(listOf(spec, ConnectionSpec.CLEARTEXT))
            .build()
    }
}
