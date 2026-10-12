package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.ServiceCommandController.CommandKind
import io.nekohasekai.sagernet.bg.ServiceCommandController.CommandState
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.databinding.LayoutDashboardBinding
import kotlinx.coroutines.*
import java.util.Locale

class DashboardFragment : ReferenceFragment() {
    private var binding: LayoutDashboardBinding? = null
    private var suppressSwitch = false
    private val controller get() = SagerNet.commandController

    override fun onViewCreated(view: View, state: Bundle?) {
        super.onViewCreated(view, state)
        toolbar.title = "wanBox"
        toolbar.subtitle = "Root"
        val ui = LayoutDashboardBinding.inflate(layoutInflater, body, true)
        binding = ui
        ui.serviceSwitch.setOnCheckedChangeListener { _, checked ->
            if (!suppressSwitch) {
                if (checked) controller.requestStart() else controller.requestStop()
                refreshStatus()
            }
        }
        ui.currentNodeLayout.setOnClickListener { (activity as? MainActivity)?.navigateToNodes() }
        ui.modeRule.setOnClickListener { setMode(MODE_RULE) }
        ui.modeGlobal.setOnClickListener { setMode(MODE_GLOBAL) }
        ui.modeDirect.setOnClickListener { setMode(MODE_DIRECT) }
        refreshStatus()
        renderStats(null)
        val activity = activity as MainActivity
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { activity.speedStatsFlow.collect { renderStats(it) } }
                launch {
                    while (isActive) {
                        refreshStatus()
                        refreshNode()
                        delay(1000)
                    }
                }
                launch {
                    controller.state.collect { command ->
                        when (command) {
                            is CommandState.Confirmed -> {
                                activity.onModuleStateConfirmed(command.status)
                                controller.acknowledge()
                            }
                            is CommandState.Error -> {
                                Toast.makeText(requireContext(), "操作失败，请检查 Root 模块状态",
                                    Toast.LENGTH_LONG).show()
                                controller.acknowledge()
                            }
                            CommandState.Cancelled -> controller.acknowledge()
                            else -> Unit
                        }
                        refreshStatus()
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    fun onServiceStateChanged(state: BaseService.State) {
        refreshStatus()
        if (state != BaseService.State.Connected) renderStats(null)
    }

    private fun refreshStatus() {
        val ui = binding ?: return
        val state = (activity as? MainActivity)?.serviceState ?: DataStore.serviceState
        val pending = controller.state.value as? CommandState.Pending
        ui.statusText.setText(when {
            pending?.kind == CommandKind.START -> R.string.service_state_connecting
            pending?.kind == CommandKind.STOP -> R.string.service_state_stopping
            state == BaseService.State.Connected -> R.string.service_state_connected
            state == BaseService.State.Connecting -> R.string.service_state_connecting
            state == BaseService.State.Stopping -> R.string.service_state_stopping
            else -> R.string.service_state_idle
        })
        suppressSwitch = true
        ui.serviceSwitch.isChecked = state == BaseService.State.Connected || state == BaseService.State.Connecting
        suppressSwitch = false
        ui.serviceSwitch.isEnabled = pending == null &&
            state != BaseService.State.Connecting && state != BaseService.State.Stopping
        listOf(ui.modeRule, ui.modeGlobal, ui.modeDirect).forEachIndexed { index, button ->
            button.isSelected = index == DataStore.dashboardMode
            button.isEnabled = pending == null
        }
    }

    private suspend fun refreshNode() {
        val node = withContext(Dispatchers.IO) {
            val profile = ProfileManager.getProfile(DataStore.selectedProxy)
            profile to profile?.let { SagerDatabase.groupDao.getById(it.groupId) }
        }
        val ui = binding ?: return
        val stats = (activity as? MainActivity)?.lastSpeedStats
        ui.nodeName.text = stats?.runningProfileName?.takeIf { it.isNotBlank() }
            ?: node.first?.displayName() ?: "—"
        ui.nodeInfo.text = node.first?.let { profile ->
            if (profile.ping > 0) getString(R.string.ref_node_info, node.second?.displayName().orEmpty(), profile.ping)
            else node.second?.displayName()
        } ?: "—"
    }

    private fun renderStats(stats: SpeedDisplayData?) {
        val ui = binding ?: return
        fun bytes(value: Long?) = value?.takeIf { it >= 0 }?.let {
            Formatter.formatFileSize(requireContext(), it)
        }
        ui.uploadSpeed.text = bytes(stats?.txRateProxy)?.let { ReferenceUi.quantity("$it/s") } ?: "—"
        ui.downloadSpeed.text = bytes(stats?.rxRateProxy)?.let { ReferenceUi.quantity("$it/s") } ?: "—"
        ui.uploadTotal.text = getString(R.string.ref_total, bytes(stats?.txTotalModuleOrNull()) ?: "—")
        ui.downloadTotal.text = getString(R.string.ref_total, bytes(stats?.rxTotalModuleOrNull()) ?: "—")
        ui.coreMemory.text = bytes(stats?.vmRSSBytes)?.let { ReferenceUi.quantity(it) } ?: "—"
        ui.connectionCount.text = stats?.connectionCountOrNull()?.let { ReferenceUi.quantity("$it 个") } ?: "—"
        ui.uptimeText.text = stats?.uptimeSeconds?.takeIf { it >= 0 }?.let {
            String.format(Locale.ROOT, "%02d:%02d:%02d", it / 3600, it % 3600 / 60, it % 60)
        } ?: "—"
        if (!stats?.runningProfileName.isNullOrBlank()) ui.nodeName.text = stats?.runningProfileName
    }

    private fun setMode(mode: Int) {
        controller.requestMode(listOf("rule", "global", "direct")[mode])
        refreshStatus()
    }

    companion object {
        const val MODE_RULE = 0
        const val MODE_GLOBAL = 1
        const val MODE_DIRECT = 2
    }
}
