// @author 雾晚
package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.bg.RootModuleClient
import io.nekohasekai.sagernet.database.DataStore
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import org.json.JSONObject
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A candidate may only be probed when it is in the active Root-module generation's tag map. */
internal class ProfileNotInRunningConfigException : IOException("profile_not_in_running_config")

/** Reads the actual running snapshot; it never guesses tags from mutable database display names. */
internal class RunningProfileTagResolver(
    private val tagsReader: suspend () -> RootModuleClient.Status = { RootModuleClient.call("node tags") },
) {
    suspend fun tagFor(candidateProfileId: Long): String {
        require(candidateProfileId > 0)
        val status = tagsReader()
        if (!status.connected || status.runningRevision.isBlank()) throw IOException("core_not_connected")
        if (status.profileTags.isEmpty()) throw IOException("profile_tags_unavailable")
        return status.profileTags[candidateProfileId]?.takeIf { it.isNotBlank() }
            ?: throw ProfileNotInRunningConfigException()
    }
}

/** Authenticated local Controller client. It never uses the app's mixed proxy or follows redirects. */
internal class ThinControllerDelayClient(
    private val controllerBaseUrl: String = "http://127.0.0.1:9090/",
    private val secretProvider: () -> String = { DataStore.clashApiSecret },
) {
    private val baseClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(3, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun measure(tag: String, targetUrl: String, timeoutMs: Int): Int {
        val request = buildRequest(tag, targetUrl, timeoutMs)
        val timeout = timeoutMs.toLong() + 2_000L
        val client = baseClient.newBuilder()
            .readTimeout(timeout, TimeUnit.MILLISECONDS)
            .callTimeout(timeout + 1_000L, TimeUnit.MILLISECONDS)
            .build()
        val response = client.newCall(request).awaitCancellable()
        response.use {
            if (!it.isSuccessful) throw IOException("controller_http_${it.code}")
            val body = it.body ?: throw IOException("controller_empty_response")
            val json = JSONObject(readBounded(body))
            val delayMs = json.optInt("delay", -1)
            if (delayMs !in 1..60_000) throw IOException("controller_invalid_delay")
            return delayMs
        }
    }

    internal fun buildRequest(tag: String, targetUrl: String, timeoutMs: Int): Request {
        require(tag.isNotBlank() && tag.length <= 256)
        require(timeoutMs in 1_000..60_000)
        require(targetUrl.length in 1..2_048)
        val target = targetUrl.toHttpUrlOrNull() ?: throw IllegalArgumentException("invalid_probe_url")
        require(target.scheme == "http" || target.scheme == "https")
        require(target.username.isEmpty() && target.password.isEmpty())

        val base = controllerBaseUrl.toHttpUrlOrNull() ?: throw IllegalArgumentException("invalid_controller_url")
        require(base.scheme == "http" && base.host == "127.0.0.1")
        require(base.username.isEmpty() && base.password.isEmpty())
        val secret = secretProvider()
        if (!secret.matches(Regex("[0-9a-f]{64}"))) throw IOException("controller_auth_unavailable")
        val endpoint = base.newBuilder()
            .addPathSegment("proxies")
            .addPathSegment(tag)
            .addPathSegment("delay")
            .addQueryParameter("url", targetUrl)
            .addQueryParameter("timeout", timeoutMs.toString())
            .build()
        return Request.Builder()
            .url(endpoint)
            .get()
            .header("Authorization", "Bearer $secret")
            .header("Accept", "application/json")
            .build()
    }

    private fun readBounded(body: ResponseBody): String {
        val source = body.source()
        val buffer = Buffer()
        val maximum = 64 * 1024L
        var total = 0L
        while (total <= maximum) {
            val count = source.read(buffer, minOf(8_192L, maximum + 1L - total))
            if (count < 0L) break
            total += count
        }
        if (total > maximum) throw IOException("controller_response_too_large")
        return buffer.readUtf8()
    }

    private suspend fun Call.awaitCancellable(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                if (!continuation.isActive) {
                    response.close()
                    return
                }
                continuation.resume(response) { response.close() }
            }
        })
    }
}
