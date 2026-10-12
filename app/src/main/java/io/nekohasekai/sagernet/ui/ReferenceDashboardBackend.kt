package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.utils.BoundedInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Only talks to the authenticated module API on loopback. */
internal object ReferenceDashboardBackend {
    private val modeChanges = Mutex()
    private val client = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS).callTimeout(4, TimeUnit.SECONDS).build()
    private fun request(path: String) = Request.Builder().url("http://127.0.0.1:9090/$path")
        .header("Authorization", "Bearer " + DataStore.clashApiSecret)
    private fun get(path: String): JSONObject = client.newCall(request(path).build()).execute().use { response ->
        if (!response.isSuccessful) throw IOException("核心 API 返回 ${response.code}")
        val body = response.body ?: throw IOException("核心 API 没有返回数据")
        if (path == "memory") JSONObject(body.source().readUtf8LineStrict(16384))
        else JSONObject(body.byteStream().use { String(BoundedInput.read(it, 4 * 1024 * 1024), Charsets.UTF_8) })
    }
    suspend fun mode(): Int = withContext(Dispatchers.IO) {
        when (get("configs").getString("mode").lowercase()) {
            "rule" -> 0; "global" -> 1; "direct" -> 2
            else -> throw IOException("核心返回未知模式")
        }
    }
    suspend fun setMode(mode: Int) = modeChanges.withLock { withContext(Dispatchers.IO) {
        val name = listOf("rule", "global", "direct")[mode]
        val body = JSONObject().put("mode", name).toString().toRequestBody("application/json".toMediaType())
        client.newCall(request("configs").patch(body).build()).execute().use {
            if (!it.isSuccessful) throw IOException("模式切换失败：${it.code}")
        }
        check(get("configs").getString("mode").equals(name, true)) { "核心未确认模式切换" }
    } }
    suspend fun metrics(): Pair<Long?, Int?> = withContext(Dispatchers.IO) {
        val memory = runCatching { get("memory").optLong("inuse", -1).takeIf { it >= 0 } }.getOrNull()
        val connections = runCatching { get("connections").optJSONArray("connections")?.length() }.getOrNull()
        memory to connections
    }
}
