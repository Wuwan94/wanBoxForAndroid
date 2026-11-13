// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.bg

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Coordinates of the module supervisor's loopback read channel. The
 * supervisor publishes them in a root-only state file; the App learns them
 * through the su bridge (`wanboxctl channel`) and then reads the status and
 * dashboard views over plain loopback HTTP instead of spawning `su` per
 * sample. The channel is read-only by contract: mutations always stay on
 * the su bridge. @author 雾晚
 */
internal data class RootModuleChannelInfo(val port: Int, val token: String)

private val channelTokenShape = Regex("[0-9a-fA-F]{32}")
private val channelPortShape = Regex("[0-9]{1,5}")
private const val CHANNEL_INFO_MAX_DEPTH = 8

/**
 * Tolerant locator for the channel coordinates in a `wanboxctl channel`
 * response: the payload may sit at the top level (the channel.json shape)
 * or nested inside the schema-1 envelope's state, so the parsed JSON is
 * searched recursively for a numeric `port` in 1..65535 and a `token` of
 * exactly 32 hex characters. Anything else — non-JSON output, an envelope
 * error from a module without channel support, partial fields — yields
 * null, which callers treat as "channel unavailable". Kept as one small
 * standalone function so the exact wire shape can be adjusted in a single
 * place once the module side is final.
 */
internal fun parseChannelInfo(text: String): RootModuleChannelInfo? {
    val root = try {
        JsonParser.parseString(text)
    } catch (_: Exception) {
        return null
    }
    var port: Int? = null
    var token: String? = null
    fun visit(element: JsonElement, depth: Int) {
        if (depth > CHANNEL_INFO_MAX_DEPTH) return
        when {
            element.isJsonObject -> {
                val obj = element.asJsonObject
                if (port == null) {
                    val primitive = obj.get("port")
                        ?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
                    val raw = primitive?.takeIf { it.isNumber }?.asString
                    val candidate = raw?.takeIf { it.matches(channelPortShape) }?.toIntOrNull()
                    if (candidate != null && candidate in 1..65535) port = candidate
                }
                if (token == null) {
                    val primitive = obj.get("token")
                        ?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
                    val candidate = primitive?.takeIf { it.isString }?.asString
                    if (candidate != null && candidate.matches(channelTokenShape)) token = candidate
                }
                if (port == null || token == null) {
                    for ((_, child) in obj.entrySet()) visit(child, depth + 1)
                }
            }
            element.isJsonArray -> {
                if (port == null || token == null) {
                    for (child in element.asJsonArray) visit(child, depth + 1)
                }
            }
        }
    }
    visit(root, 0)
    val foundPort = port
    val foundToken = token
    return if (foundPort != null && foundToken != null) {
        RootModuleChannelInfo(foundPort, foundToken)
    } else {
        null
    }
}

/**
 * Process-wide channel bookkeeping: the discovered coordinates plus the
 * failure cooldown. Any channel failure (discovery included) drops the
 * coordinates and starts the cooldown so a dead or absent channel costs at
 * most one rediscovery attempt per window instead of an extra su spawn on
 * every read; the reads themselves fall back to the su bridge meanwhile.
 * The injected clock keeps the state machine deterministic in unit tests.
 */
internal class RootModuleChannelGate(
    private val cooldownMs: Long,
    private val nowMs: () -> Long,
) {
    private var info: RootModuleChannelInfo? = null
    private var cooldownUntilMs: Long = Long.MIN_VALUE

    init { require(cooldownMs > 0) }

    @Synchronized
    fun info(): RootModuleChannelInfo? = info

    /** True when a channel attempt (discovery or use) is currently permitted. */
    @Synchronized
    fun attemptAllowed(): Boolean = nowMs() >= cooldownUntilMs

    @Synchronized
    fun store(value: RootModuleChannelInfo) {
        info = value
    }

    /** A failure drops the coordinates and (re)starts the cooldown. */
    @Synchronized
    fun fail() {
        info = null
        cooldownUntilMs = nowMs() + cooldownMs
    }

    /**
     * Forget the coordinates without blaming the channel: an explicit cache
     * invalidation means the supervisor may have been replaced, so the next
     * read rediscovers it immediately instead of trusting a stale port.
     */
    @Synchronized
    fun dropInfo() {
        info = null
    }
}

/** URL of one channel read; a pure function so tests can pin the wire shape. */
internal fun channelUrl(
    info: RootModuleChannelInfo,
    query: RootModuleReadQuery,
    waitSeconds: Int,
    sinceRevision: String?,
): String {
    val path = when (query) {
        RootModuleReadQuery.STATUS -> "/v1/status"
        RootModuleReadQuery.DASHBOARD -> "/v1/dashboard"
    }
    return buildString {
        append("http://127.0.0.1:")
        append(info.port)
        append(path)
        if (waitSeconds > 0) {
            append("?wait=")
            append(waitSeconds)
            append("&since=")
            append(URLEncoder.encode(sinceRevision.orEmpty(), "UTF-8"))
        }
    }
}

/**
 * Blocking loopback HTTP for the channel, on HttpURLConnection so no new
 * dependency enters the thin manager. Responses are byte-identical to the
 * su bridge's status/dashboard output and are validated by the caller
 * through the same parseResponse path; this layer only moves bytes and
 * reports transport failures as IOException.
 */
internal object RootModuleChannelTransport {
    private const val CONNECT_TIMEOUT_MS = 2_000
    private const val READ_TIMEOUT_MS = 2_000
    private const val LONG_POLL_READ_SLACK_MS = 5_000
    private const val MAX_BODY_BYTES = 1_048_576

    /**
     * GET one view. With [waitSeconds] > 0 the server holds the request
     * until the view's revision leaves [sinceRevision] or the wait elapses.
     * Cancellation disconnects the socket so a cancelled long poll does not
     * linger until its read deadline.
     */
    suspend fun get(
        info: RootModuleChannelInfo,
        query: RootModuleReadQuery,
        waitSeconds: Int = 0,
        sinceRevision: String? = null,
    ): String = coroutineScope {
        val connection = open(info, query, waitSeconds, sinceRevision)
        val deferred = async(Dispatchers.IO) { readBody(connection) }
        try {
            deferred.await()
        } catch (e: CancellationException) {
            connection.disconnect()
            deferred.cancel()
            throw e
        } finally {
            connection.disconnect()
        }
    }

    private fun open(
        info: RootModuleChannelInfo,
        query: RootModuleReadQuery,
        waitSeconds: Int,
        sinceRevision: String?,
    ): HttpURLConnection {
        val connection = URL(channelUrl(info, query, waitSeconds, sinceRevision))
            .openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = if (waitSeconds > 0) {
            waitSeconds * 1_000 + LONG_POLL_READ_SLACK_MS
        } else {
            READ_TIMEOUT_MS
        }
        connection.requestMethod = "GET"
        connection.setRequestProperty("X-Wanbox-Token", info.token)
        connection.useCaches = false
        return connection
    }

    private fun readBody(connection: HttpURLConnection): String {
        val code = connection.responseCode
        if (code != HttpURLConnection.HTTP_OK) throw IOException("channel_http_rejected")
        return connection.inputStream.use { stream ->
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val count = stream.read(chunk)
                if (count < 0) break
                if (buffer.size() + count > MAX_BODY_BYTES) throw IOException("module_response_too_large")
                buffer.write(chunk, 0, count)
            }
            buffer.toByteArray().toString(Charsets.UTF_8)
        }
    }
}
