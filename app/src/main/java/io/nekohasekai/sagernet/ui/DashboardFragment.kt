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
    private var serviceStartTime: Long = 0L

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
            // 等待后台状态回调后清除标记，超时保护
            view?.postDelayed({
                serviceOpInProgress = false
            }, 5000)
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
        when (mode) {
            MODE_RULE -> {
                DataStore.bypass = true
                DataStore.proxyApps = false
            }
            MODE_GLOBAL -> {
                DataStore.bypass = false
                DataStore.proxyApps = false
            }
            MODE_DIRECT -> {
                // 直连模式：不断开 Root TUN，仅切换路由为直连
                DataStore.bypass = true
                DataStore.proxyApps = false
            }
        }
        DataStore.dashboardMode = mode
        SagerNet.reloadService()
        updateModeUI()
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
        val stats = activity.lastSpeedStats ?: return
        lifecycleScope.launch {
            val ctx = context ?: return@launch
            uploadSpeed?.text = Formatter.formatFileSize(ctx, stats.txRateProxy) + "/s"
            downloadSpeed?.text = Formatter.formatFileSize(ctx, stats.rxRateProxy) + "/s"
            uploadTotal?.text = "累计 " + Formatter.formatFileSize(ctx, stats.txTotal)
            downloadTotal?.text = "累计 " + Formatter.formatFileSize(ctx, stats.rxTotal)
            coreMemory?.text = "—"
            connectionCount?.text = "—"
        }
    }

    private fun updateUptime() {
        lifecycleScope.launch {
            val state = (activity as? MainActivity)?.serviceState
            if (state == BaseService.State.Connected && serviceStartTime == 0L) {
                serviceStartTime = System.currentTimeMillis()
            } else if (state != BaseService.State.Connected) {
                serviceStartTime = 0L
            }
            uptimeText?.text = if (serviceStartTime > 0) {
                formatDuration(System.currentTimeMillis() - serviceStartTime)
            } else {
                "—"
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
