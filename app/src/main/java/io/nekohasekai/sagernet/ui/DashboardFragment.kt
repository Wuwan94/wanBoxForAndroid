package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.os.SystemClock
import android.text.format.Formatter
import android.view.View
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.databinding.LayoutDashboardBinding
import kotlinx.coroutines.*
import java.util.Locale

class DashboardFragment : ReferenceFragment() {
    private var binding: LayoutDashboardBinding? = null
    private var polling: Job? = null
    private var suppressSwitch = false
    private var serviceBusy = false
    private var pendingServiceTarget: Boolean? = null
    private var modeBusy = false
    private var unlock: Job? = null

    override fun onViewCreated(view: View, state: Bundle?) {
        super.onViewCreated(view, state)
        toolbar.title = "wanBox"
        toolbar.subtitle = "Root"
        val ui = LayoutDashboardBinding.inflate(layoutInflater, body, true)
        binding = ui
        ui.serviceSwitch.setOnCheckedChangeListener { _, checked ->
            if (!suppressSwitch && !serviceBusy) {
                serviceBusy = true
                pendingServiceTarget = checked
                ui.serviceSwitch.isEnabled = false
                if (checked) SagerNet.startService() else SagerNet.stopService()
                unlock?.cancel()
                unlock = viewLifecycleOwner.lifecycleScope.launch {
                    delay(15000)
                    serviceBusy = false
                    pendingServiceTarget = null
                    binding?.serviceSwitch?.isEnabled = true
                    refreshStatus()
                }
            }
        }
        ui.currentNodeLayout.setOnClickListener { (activity as? MainActivity)?.navigateToNodes() }
        ui.modeRule.setOnClickListener { setMode(0) }
        ui.modeGlobal.setOnClickListener { setMode(1) }
        ui.modeDirect.setOnClickListener { setMode(2) }
        refreshStatus()
        refreshMode()
    }

    override fun onResume() {
        super.onResume()
        polling = viewLifecycleOwner.lifecycleScope.launch {
            var tick = 0
            while (isActive) {
                refreshStatus()
                refreshNodeAndTraffic()
                if (DataStore.serviceState == BaseService.State.Connected && tick++ % 3 == 0) {
                    val metrics = ReferenceDashboardBackend.metrics()
                    binding?.coreMemory?.text = metrics.first?.let { ReferenceUi.quantity(Formatter.formatFileSize(requireContext(), it)) } ?: "—"
                    binding?.connectionCount?.text = metrics.second?.let { ReferenceUi.quantity("$it 个") } ?: "—"
                    if (!modeBusy) {
                        try { DataStore.dashboardMode = ReferenceDashboardBackend.mode(); refreshMode() }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { }
                    }
                }
                delay(1000)
            }
        }
    }
    override fun onPause() { polling?.cancel(); polling = null; super.onPause() }
    override fun onDestroyView() {
        polling?.cancel()
        unlock?.cancel()
        binding = null
        super.onDestroyView()
    }
    private fun refreshStatus() {
        val ui = binding ?: return
        val state = DataStore.serviceState
        ui.statusText.setText(when (state) {
            BaseService.State.Connected -> R.string.service_state_connected
            BaseService.State.Connecting -> R.string.service_state_connecting
            BaseService.State.Stopping -> R.string.service_state_stopping
            else -> R.string.service_state_idle
        })
        suppressSwitch = true
        ui.serviceSwitch.isChecked = state == BaseService.State.Connected || state == BaseService.State.Connecting
        suppressSwitch = false
        if (pendingServiceTarget != null && state != BaseService.State.Connecting &&
            state != BaseService.State.Stopping && (state == BaseService.State.Connected) == pendingServiceTarget) {
            serviceBusy = false
            pendingServiceTarget = null
            unlock?.cancel()
        }
        ui.serviceSwitch.isEnabled = !serviceBusy && state != BaseService.State.Connecting && state != BaseService.State.Stopping
        val start = (activity as? MainActivity)?.serviceConnectedAt ?: 0L
        val seconds = if (start > 0L) (SystemClock.elapsedRealtime() - start) / 1000 else 0L
        ui.uptimeText.text = String.format(Locale.ROOT, "%02d:%02d:%02d",
            seconds / 3600, seconds % 3600 / 60, seconds % 60)
        if (state != BaseService.State.Connected) {
            listOf(ui.uploadSpeed, ui.downloadSpeed, ui.uploadTotal, ui.downloadTotal,
                ui.coreMemory, ui.connectionCount).forEach { it.text = "—" }
        }
    }
    private suspend fun refreshNodeAndTraffic() {
        val node = withContext(Dispatchers.IO) {
            val profile = ProfileManager.getProfile(DataStore.selectedProxy)
            profile to profile?.let { SagerDatabase.groupDao.getById(it.groupId) }
        }
        val ui = binding ?: return
        ui.nodeName.text = node.first?.displayName() ?: "—"
        ui.nodeInfo.text = node.first?.let { profile ->
            if (profile.ping > 0) getString(R.string.ref_node_info, node.second?.displayName().orEmpty(), profile.ping)
            else node.second?.displayName()
        } ?: "—"
        if (DataStore.serviceState != BaseService.State.Connected) return
        val stats = (activity as? MainActivity)?.lastSpeedStats ?: return
        ui.uploadSpeed.text = ReferenceUi.quantity(Formatter.formatFileSize(requireContext(), stats.txRateProxy) + "/s")
        ui.downloadSpeed.text = ReferenceUi.quantity(Formatter.formatFileSize(requireContext(), stats.rxRateProxy) + "/s")
        ui.uploadTotal.text = getString(R.string.ref_total, Formatter.formatFileSize(requireContext(), stats.txTotal))
        ui.downloadTotal.text = getString(R.string.ref_total, Formatter.formatFileSize(requireContext(), stats.rxTotal))
    }
    private fun refreshMode() {
        binding?.let {
            listOf(it.modeRule, it.modeGlobal, it.modeDirect).forEachIndexed { index, button ->
                button.isSelected = index == DataStore.dashboardMode
                button.isEnabled = !modeBusy
            }
        }
    }
    private fun setMode(mode: Int) {
        if (modeBusy) return
        modeBusy = true
        refreshMode()
        val context = requireContext().applicationContext
        SagerNet.application.applicationScope.launch(Dispatchers.Main.immediate) {
            try {
                if (DataStore.serviceState == BaseService.State.Connected) ReferenceDashboardBackend.setMode(mode)
                DataStore.dashboardMode = mode
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                android.widget.Toast.makeText(context, error.message ?: "模式切换失败",
                    android.widget.Toast.LENGTH_LONG).show()
            } finally {
                modeBusy = false
                refreshMode()
            }
        }
    }
    companion object {
        const val MODE_RULE = 0
        const val MODE_GLOBAL = 1
        const val MODE_DIRECT = 2
    }
}
