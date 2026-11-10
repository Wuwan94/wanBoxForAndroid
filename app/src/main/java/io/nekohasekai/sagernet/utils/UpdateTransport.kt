// @author 雾晚
package io.nekohasekai.sagernet.utils

import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** One immutable network policy for release metadata AND all assets (including redirects).
 * Updates require the configured mixed inbound: connection/auth failure never retries direct.
 * This deliberately does not change subscription or other WanHttp callers.
 */
internal class UpdateTransport(port: Int, username: String?, password: String?, private val job: Job) : AutoCloseable {
    init { require(port in 1..65535) }
    private val client = OkHttpClient.Builder()
        .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(true).followSslRedirects(false)
        .proxyAuthenticator { _, response ->
            if (username.isNullOrBlank() || response.request.header("Proxy-Authorization") != null) null
            else response.request.newBuilder()
                .header("Proxy-Authorization", Credentials.basic(username, password.orEmpty())).build()
        }.build()

    // Cancellation closes execute()/body reads, not merely a later UI continuation.
    @OptIn(InternalCoroutinesApi::class)
    private fun <T> request(url: String, consume: (Response) -> T): T {
        job.ensureActive()
        val request = Request.Builder().url(url).build()
        require(request.url.isHttps) { "更新只接受 HTTPS 地址" }
        val call = client.newCall(request)
        val cancellation = job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) {
            if (job.isCancelled) call.cancel()
        }
        try {
            return call.execute().use {
                if (!it.isSuccessful || it.body == null) throw IOException("更新请求失败（HTTP ${it.code}）")
                job.ensureActive()
                consume(it).also { job.ensureActive() }
            }
        } finally { cancellation.dispose() }
    }

    fun text(url: String): String = request(url) { SecureNetwork.text(it.body, 4 * 1024 * 1024) }
    fun download(url: String, target: File) = request(url) {
        it.body!!.byteStream().use { input -> ArtifactDownload.write(target, input) }
    }
    override fun close() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}
