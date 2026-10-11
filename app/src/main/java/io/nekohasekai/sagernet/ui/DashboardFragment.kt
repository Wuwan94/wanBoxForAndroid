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
import androidx.lifecycle.repeatOnLifecycle
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
import kotlinx.coroutines.withContext

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
    // @author 雾晚：使用应用级 ServiceCommandController 串行控制
    // 替代局部门闩 + 5s 延时，UI 通过 StateFlow 只读观察
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
            // @author 雾晚：程序化更新时 tag 标记，回调直接返回不发送命令
            if (serviceSwitch?.tag == "programmatic") return@setOnCheckedChangeListener
            if (io.nekohasekai.sagernet.SagerNet.commandController.isBusy()) {
                // 操作进行中，恢复开关到实际状态，忽略本次点击
                syncSwitchToState()
                return@setOnCheckedChangeListener
            }
            // @author 雾晚：通过应用级串行控制器提交，后台确认后更新
            val kind = if (isChecked) {
                io.nekohasekai.sagernet.bg.ServiceCommandController.CommandKind.START
            } else {
                io.nekohasekai.sagernet.bg.ServiceCommandController.CommandKind.STOP
            }
            // @author 雾晚：通过控制器提交，不捕获 Fragment/views
            val submitted = if (isChecked) {
                io.nekohasekai.sagernet.SagerNet.commandController.requestStart()
            } else {
                io.nekohasekai.sagernet.SagerNet.commandController.requestStop()
            }
            if (!submitted) {
                // 被拒绝（有命令执行中），恢复开关
                syncSwitchToState()
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
        // @author 雾晚：使用 repeatOnLifecycle(STARTED) 订阅，界面销毁自动取消
        // 无旧值回写，无重复查询，异常/断开清空
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                while (true) {
                    updateDashboardOnce()
                    kotlinx.coroutines.delay(1000)
                }
            }
        }
        // @author 雾晚：观察串行命令状态，UI 只读渲染
        // Pending 禁用开关并显示连接中/断开中；Confirmed 用携带的 status 同步真实状态；
        // Error 提示安全错误码并回读真实状态
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                io.nekohasekai.sagernet.SagerNet.commandController.state.collect { state ->
                    when (state) {
                        is io.nekohasekai.sagernet.bg.ServiceCommandController.CommandState.Pending -> {
                            // 执行中：禁用开关，显示过渡状态
                            serviceSwitch?.isEnabled = false
                            val msg = when (state.kind) {
                                io.nekohasekai.sagernet.bg.ServiceCommandController.CommandKind.START ->
                                    getString(R.string.service_state_connecting)
                                io.nekohasekai.sagernet.bg.ServiceCommandController.CommandKind.STOP ->
                                    getString(R.string.service_state_stopping)
                                else -> statusText?.text.toString()
                            }
                            statusText?.text = msg
                        }
                        is io.nekohasekai.sagernet.bg.ServiceCommandController.CommandState.Confirmed -> {
                            // @author 雾晚：用确认结果中的真实 status 同步，不用旧 MainActivity.serviceState
                            serviceSwitch?.isEnabled = true
                            updateServiceStateFromStatus(state.status)
                            io.nekohasekai.sagernet.SagerNet.commandController.acknowledge()
                        }
                        is io.nekohasekai.sagernet.bg.ServiceCommandController.CommandState.Error -> {
                            serviceSwitch?.isEnabled = true
                            // 安全错误码提示（不暴露原始异常）
                            val safeMsg = when {
                                state.message.contains("timeout") -> "操作超时"
                                state.message.contains("node") -> "节点切换失败"
                                else -> "操作失败"
                            }
                            android.widget.Toast.makeText(context, safeMsg, android.widget.Toast.LENGTH_SHORT).show()
                            // 回读真实状态
                            updateServiceState()
                            syncSwitchToState()
                            io.nekohasekai.sagernet.SagerNet.commandController.acknowledge()
                        }
                        is io.nekohasekai.sagernet.bg.ServiceCommandController.CommandState.Cancelled -> {
                            serviceSwitch?.isEnabled = true
                            updateServiceState()
                            syncSwitchToState()
                            io.nekohasekai.sagernet.SagerNet.commandController.acknowledge()
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
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

    /**
     * @author 雾晚：从确认的 Status 同步真实状态，不用旧 MainActivity.serviceState 渲染。
     */
    private fun updateServiceStateFromStatus(status: io.nekohasekai.sagernet.bg.RootModuleClient.Status) {
        val ctx = context ?: return
        serviceSwitch?.tag = "programmatic"
        try {
            when {
                status.connected -> {
                    statusText?.text = getString(R.string.service_state_connected)
                    serviceSwitch?.isChecked = true
                }
                status.phase == "starting" -> {
                    statusText?.text = getString(R.string.service_state_connecting)
                    serviceSwitch?.isChecked = true
                }
                else -> {
                    statusText?.text = getString(R.string.service_state_idle)
                    serviceSwitch?.isChecked = false
                }
            }
        } finally {
            serviceSwitch?.tag = null
        }
        updateCurrentNode()
    }

    private fun updateServiceState() {
        val state = (activity as? MainActivity)?.serviceState ?: BaseService.State.Idle
        lifecycleScope.launch {
            // 后台确认状态后清除操作中标记
            serviceSwitch?.tag = "programmatic"
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
                serviceSwitch?.tag = null
            }
        }
    }

    private fun syncSwitchToState() {
        val state = (activity as? MainActivity)?.serviceState ?: BaseService.State.Idle
        serviceSwitch?.tag = "programmatic"
        try {
            serviceSwitch?.isChecked = (state == BaseService.State.Connected)
        } finally {
            serviceSwitch?.tag = null
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



    /**
     * @author 雾晚：单次更新所有仪表盘数据。
     * 合并 stats 与 uptime，避免每秒两轮独立 status 查询。
     * 复用 RootModuleClient 的 TTL 缓存，不另开轮询。
     */
    /**
     * @author 雾晚：单次仪表盘更新，由 repeatOnLifecycle(STARTED) 循环调用。
     * 界面销毁自动取消，无旧值回写。异常/断开清空显示。
     */
    private suspend fun updateDashboardOnce() {
        val act = activity as? MainActivity ?: return
        val stats = act.lastSpeedStats
        val ctx = context ?: return

        // 未连接时清理旧数据
        if (stats == null) {
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (!isAdded) return@withContext
                uploadSpeed?.text = "—"
                downloadSpeed?.text = "—"
                uploadTotal?.text = "累计 —"
                downloadTotal?.text = "累计 —"
                coreMemory?.text = "—"
                connectionCount?.text = "—"
                uptimeText?.text = "—"
            }
            return
        }

        // @author 雾晚：遥测经 AIDL 从 :bg 进程传递，在 SpeedDisplayData 中
        // UI 进程直接使用，不跨进程调用 RootModuleClient
        withContext(kotlinx.coroutines.Dispatchers.Main) {
            if (!isAdded) return@withContext
            uploadSpeed?.text = Formatter.formatFileSize(ctx, stats.txRateProxy) + "/s"
            downloadSpeed?.text = Formatter.formatFileSize(ctx, stats.rxRateProxy) + "/s"
            // @author 雾晚：使用模块采集的真实累计（-1=未知），未知显示—
            uploadTotal?.text = stats.txTotalModuleOrNull()?.let { "累计 " + Formatter.formatFileSize(ctx, it) }
                ?: "累计 —"
            downloadTotal?.text = stats.rxTotalModuleOrNull()?.let { "累计 " + Formatter.formatFileSize(ctx, it) }
                ?: "累计 —"
            // @author 雾晚：RSS 未知显示—，不伪造
            coreMemory?.text = if (stats.vmRSSBytes >= 0) {
                Formatter.formatFileSize(ctx, stats.vmRSSBytes)
            } else "—"
            // @author 雾晚：连接数 null 显示—，区分未知与真实 0
            connectionCount?.text = stats.connectionCountOrNull()?.let { "$it 个" } ?: "—"
            // @author 雾晚：运行时长由模块端提供（ready marker），未知显示—
            uptimeText?.text = if (stats.uptimeSeconds >= 0) {
                formatDuration(stats.uptimeSeconds * 1000)
            } else {
                "—"
            }
            // @author 雾晚：仅显示已验证的 runningProfileName，
            // 不从未确认的 selectedProxy 冒充
            if (stats.runningProfileName.isNotBlank()) {
                nodeName?.text = stats.runningProfileName
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
