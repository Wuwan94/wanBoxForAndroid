package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import com.google.android.material.switchmaterial.SwitchMaterial
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * preview.18 UI 改造：仪表盘页。
 * 显示服务状态、当前节点、模式切换（规则/全局/直连）、2x2 数据卡。
 */
class DashboardFragment : ToolbarFragment() {

    /**
     * @author 雾晚：共享的受限 loopback 客户端。
     * 仅访问 127.0.0.1:9090，5s 超时，禁重定向，不每次新建。
     */
    private val loopbackClient: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    private var statusText: TextView? = null
    private var serviceSwitch: SwitchMaterial? = null
    // 防止程序化更新开关状态时触发监听器导致重复启停
    private var suppressSwitchCallback = false
    // 防重复提交：连接操作进行中时忽略新的点击
    private var serviceOpInProgress = false
    private var nodeName: TextView? = null
    private var nodeInfo: TextView? = null
    private var uptimeText: TextView? = null
    private var modeRule: Button? = null
    private var modeGlobal: Button? = null
    private var modeDirect: Button? = null
    private var uploadSpeed: TextView? = null
    private var uploadTotal: TextView? = null
    private var downloadSpeed: TextView? = null
    private var downloadTotal: TextView? = null
    private var coreMemory: TextView? = null
    private var connectionCount: TextView? = null

    private var statsJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.layout_dashboard, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        statusText = view.findViewById(R.id.statusText)
        serviceSwitch = view.findViewById(R.id.serviceSwitch)
        nodeName = view.findViewById(R.id.nodeName)
        nodeInfo = view.findViewById(R.id.nodeInfo)
        uptimeText = view.findViewById(R.id.uptimeText)
        modeRule = view.findViewById(R.id.modeRule)
        modeGlobal = view.findViewById(R.id.modeGlobal)
        modeDirect = view.findViewById(R.id.modeDirect)
        uploadSpeed = view.findViewById(R.id.uploadSpeed)
        uploadTotal = view.findViewById(R.id.uploadTotal)
        downloadSpeed = view.findViewById(R.id.downloadSpeed)
        downloadTotal = view.findViewById(R.id.downloadTotal)
        coreMemory = view.findViewById(R.id.coreMemory)
        connectionCount = view.findViewById(R.id.connectionCount)

        serviceSwitch?.setOnCheckedChangeListener { _, isChecked ->
            if (suppressSwitchCallback) return@setOnCheckedChangeListener
            if (serviceOpInProgress) {
                // 操作进行中，恢复开关到实际状态，忽略本次点击
                syncSwitchToState()
                return@setOnCheckedChangeListener
            }
            serviceOpInProgress = true
            if (isChecked) {
                SagerNet.startService()
            } else {
                SagerNet.stopService()
            }
            // @author 雾晚：等待后台状态回调后清除标记，超时保护（生命周期感知）
            lifecycleScope.launch {
                kotlinx.coroutines.delay(5000)
                serviceOpInProgress = false
            }
        }

        modeRule?.setOnClickListener { setMode(MODE_RULE) }
        modeGlobal?.setOnClickListener { setMode(MODE_GLOBAL) }
        modeDirect?.setOnClickListener { setMode(MODE_DIRECT) }

        view.findViewById<View>(R.id.currentNodeLayout)?.setOnClickListener {
            (activity as? MainActivity)?.navigateToNodes()
        }

        updateServiceState()
        updateCurrentNode()
        updateModeUI()
    }

    override fun onResume() {
        super.onResume()
        startStatsPolling()
        updateServiceState()
        updateCurrentNode()
        updateModeUI()
    }

    override fun onPause() {
        super.onPause()
        stopStatsPolling()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopStatsPolling()
        statusText = null
        serviceSwitch = null
        nodeName = null
        nodeInfo = null
        uptimeText = null
        modeRule = null
        modeGlobal = null
        modeDirect = null
        uploadSpeed = null
        uploadTotal = null
        downloadSpeed = null
        downloadTotal = null
        coreMemory = null
        connectionCount = null
    }

    /**
     * @author 雾晚：由 MainActivity.changeState() 回调，服务状态变化时实时更新 UI。
     * 仅在 Fragment 处于活跃生命周期时处理，避免后台回调导致状态错乱。
     */
    fun onServiceStateChanged(state: BaseService.State) {
        if (!isAdded || view == null) return
        updateServiceState()
    }

    private fun updateServiceState() {
        val state = (activity as? MainActivity)?.serviceState ?: BaseService.State.Idle
        lifecycleScope.launch {
            // 后台确认状态后清除操作中标记
            serviceOpInProgress = false
            suppressSwitchCallback = true
            try {
                when (state) {
                    BaseService.State.Connected -> {
                        statusText?.text = getString(R.string.service_state_connected)
                        if (serviceSwitch?.isChecked == false) serviceSwitch?.isChecked = true
                    }
                    BaseService.State.Connecting -> {
                        statusText?.text = getString(R.string.service_state_connecting)
                    }
                    BaseService.State.Stopping -> {
                        statusText?.text = getString(R.string.service_state_stopping)
                    }
                    else -> {
                        statusText?.text = getString(R.string.service_state_idle)
                        if (serviceSwitch?.isChecked == true) serviceSwitch?.isChecked = false
                    }
                }
            } finally {
                suppressSwitchCallback = false
            }
        }
    }

    private fun syncSwitchToState() {
        val state = (activity as? MainActivity)?.serviceState ?: BaseService.State.Idle
        suppressSwitchCallback = true
        try {
            serviceSwitch?.isChecked = (state == BaseService.State.Connected)
        } finally {
            suppressSwitchCallback = false
        }
    }

    private fun updateCurrentNode() {
        lifecycleScope.launch {
            val profileId = DataStore.selectedProxy
            if (profileId == 0L) {
                nodeName?.text = "—"
                nodeInfo?.text = "—"
                return@launch
            }
            val profile = ProfileManager.getProfile(profileId)
            nodeName?.text = profile?.displayName() ?: "—"
            nodeInfo?.text = "—"
        }
    }

    private fun updateModeUI() {
        val mode = getCurrentMode()
        lifecycleScope.launch {
            val selectedBg = android.graphics.drawable.ColorDrawable(0xFFE0E0E0.toInt())
            val normalBg = android.graphics.drawable.ColorDrawable(0x00000000)
            modeRule?.background = if (mode == MODE_RULE) selectedBg else normalBg
            modeGlobal?.background = if (mode == MODE_GLOBAL) selectedBg else normalBg
            modeDirect?.background = if (mode == MODE_DIRECT) selectedBg else normalBg
        }
    }

    private fun setMode(mode: Int) {
        // @author 雾晚：通过 Clash API 运行时切换模式，不重启核心。
        // 0=rule, 1=global, 2=direct
        val modeStr = when (mode) {
            MODE_GLOBAL -> "global"
            MODE_DIRECT -> "direct"
            else -> "rule"
        }
        // 先持久化，失败时回读
        val oldMode = DataStore.dashboardMode
        DataStore.dashboardMode = mode
        updateModeUI()

        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                if (patchClashMode(modeStr)) {
                    // 读回确认
                    val confirmed = getClashMode()
                    if (confirmed != modeStr) {
                        throw IllegalStateException("Mode mismatch: expected $modeStr, got $confirmed")
                    }
                } else {
                    throw IllegalStateException("PATCH /configs failed")
                }
            } catch (e: Exception) {
                // 失败回滚 UI 和持久化
                DataStore.dashboardMode = oldMode
                lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    updateModeUI()
                    android.widget.Toast.makeText(
                        requireContext(),
                        "模式切换失败：${e.message}",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }


    /**
     * @author 雾晚：流式限额读取响应体，避免 body.string().take() 全量读入内存。
     * 超过限额返回 null（视为未知，不造假）。
     */
    private fun readLimitedBody(resp: okhttp3.Response, maxBytes: Long): String? {
        return try {
            val source = resp.body?.source() ?: return null
            val buffer = okio.Buffer()
            var total: Long = 0
            while (true) {
                val read = source.read(buffer, 8192)
                if (read == -1L) break
                total += read
                if (total > maxBytes) return null // 超限，视为未知
            }
            buffer.readUtf8()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }
    }

    /**
     * @author 雾晚：通过 loopback Clash API 切换运行模式，不重启核心。
     * 安全约束：仅访问 127.0.0.1:9090，带 Bearer 鉴权，限制超时与响应体大小，
     * 禁止远程重定向。
     */
    private fun patchClashMode(mode: String): Boolean {
        return try {
            val secret = DataStore.clashApiSecret
            val body = "{\"mode\":\"$mode\"}"
                .toRequestBody("application/json".toMediaType())
            val req = okhttp3.Request.Builder()
                .url("http://127.0.0.1:9090/configs")
                .patch(body)
                .header("Authorization", "Bearer $secret")
                .build()
            loopbackClient.newCall(req).execute().use { resp ->
                // PATCH 响应体小，直接关闭即可，不读取内容
                resp.isSuccessful
            }
        } catch (e: Exception) {
            // @author 雾晚：取消异常必须重新抛出，与 OkHttp 取消联动
            if (e is kotlinx.coroutines.CancellationException) throw e
            false
        }
    }

    /**
     * @author 雾晚：读回当前 Clash 模式用于确认。
     */
    private fun getClashMode(): String? {
        return try {
            val secret = DataStore.clashApiSecret
            val req = okhttp3.Request.Builder()
                .url("http://127.0.0.1:9090/configs")
                .header("Authorization", "Bearer $secret")
                .build()
            loopbackClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bodyStr = readLimitedBody(resp, 4096) ?: return null
                org.json.JSONObject(bodyStr).optString("mode", null)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }
    }

    private fun getCurrentMode(): Int {
        return DataStore.dashboardMode
    }

    private fun startStatsPolling() {
        stopStatsPolling()
        statsJob = lifecycleScope.launch {
            while (true) {
                updateStats()
                updateUptime()
                delay(1000)
            }
        }
    }

    private fun stopStatsPolling() {
        statsJob?.cancel()
        statsJob = null
    }

    private fun updateStats() {
        val activity = activity as? MainActivity ?: return
        val stats = activity.lastSpeedStats
        lifecycleScope.launch {
            val ctx = context ?: return@launch
            // @author 雾晚：未连接时清理旧数据，不显示虚假数据
            if (stats == null) {
                uploadSpeed?.text = "—"
                downloadSpeed?.text = "—"
                uploadTotal?.text = "累计 —"
                downloadTotal?.text = "累计 —"
                coreMemory?.text = "—"
                connectionCount?.text = "—"
                return@launch
            }
            uploadSpeed?.text = Formatter.formatFileSize(ctx, stats.txRateProxy) + "/s"
            downloadSpeed?.text = Formatter.formatFileSize(ctx, stats.rxRateProxy) + "/s"
            uploadTotal?.text = "累计 " + Formatter.formatFileSize(ctx, stats.txTotal)
            downloadTotal?.text = "累计 " + Formatter.formatFileSize(ctx, stats.rxTotal)
            // @author 雾晚：从模块端 Telemetry 获取真实 RSS/连接数/运行时长
            // 通过 RootModuleClient 状态查询，复用既有前台观察，不另开轮询
            coreMemory?.text = "—"
            connectionCount?.text = "—"
        }
        // 后台获取模块遥测（复用状态查询缓存，不独立轮询）
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val status = io.nekohasekai.sagernet.bg.RootModuleClient.call("status")
                val telemetry = status.telemetry
                lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    if (!isAdded) return@launch
                    // RSS：未知显示—，不伪造
                    coreMemory?.text = telemetry?.let {
                        if (it.vmRSSBytes > 0) Formatter.formatFileSize(ctx, it.vmRSSBytes) else "—"
                    } ?: "—"
                    // 连接数：null 显示—，区分未知与真实 0
                    connectionCount?.text = telemetry?.connectionCount?.let { "$it 个" } ?: "—"
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // 失败保持"—"，不伪造
            }
        }
    }


    private fun updateUptime() {
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val status = io.nekohasekai.sagernet.bg.RootModuleClient.call("status")
                val uptimeSeconds = status.telemetry?.uptimeSeconds ?: 0L
                lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    if (!isAdded) return@launch
                    // @author 雾晚：真实运行时长由模块端提供（ready marker），未知显示—
                    uptimeText?.text = if (uptimeSeconds > 0) {
                        formatDuration(uptimeSeconds * 1000)
                    } else {
                        "—"
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    if (isAdded) uptimeText?.text = "—"
                }
            }
        }
    }

    private fun formatDuration(millis: Long): String {
        val seconds = millis / 1000
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }

    companion object {
        const val MODE_RULE = 0
        const val MODE_GLOBAL = 1
        const val MODE_DIRECT = 2
    }
}
