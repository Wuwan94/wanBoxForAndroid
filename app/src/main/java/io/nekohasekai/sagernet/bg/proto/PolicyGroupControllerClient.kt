package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.database.DataStore
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import okio.Buffer
import org.json.JSONObject
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

internal enum class PolicyGroupType { SELECTOR, URLTEST, LOADBALANCE }

internal data class PolicyGroup(
    val tag: String,
    val type: PolicyGroupType,
    val now: String?,
    val members: List<String>,
)

internal data class PolicyGroupSnapshot(val groups: List<PolicyGroup>, val skippedEntries: Int)

/** Reads only the running controller snapshot; database names never define membership. */
internal class PolicyGroupControllerClient(
    private val controllerBaseUrl: String = "http://127.0.0.1:9090/",
    private val secretProvider: () -> String = { DataStore.clashApiSecret },
) {
    private val client = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun snapshot(): PolicyGroupSnapshot {
        val json = request(null)
        val proxies = json.optJSONObject("proxies") ?: throw IOException("controller_invalid_snapshot")
        var skipped = 0
        val groups = mutableListOf<PolicyGroup>()
        for (tag in proxies.keys()) {
            val group = proxies.optJSONObject(tag)?.let { parseGroup(tag, it) }
            if (group == null) skipped++ else groups.add(group)
        }
        return PolicyGroupSnapshot(groups, skipped)
    }

    suspend fun select(group: PolicyGroup, member: String): PolicyGroup {
        require(group.type == PolicyGroupType.SELECTOR) { "automatic_group" }
        require(member in group.members) { "unknown_member" }
        request(group.tag, member)
        val actual = parseGroup(group.tag, request(group.tag))
            ?: throw IOException("controller_invalid_group")
        if (actual.type != PolicyGroupType.SELECTOR || actual.now != member) {
            throw IOException("controller_selection_mismatch")
        }
        return actual
    }

    private fun parseGroup(tag: String, json: JSONObject): PolicyGroup? {
        val type = PolicyGroupType.entries.firstOrNull { it.name.equals(json.opt("type") as? String, true) }
            ?: return null
        val all = json.optJSONArray("all") ?: return null
        val members = mutableListOf<String>()
        for (index in 0 until all.length()) {
            val member = all.opt(index) as? String ?: return null
            if (member.isBlank()) return null
            members.add(member)
        }
        val now = json.opt("now")
        if (now != null && now != JSONObject.NULL && now !is String) return null
        return PolicyGroup(tag, type, now as? String, members)
    }

    private suspend fun request(tag: String?, member: String? = null): JSONObject {
        val base = controllerBaseUrl.toHttpUrlOrNull() ?: throw IllegalArgumentException("invalid_controller_url")
        require(base.scheme == "http" && base.host == "127.0.0.1")
        require(base.username.isEmpty() && base.password.isEmpty())
        val secret = secretProvider()
        if (!secret.matches(Regex("[0-9a-f]{64}"))) throw IOException("controller_auth_unavailable")
        val endpoint = base.newBuilder().addPathSegment("proxies").apply {
            if (tag != null) addPathSegment(tag)
        }.build()
        val request = Request.Builder().url(endpoint)
            .header("Authorization", "Bearer $secret").header("Accept", "application/json")
            .apply {
                if (member == null) get()
                else put(JSONObject().put("name", member).toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            }.build()
        return client.newCall(request).awaitCancellable().use { response ->
            if (!response.isSuccessful) throw IOException("controller_http_${response.code}")
            val body = response.body ?: throw IOException("controller_empty_response")
            val text = readBounded(body)
            if (member != null) return@use JSONObject()
            try { JSONObject(text) }
            catch (error: org.json.JSONException) { throw IOException("controller_invalid_json", error) }
        }
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
