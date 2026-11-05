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
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.CircularProgressIndicator
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.bg.RootTunService
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.utils.LandingIpInfo
import io.nekohasekai.sagernet.utils.LandingIpManager
import io.nekohasekai.sagernet.widget.LandingIpBottomSheet
import androidx.core.view.isVisible
import kotlinx.coroutines.*
import java.util.Locale

/** Compact dashboard bound to the same Root service and persisted routing mode. */
class DashboardFragment : ToolbarFragment(R.layout.layout_dashboard) {
    private var statsJob: Job? = null
    private var nodeJob: Job? = null
    private var operationUntil = 0L
    private var renderedHero: Hero? = null
    private var renderedDetail: CharSequence? = null

    /** phase2: the four home states (未连接/连接中/已连接/异常), each with its own colour, text and mascot. */
    private enum class Hero(val fill: Int, val on: Int, val mascot: Int, val title: Int) {
        IDLE(R.color.connect_idle_fill, R.color.connect_idle_on, R.drawable.ic_mascot_sleep, R.string.service_state_idle),
        CONNECTING(R.color.connect_busy_fill, R.color.connect_busy_on, R.drawable.ic_mascot_curious, R.string.service_state_connecting),
        CONNECTED(R.color.connect_on_fill, R.color.connect_on_on, R.drawable.ic_mascot_happy, R.string.service_state_connected),
        ERROR(R.color.connect_error_fill, R.color.connect_error_on, R.drawable.ic_mascot_worried, R.string.home_state_error),
    }

    /** Only touch a TextView when its content actually changed (the render loop runs every second). */
    private fun TextView.setTextIfChanged(value: CharSequence) {
        if (text?.toString() != value.toString()) text = value
    }
    private var renderedProfile = -1L
    private var renderedState: BaseService.State? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.title = "wanBox"
        view.findViewById<View>(R.id.connectButton).setOnClickListener { button ->
            if (SystemClock.elapsedRealtime() < operationUntil) return@setOnClickListener
            val host = activity as? MainActivity ?: return@setOnClickListener
            if (DataStore.hapticFeedback) button.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            val state = host.serviceState
            when {
                state == BaseService.State.Connecting || state == BaseService.State.Stopping -> return@setOnClickListener
                state.canStop -> SagerNet.stopService()
                else -> SagerNet.startService() // 未连接 → 连接；异常(未运行) → 重试
            }
            operationUntil = SystemClock.elapsedRealtime() + 5000L
            bounce(button, 0.92f)
        }
        view.findViewById<View>(R.id.btnFailover).setOnClickListener { failover(it) }
        view.findViewById<View>(R.id.currentNodeLayout).setOnClickListener {
            (activity as? MainActivity)?.navigateToNodes()
        }
        listOf(R.id.modeRule, R.id.modeGlobal, R.id.modeDirect).forEachIndexed { mode, id ->
            view.findViewById<Button>(id).setOnClickListener {
                if (DataStore.dashboardMode != mode) {
                    // fix: 路由模式只写 dashboardMode（Clash 模式 rule/global/direct）。
                    // bypass / proxyApps 属于分应用代理，模式切换不得改写或关闭它们。
                    DataStore.dashboardMode = mode
                    SagerNet.reloadService()
                    renderMode(view)
                }
            }
        }
        renderMode(view)
        // remove(D3)：连接测试 / 落地 IP 从底部 StatsBar 迁到首页「连接检查」卡片
        view.findViewById<View>(R.id.btnTestConnection).setOnClickListener {
            if (DataStore.hapticFeedback) it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            testConnection(view)
        }
        view.findViewById<View>(R.id.btnLandingIp).setOnClickListener {
            it.animate().rotationBy(360f).setDuration(400).start()
            refreshLandingIp(view, force = true)
        }
        view.findViewById<View>(R.id.landingIpLayout).setOnClickListener {
            val cached = LandingIpManager.getCachedInfo() ?: return@setOnClickListener refreshLandingIp(view, force = true)
            LandingIpBottomSheet.show(requireActivity(), cached) { refreshLandingIp(view, force = true) }
        }
        // feat(home): 首页「更新订阅」——更新全部订阅（与订阅页「全部更新」同一逻辑，无二次确认）
        view.findViewById<View>(R.id.btnUpdateSubscription).setOnClickListener { updateAllSubscriptions(it) }
    }

    private fun updateAllSubscriptions(button: View) {
        val host = activity as? MainActivity ?: return
        button.isEnabled = false
        runOnDefaultDispatcher {
            val groups = SagerDatabase.groupDao.allGroups().filter { it.type == GroupType.SUBSCRIPTION }
            if (groups.isEmpty()) {
                onMainDispatcher {
                    button.isEnabled = true
                    host.snackbar(host.getString(R.string.minimal_no_subscription)).show()
                }
                return@runOnDefaultDispatcher
            }
            val results = supervisorScope {
                groups.map { group ->
                    async {
                        try {
                            GroupUpdater.executeUpdate(group, false)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            Logs.w("Home update failed for ${group.displayName()}: ${e.readableMessage}")
                            false
                        }
                    }
                }.awaitAll()
            }
            val ok = results.count { it }
            val fail = results.size - ok
            onMainDispatcher {
                button.isEnabled = true
                if (host.isFinishing || host.isDestroyed) return@onMainDispatcher
                host.snackbar(
                    if (fail == 0) "全部订阅更新完成（共 ${ok} 个）" else "订阅更新完成：${ok} 个成功，${fail} 个失败"
                ).show()
            }
        }
    }

    private var testJob: Job? = null
    private var landingJob: Job? = null
    private var testResult: CharSequence = "—"
    private var landingCheckedProfile = -1L

    private fun testConnection(root: View) {
        val host = activity as? MainActivity ?: return
        val service = host.connection.service
        if (!DataStore.serviceState.connected || service == null) {
            testResult = getString(R.string.not_connected)
            renderCheck(root)
            return
        }
        if (testJob?.isActive == true) return
        testResult = getString(R.string.connection_test_testing)
        renderCheck(root)
        val profileAtStart = DataStore.selectedProxy
        testJob = viewLifecycleOwner.lifecycleScope.launch {
            val elapsed = try {
                withContext(Dispatchers.IO) { host.urlTest(service) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logs.w("testConnection error: $e")
                testResult = getString(R.string.connection_test_error, e.readableMessage)
                renderCheck(root)
                return@launch
            }
            if (DataStore.selectedProxy != profileAtStart) {
                testResult = "—"
            } else if (elapsed > 0) {
                val type = if (DataStore.connectionTestURL.startsWith("https://", true)) "HTTPS" else "HTTP"
                testResult = "$type ${elapsed}ms"
            } else {
                testResult = when (elapsed) {
                    RootTunService.TEST_ERR_NOT_CONNECTED -> "模块未连接"
                    RootTunService.TEST_ERR_SWITCHING -> "节点切换中"
                    RootTunService.TEST_ERR_SWITCHED_DURING_TEST -> "正在确认当前代理出口"
                    RootTunService.TEST_ERR_PROFILE_NOT_FOUND -> "节点配置不存在"
                    RootTunService.TEST_ERR_PROXY_ENTRY -> "本地代理入口不可用"
                    RootTunService.TEST_ERR_HANDSHAKE_TIMEOUT -> "代理握手超时"
                    RootTunService.TEST_ERR_DNS_FAILED -> "DNS 解析失败"
                    RootTunService.TEST_ERR_EGRESS_UNREACHABLE -> "网络出口不可达"
                    else -> getString(R.string.connection_test_fail)
                }
            }
            renderCheck(root)
        }
    }

    private fun refreshLandingIp(root: View, force: Boolean) {
        if (!DataStore.showLandingIp || !DataStore.serviceState.connected) { renderCheck(root); return }
        if (!force && (landingJob?.isActive == true || LandingIpManager.getCachedInfo() != null)) { renderCheck(root); return }
        landingJob?.cancel()
        val profile = DataStore.currentProfile
        root.findViewById<TextView>(R.id.landingIpText).text = getString(R.string.landing_ip_querying)
        landingJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = LandingIpManager.queryLandingIp(profile, forceRefresh = force) { info ->
                lifecycleScope.launch(Dispatchers.Main) {
                    if (isActive && view != null) root.findViewById<TextView>(R.id.landingIpText).text = info.display()
                }
            }
            result.onFailure { Logs.w(it) }
            renderCheck(root)
        }
    }

    private fun LandingIpInfo.display() = "$countryFlag $countryCode $ip".trim()

    /** Renders the「连接检查」card; cheap enough for the 1 s render loop. */
    private fun renderCheck(root: View) {
        val connected = DataStore.serviceState.connected
        if (!connected) {
            testJob?.cancel(); landingJob?.cancel()
            testResult = "—"; landingCheckedProfile = -1L
        }
        root.findViewById<TextView>(R.id.connectionTestResult).text = testResult
        root.findViewById<View>(R.id.btnTestConnection).isEnabled = connected
        val showIp = connected && DataStore.showLandingIp
        root.findViewById<View>(R.id.landingIpLayout).isVisible = showIp
        if (!showIp) return
        val ipText = root.findViewById<TextView>(R.id.landingIpText)
        val cached = LandingIpManager.getCachedInfo()
        ipText.text = when {
            cached != null && cached.ip.isNotBlank() -> cached.display()
            landingJob?.isActive == true || LandingIpManager.isCurrentlyQuerying() -> getString(R.string.landing_ip_querying)
            else -> LandingIpManager.getProfileFallbackDisplay(DataStore.selectedProxy)
        }
        // 节点切换或首次连接后自动查询一次（与旧 StatsBar 行为一致：仅在开启 showLandingIp 时）
        val profile = DataStore.currentProfile
        if (cached == null && landingCheckedProfile != profile && landingJob?.isActive != true) {
            landingCheckedProfile = profile
            refreshLandingIp(root, force = false)
        }
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
                background = ContextCompat.getDrawable(context, if (DataStore.dashboardMode == mode) R.drawable.bg_dashboard_active else R.drawable.bg_dashboard_segment)
                backgroundTintList = null
                isSelected = DataStore.dashboardMode == mode
            }
        }
    }

    private fun render(root: View) {
        val host = activity as? MainActivity ?: return
        val state = host.serviceState
        if (renderedState != state) operationUntil = 0L
        renderedState = state
        renderHero(root, host, state)
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
                // phase2: light spring when the node changes (not on first bind)
                if (nodeBound) bounce(root.findViewById(R.id.currentNodeLayout), 0.97f)
                nodeBound = true
                root.findViewById<TextView>(R.id.nodeName).text = profile?.displayName() ?: getString(R.string.minimal_select)
                root.findViewById<TextView>(R.id.nodeInfo).text = profile?.let {
                    listOfNotNull(result.second?.displayName(), if (it.status == 1) "${it.ping} ms" else it.displayType()).joinToString(" · ")
                } ?: "—"
            }
        }
        val stats = host.lastSpeedStats.takeIf { state.connected }
        fun text(id: Int, value: CharSequence) { root.findViewById<TextView>(id).setTextIfChanged(value) }
        fun value(id: Int, value: CharSequence, activeColor: Int) {
            root.findViewById<TextView>(id).apply {
                val wasDash = text?.toString() == "—"
                setTextIfChanged(value)
                val isDash = value.toString() == "—"
                if (wasDash != isDash || tag == null) {
                    tag = activeColor
                    setTextColor(ContextCompat.getColor(requireContext(), if (isDash) R.color.minimal_secondary else activeColor))
                }
            }
        }
        fun rate(value: Long): CharSequence {
            val formatted = Formatter.formatFileSize(requireContext(), value) + "/s"
            val unit = formatted.indexOf(' ')
            return SpannableString(formatted).apply {
                if (unit >= 0) setSpan(RelativeSizeSpan(0.55f), unit, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        value(R.id.uploadSpeed, stats?.let { rate(it.txRateProxy) } ?: "—", R.color.minimal_data)
        value(R.id.downloadSpeed, stats?.let { rate(it.rxRateProxy) } ?: "—", R.color.minimal_data)
        text(R.id.uploadTotal, getString(R.string.minimal_total, stats?.let { Formatter.formatFileSize(requireContext(), it.txTotal) } ?: "—"))
        text(R.id.downloadTotal, getString(R.string.minimal_total, stats?.let { Formatter.formatFileSize(requireContext(), it.rxTotal) } ?: "—"))
        val memoryText = stats?.let {
            if (it.vmRSSBytes > 0L) Formatter.formatFileSize(requireContext(), it.vmRSSBytes) else null
        } ?: "—"
        value(R.id.coreMemory, memoryText, R.color.minimal_text)
        val connectionsText = stats?.connectionCountOrNull()?.toString() ?: "—"
        value(R.id.connectionCount, connectionsText, R.color.minimal_text)
        val uptime = stats?.let {
            if (it.uptimeSeconds >= 0L) {
                String.format(Locale.ROOT, "%02d:%02d:%02d", it.uptimeSeconds / 3600, it.uptimeSeconds % 3600 / 60, it.uptimeSeconds % 60)
            } else null
        } ?: "—"
        value(R.id.uptimeText, uptime, R.color.minimal_text)
        renderCheck(root)
    }

    private var nodeBound = false

    private fun bounce(view: View, scale: Float) {
        view.animate().cancel()
        view.scaleX = scale; view.scaleY = scale
        view.animate().scaleX(1f).scaleY(1f).setDuration(420)
            .setInterpolator(OvershootInterpolator(2.2f)).start()
    }

    private fun renderHero(root: View, host: MainActivity, state: BaseService.State) {
        val health = host.lastModuleHealth
        val comm = health?.commFailures ?: 0
        val unreachable = comm >= RootTunService.FAILURES_BEFORE_UNKNOWN
        val failed = health?.modulePhase in setOf("failed", "cleanup_required") && !state.started
        val degraded = state == BaseService.State.Connected && health?.moduleHealth == "degraded"
        val hero = when {
            unreachable || failed || degraded -> Hero.ERROR
            state == BaseService.State.Connected -> Hero.CONNECTED
            state == BaseService.State.Connecting || state == BaseService.State.Stopping -> Hero.CONNECTING
            else -> Hero.IDLE
        }
        val ctx = requireContext()
        val detail: CharSequence = when {
            unreachable -> getString(R.string.home_hint_unreachable, comm)
            degraded -> getString(R.string.home_hint_degraded)
            failed -> io.nekohasekai.sagernet.bg.RootModuleErrors.message(ctx,
                health?.moduleError?.ifBlank { null } ?: health?.moduleLastReason?.ifBlank { null } ?: health?.modulePhase.orEmpty())
            state == BaseService.State.Stopping -> getString(R.string.home_hint_stopping)
            hero == Hero.CONNECTING -> getString(R.string.home_hint_connecting)
            hero == Hero.CONNECTED -> host.lastSpeedStats?.uptimeSeconds?.takeIf { it >= 0 }?.let {
                getString(R.string.home_hint_connected, String.format(Locale.ROOT, "%02d:%02d:%02d", it / 3600, it % 3600 / 60, it % 60))
            } ?: getString(R.string.home_hint_connected_plain)
            else -> getString(R.string.home_hint_idle)
        }
        if (detail.toString() != renderedDetail?.toString()) {
            renderedDetail = detail
            root.findViewById<TextView>(R.id.statusDetail).text = detail
        }
        root.findViewById<View>(R.id.btnFailover).isVisible = degraded
        if (hero == renderedHero) return
        val first = renderedHero == null
        renderedHero = hero
        // phase2: mark the home as fully drawn once its state is known (Macrobenchmark / am_activity_launch_time)
        if (first) runCatching { activity?.reportFullyDrawn() }
        val on = ContextCompat.getColor(ctx, hero.on)
        root.findViewById<MaterialCardView>(R.id.connectButton).setCardBackgroundColor(ContextCompat.getColor(ctx, hero.fill))
        root.findViewById<ImageView>(R.id.connectIcon).imageTintList = ColorStateList.valueOf(on)
        root.findViewById<TextView>(R.id.connectLabel).apply {
            setTextColor(on)
            setText(when {
                hero == Hero.CONNECTING -> R.string.home_action_connecting
                state.canStop -> R.string.home_action_disconnect
                hero == Hero.ERROR -> R.string.home_action_retry
                else -> R.string.home_action_connect
            })
        }
        root.findViewById<CircularProgressIndicator>(R.id.connectProgress).visibility =
            if (hero == Hero.CONNECTING) View.VISIBLE else View.INVISIBLE
        root.findViewById<ImageView>(R.id.mascot).setImageResource(hero.mascot)
        root.findViewById<TextView>(R.id.statusText).setText(hero.title)
        if (!first) {
            bounce(root.findViewById(R.id.mascot), 0.85f)
            if (hero == Hero.CONNECTED) bounce(root.findViewById(R.id.connectButton), 0.9f)
        }
    }

    private fun failover(button: View) {
        val host = activity as? MainActivity ?: return
        button.isEnabled = false
        runOnDefaultDispatcher {
            val error = try {
                io.nekohasekai.sagernet.bg.RootModuleClient.call("node failover"); null
            } catch (e: CancellationException) { throw e } catch (e: Exception) { e }
            onMainDispatcher {
                button.isEnabled = true
                if (host.isFinishing || host.isDestroyed) return@onMainDispatcher
                host.snackbar(if (error == null) getString(R.string.home_failover_done)
                    else io.nekohasekai.sagernet.bg.RootModuleErrors.message(host, error)).show()
            }
        }
    }

    override fun onPause() { statsJob?.cancel(); statsJob = null; super.onPause() }
    override fun onDestroyView() {
        statsJob?.cancel(); statsJob = null
        nodeJob?.cancel(); nodeJob = null
        testJob?.cancel(); testJob = null
        landingJob?.cancel(); landingJob = null
        testResult = "—"; landingCheckedProfile = -1L
        renderedProfile = -1L; renderedState = null; operationUntil = 0L
        renderedHero = null; renderedDetail = null; nodeBound = false
        super.onDestroyView()
    }
    companion object {
        const val MODE_RULE = 0
        const val MODE_GLOBAL = 1
        const val MODE_DIRECT = 2
    }
}
