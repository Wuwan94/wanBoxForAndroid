package io.nekohasekai.sagernet.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.format.Formatter
import android.text.style.RelativeSizeSpan
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.switchmaterial.SwitchMaterial
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import kotlinx.coroutines.*
import java.util.Locale

/** Compact dashboard bound to the same Root service and persisted routing mode. */
class DashboardFragment : ToolbarFragment(R.layout.layout_dashboard) {
    private var statsJob: Job? = null
    private var nodeJob: Job? = null
    private var suppressSwitch = false
    private var operationUntil = 0L
    private var renderedProfile = -1L
    private var renderedState: BaseService.State? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.title = "wanBox"
        val switch = view.findViewById<SwitchMaterial>(R.id.serviceSwitch)
        switch.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            operationUntil = SystemClock.elapsedRealtime() + 5000L
            switch.isEnabled = false
            if (checked) SagerNet.startService() else SagerNet.stopService()
        }
        view.findViewById<View>(R.id.currentNodeLayout).setOnClickListener {
            (activity as? MainActivity)?.navigateToNodes()
        }
        listOf(R.id.modeRule, R.id.modeGlobal, R.id.modeDirect).forEachIndexed { mode, id ->
            view.findViewById<Button>(id).setOnClickListener {
                if (DataStore.dashboardMode != mode) {
                    DataStore.bypass = mode != MODE_GLOBAL
                    DataStore.proxyApps = false
                    DataStore.dashboardMode = mode
                    SagerNet.reloadService()
                    renderMode(view)
                }
            }
        }
        renderMode(view)
    }

    override fun onResume() {
        super.onResume()
        val root = view ?: return
        statsJob?.cancel()
        statsJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                render(root)
                delay(1000)
            }
        }
    }

    private fun renderMode(root: View) {
        listOf(R.id.modeRule, R.id.modeGlobal, R.id.modeDirect).forEachIndexed { mode, id ->
            root.findViewById<Button>(id).apply {
                background = ContextCompat.getDrawable(context, if (DataStore.dashboardMode == mode) R.drawable.bg_minimal_active else R.drawable.bg_minimal_segment)
                backgroundTintList = null
                isSelected = DataStore.dashboardMode == mode
            }
        }
    }

    private fun render(root: View) {
        val host = activity as? MainActivity ?: return
        val state = host.serviceState
        val switch = root.findViewById<SwitchMaterial>(R.id.serviceSwitch)
        if (renderedState != state) operationUntil = 0L
        renderedState = state
        suppressSwitch = true
        switch.isChecked = state == BaseService.State.Connected || state == BaseService.State.Connecting
        suppressSwitch = false
        switch.isEnabled = state != BaseService.State.Connecting && state != BaseService.State.Stopping && SystemClock.elapsedRealtime() >= operationUntil
        root.findViewById<TextView>(R.id.statusText).setText(when (state) {
            BaseService.State.Connected -> R.string.service_state_connected
            BaseService.State.Connecting -> R.string.service_state_connecting
            BaseService.State.Stopping -> R.string.service_state_stopping
            else -> R.string.service_state_idle
        })
        val profileId = if (state.connected && DataStore.currentProfile > 0) DataStore.currentProfile else DataStore.selectedProxy
        if (profileId != renderedProfile) {
            renderedProfile = profileId
            nodeJob?.cancel()
            nodeJob = viewLifecycleOwner.lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    val profile = SagerDatabase.proxyDao.getById(profileId)
                    val group = profile?.let { SagerDatabase.groupDao.getById(it.groupId) }
                    profile to group
                }
                val profile = result.first
                root.findViewById<TextView>(R.id.nodeName).text = profile?.displayName() ?: getString(R.string.minimal_select)
                root.findViewById<TextView>(R.id.nodeInfo).text = profile?.let {
                    listOfNotNull(result.second?.displayName(), if (it.status == 1) "${it.ping} ms" else it.displayType()).joinToString(" · ")
                } ?: "—"
            }
        }
        val stats = host.lastSpeedStats.takeIf { state.connected }
        fun text(id: Int, value: CharSequence) { root.findViewById<TextView>(id).text = value }
        fun rate(value: Long): CharSequence {
            val formatted = Formatter.formatFileSize(requireContext(), value) + "/s"
            val unit = formatted.indexOf(' ')
            return SpannableString(formatted).apply {
                if (unit >= 0) setSpan(RelativeSizeSpan(0.55f), unit, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        text(R.id.uploadSpeed, stats?.let { rate(it.txRateProxy) } ?: "—")
        text(R.id.downloadSpeed, stats?.let { rate(it.rxRateProxy) } ?: "—")
        text(R.id.uploadTotal, getString(R.string.minimal_total, stats?.let { Formatter.formatFileSize(requireContext(), it.txTotal) } ?: "—"))
        text(R.id.downloadTotal, getString(R.string.minimal_total, stats?.let { Formatter.formatFileSize(requireContext(), it.rxTotal) } ?: "—"))
        // The v3.0.8 module protocol does not expose memory or connection counts.
        text(R.id.coreMemory, "—")
        text(R.id.connectionCount, "—")
        val seconds = if (host.connectedSince > 0 && state.connected) (SystemClock.elapsedRealtime() - host.connectedSince) / 1000 else null
        text(R.id.uptimeText, seconds?.let { String.format(Locale.ROOT, "%02d:%02d:%02d", it / 3600, it % 3600 / 60, it % 60) } ?: "—")
    }

    override fun onPause() { statsJob?.cancel(); statsJob = null; super.onPause() }
    override fun onDestroyView() {
        statsJob?.cancel(); nodeJob?.cancel()
        renderedProfile = -1L; renderedState = null; operationUntil = 0L
        super.onDestroyView()
    }
    companion object {
        const val MODE_RULE = 0
        const val MODE_GLOBAL = 1
        const val MODE_DIRECT = 2
    }
}
