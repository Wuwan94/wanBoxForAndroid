// @author 雾晚
package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import androidx.core.app.ActivityCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.preference.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.bg.ModuleDiagnostics
import io.nekohasekai.sagernet.bg.NotificationGate
import io.nekohasekai.sagernet.bg.SmartAutoStopNotifications
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.SpeedTestSettings
import io.nekohasekai.sagernet.core.CoreRuntime
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.utils.AppLocale
import io.nekohasekai.sagernet.utils.Theme
import moe.matsuri.nb4a.ui.*
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.File
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import libcore.Libcore

class SettingsPreferenceFragment : PreferenceFragmentCompat(), OnPreferenceDataStoreChangeListener {


    private val notificationPermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        val available = granted && SmartAutoStopNotifications.availability(requireContext()) == NotificationGate.AVAILABLE
        setAutoStopEnabled(available)
        if (!available) {
            Toast.makeText(requireContext(), R.string.smart_auto_stop_permission_denied, Toast.LENGTH_LONG).show()
        }
        updateAutoStopPermission()
    }

    private fun setAutoStopEnabled(enabled: Boolean) {
        findPreference<SwitchPreference>(Key.SMART_AUTO_STOP_ENABLED)?.let { preference ->
            // Update the switch before publishing settings, without persisting a second time.
            val wasPersistent = preference.isPersistent
            preference.isPersistent = false
            try {
                preference.isChecked = enabled
            } finally {
                preference.isPersistent = wasPersistent
            }
        }
        if (DataStore.smartAutoStopEnabled != enabled) {
            DataStore.smartAutoStopEnabled = enabled
        }
    }

    // B6（N4）：共享接管开关与 setAutoStopEnabled 同款写法（先改 UI 再落库，避免二次持久化）。
    private fun setSharedInterceptionEnabled(enabled: Boolean) {
        findPreference<SwitchPreference>(Key.SHARED_INTERCEPTION_ENABLED)?.let { preference ->
            val wasPersistent = preference.isPersistent
            preference.isPersistent = false
            try {
                preference.isChecked = enabled
            } finally {
                preference.isPersistent = wasPersistent
            }
        }
        if (DataStore.sharedInterceptionEnabled != enabled) {
            DataStore.sharedInterceptionEnabled = enabled
        }
    }

    private fun sharedConfig(): io.nekohasekai.sagernet.bg.SharedInterceptionConfig =
        io.nekohasekai.sagernet.bg.SharedInterception.parse(DataStore.sharedInterceptionConfig)
            .getOrDefault(io.nekohasekai.sagernet.bg.SharedInterception.defaults())

    private fun saveSharedConfig(cfg: io.nekohasekai.sagernet.bg.SharedInterceptionConfig) {
        DataStore.sharedInterceptionConfig = io.nekohasekai.sagernet.bg.SharedInterception.serialize(
            cfg.copy(
                enabled = DataStore.sharedInterceptionEnabled,
                defaultInterface = DataStore.sharedInterceptionDefaultInterface,
            )
        )
    }

    // B6（N4）：下游接口多选（Root 枚举候选，失败回退本地枚举；loopback 不得入选）。
    private fun showSharedInterfacePicker() {
        lifecycleScope.launch {
            val candidates = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                io.nekohasekai.sagernet.bg.SharedInterception.listCandidateInterfaces()
            }
            val activity = activity
            if (activity == null || activity.isFinishing || activity.isDestroyed) return@launch
            if (candidates.isEmpty()) {
                Toast.makeText(requireContext(), R.string.shared_interception_no_candidate, Toast.LENGTH_LONG).show()
                return@launch
            }
            val current = sharedConfig().interfaces.toSet()
            val checked = candidates.map { it in current }.toBooleanArray()
            val selected = current.toMutableSet()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.shared_interception_interfaces)
                .setMultiChoiceItems(candidates.toTypedArray(), checked) { _, which, isChecked ->
                    if (isChecked) selected.add(candidates[which]) else selected.remove(candidates[which])
                }
                .setPositiveButton(R.string.confirm) { _, _ ->
                    saveSharedConfig(sharedConfig().copy(interfaces = selected.sorted()))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    // B6（N4）：数据平面二选一；预检失败不得自动切换（此处只保存用户选择）。
    private fun showSharedDataPlanePicker() {
        val options = listOf(
            io.nekohasekai.sagernet.bg.SharedInterceptionConfig.DATA_PLANE_SOCKET_ASSIGN to
                getString(R.string.shared_interception_data_plane_socket_assign),
            io.nekohasekai.sagernet.bg.SharedInterceptionConfig.DATA_PLANE_PACKET_REWRITE to
                getString(R.string.shared_interception_data_plane_packet_rewrite),
        )
        val current = sharedConfig().dataPlane
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.shared_interception_data_plane)
            .setSingleChoiceItems(
                options.map { it.second }.toTypedArray(),
                options.indexOfFirst { it.first == current }.coerceAtLeast(0),
            ) { dialog, which ->
                saveSharedConfig(sharedConfig().copy(dataPlane = options[which].first))
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun bindSharedTextPreference(key: String, initial: String, onValue: (String) -> Boolean) {
        val preference = findPreference<EditTextPreference>(key) ?: return
        preference.text = initial
        preference.setOnPreferenceChangeListener { _, value ->
            onValue(value as String)
        }
    }

    private fun bindSharedListPreference(
        key: String,
        initial: List<String>,
        onSave: (List<String>) -> Unit,
    ) {
        val preference = findPreference<EditTextPreference>(key) ?: return
        preference.text = initial.joinToString(", ")
        preference.setOnBindEditTextListener {
            it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            it.setSingleLine(false)
        }
        preference.setOnPreferenceChangeListener { _, value ->
            val values = (value as String).split(",").map { it.trim() }.filter { it.isNotEmpty() }
            // 先校验合并后的完整配置，非法时拒绝保存并提示。
            val merged = when (key) {
                "sharedInterceptionIncludeCidr" -> sharedConfig().copy(includeSourceCidr = values)
                "sharedInterceptionExcludeCidr" -> sharedConfig().copy(excludeSourceCidr = values)
                "sharedInterceptionIncludeMac" -> sharedConfig().copy(includeMacAddress = values)
                "sharedInterceptionExcludeMac" -> sharedConfig().copy(excludeMacAddress = values)
                else -> sharedConfig()
            }
            val error = io.nekohasekai.sagernet.bg.SharedInterception.validate(merged).exceptionOrNull()
            if (error != null) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.shared_interception_invalid, error.message ?: "invalid"),
                    Toast.LENGTH_LONG,
                ).show()
                false
            } else {
                onSave(values)
                true
            }
        }
    }

    private fun updateAutoStopPermission() {
        val available = SmartAutoStopNotifications.availability(requireContext()) == NotificationGate.AVAILABLE
        if (!available) setAutoStopEnabled(false)
        findPreference<Preference>("smartAutoStopPermission")?.isVisible = !available || DataStore.smartAutoStopEnabled
    }

    private lateinit var globalCustomConfig: EditConfigPreference

    private fun tintPreferenceIcons(group: PreferenceGroup, color: Int) {
        for (i in 0 until group.preferenceCount) {
            val pref = group.getPreference(i)
            if (pref is PreferenceGroup) {
                tintPreferenceIcons(pref, color)
            } else {
                pref.icon?.let { icon ->
                    val tinted = icon.mutate()
                    DrawableCompat.setTint(tinted, color)
                    pref.icon = tinted
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        DataStore.configurationStore.registerChangeListener(this)
        listView.layoutManager = FixedLinearLayoutManager(listView)
        setDivider(null)
        setDividerHeight(0)
        listView.clipToPadding = false
        listView.setPadding(0, dp2px(8), 0, dp2px(16))
    }

    override fun onDestroyView() {
        DataStore.configurationStore.unregisterChangeListener(this)
        super.onDestroyView()
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        if (key == Key.MTU && DataStore.serviceState.started) {
            // The persisted MTU needs a new module snapshot, without recreating MainActivity.
            SagerNet.reloadService()
        }
        // B6（N3/N4）：统一按网络规则表与共享接管配置变更后重载（失败弹可读错误）。
        // 通知门禁复核只属于按网络规则开关；共享配置变更不得误关规则开关。
        if (key == Key.SMART_AUTO_STOP_ENABLED || key == Key.NETWORK_RULES) {
            updateAutoStopPermission()
        }
        if (key == Key.SMART_AUTO_STOP_ENABLED || key == Key.NETWORK_RULES ||
            key == Key.SHARED_INTERCEPTION_ENABLED || key == Key.SHARED_INTERCEPTION_CONFIG ||
            key == Key.SHARED_INTERCEPTION_DEFAULT_INTERFACE
        ) {
            SagerNet.reloadService { error ->
                runOnMainDispatcher {
                    val activity = activity
                    if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                        activity.alert(io.nekohasekai.sagernet.bg.SmartAutoStopApplyErrors.message(activity, error)).tryToShow()
                    }
                }
            }
        }
        if (key == Key.PROFILE_CARD_STYLE) {
            runOnMainDispatcher {
                listView?.adapter?.notifyDataSetChanged()
            }
        }
        if (key == Key.PERSIST_ACROSS_REBOOT) {
            // 任何来源（开关、备份恢复、旧版本升级）改了持久值，都立刻让
            // BOOT_COMPLETED 接收器跟上，不等下一次服务启动。
            io.nekohasekai.sagernet.bg.AutoStartSettings.syncBootReceiver()
        }
    }

    private val reloadListener = Preference.OnPreferenceChangeListener { _, _ ->
        needReload()
        true
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.preferenceDataStore = DataStore.configurationStore
        DataStore.initGlobal()
        addPreferencesFromResource(R.xml.global_preferences)

        val iconColor = Theme.getPrimaryColor(requireContext())
        tintPreferenceIcons(preferenceScreen, iconColor)

        val nightTheme = findPreference<SimpleMenuPreference>(Key.NIGHT_THEME)!!
        nightTheme.setOnPreferenceChangeListener { _, newTheme ->
            Theme.currentNightMode = (newTheme as String).toInt()
            Theme.applyNightTheme()
            true
        }
        val appLanguage = findPreference<SimpleMenuPreference>(Key.APP_LANGUAGE)!!
        appLanguage.setOnPreferenceChangeListener { _, newValue ->
            AppLocale.apply(newValue as String)
            true
        }
        val mixedPort = findPreference<EditTextPreference>(Key.MIXED_PORT)!!
        val disableMixedInbound = findPreference<SwitchPreference>(Key.DISABLE_MIXED_INBOUND)!!
        val mixedAuthConfig = findPreference<Preference>(Key.MIXED_AUTH_CONFIG)!!
        val dnsHosts = findPreference<EditTextPreference>(Key.DNS_HOSTS)!!
        val strictRoute = findPreference<SwitchPreference>(Key.STRICT_ROUTE)!!
        val speedTestMode = findPreference<SimpleMenuPreference>(Key.SPEED_TEST_MODE)!!
        val speedTestTimeout = findPreference<EditTextPreference>(Key.SPEED_TEST_TIMEOUT_MS)!!
        val simpleDownloadURL = findPreference<EditTextPreference>(Key.SIMPLE_DOWNLOAD_URL)!!
        // B6（N3）：首次打开设置页时把 F1 旧规则迁移进统一规则表（失败保留原数据）。
        io.nekohasekai.sagernet.bg.NetworkRules.migrateIfNeeded()
        // B6（N3）：统一规则表入口（取代 F1 独立规则编辑框）；单规则表、单开关。
        findPreference<Preference>("networkRules")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), NetworkRulesActivity::class.java))
            true
        }
        findPreference<SwitchPreference>(Key.SHARED_INTERCEPTION_ENABLED)?.setOnPreferenceChangeListener { _, enabled ->
            if (enabled == true) {
                // 开启前先跑 eBPF 能力预检预览；未通过不开启、不切换数据平面。
                // 预检跑不通（如无 Root）时放行，由 Root apply 门禁兜底。
                lifecycleScope.launch {
                    val missing = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        io.nekohasekai.sagernet.bg.SharedInterception.preflight(sharedConfig().dataPlane)
                    }
                    val activity = activity
                    if (activity == null || activity.isFinishing || activity.isDestroyed) return@launch
                    if (missing != null && missing.isNotEmpty()) {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.shared_interception_preflight_failed, missing.joinToString(", ")),
                            Toast.LENGTH_LONG,
                        ).show()
                        setSharedInterceptionEnabled(false)
                    } else {
                        setSharedInterceptionEnabled(true)
                    }
                }
                return@setOnPreferenceChangeListener false
            }
            true
        }
        findPreference<Preference>("sharedInterceptionInterfaces")?.setOnPreferenceClickListener {
            showSharedInterfacePicker()
            true
        }
        findPreference<Preference>("sharedInterceptionDataPlane")?.setOnPreferenceClickListener {
            showSharedDataPlanePicker()
            true
        }
        // B6（N4）：UDP 超时与源筛选均为非持久化编辑框，值写入共享配置 JSON。
        bindSharedTextPreference("sharedInterceptionUdpTimeout", sharedConfig().udpTimeoutSeconds.toString()) { value ->
            val seconds = value.trim().toLongOrNull()
            if (seconds == null || seconds < io.nekohasekai.sagernet.bg.SharedInterceptionConfig.MIN_UDP_TIMEOUT_SECONDS) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.shared_interception_invalid, "shared_udp_timeout_invalid"),
                    Toast.LENGTH_LONG,
                ).show()
                false
            } else {
                saveSharedConfig(sharedConfig().copy(udpTimeoutSeconds = seconds))
                true
            }
        }
        bindSharedListPreference("sharedInterceptionIncludeCidr", sharedConfig().includeSourceCidr) { values ->
            saveSharedConfig(sharedConfig().copy(includeSourceCidr = values))
        }
        bindSharedListPreference("sharedInterceptionExcludeCidr", sharedConfig().excludeSourceCidr) { values ->
            saveSharedConfig(sharedConfig().copy(excludeSourceCidr = values))
        }
        bindSharedListPreference("sharedInterceptionIncludeMac", sharedConfig().includeMacAddress) { values ->
            saveSharedConfig(sharedConfig().copy(includeMacAddress = values))
        }
        bindSharedListPreference("sharedInterceptionExcludeMac", sharedConfig().excludeMacAddress) { values ->
            saveSharedConfig(sharedConfig().copy(excludeMacAddress = values))
        }
        findPreference<SwitchPreference>(Key.SMART_AUTO_STOP_ENABLED)?.setOnPreferenceChangeListener { _, enabled ->
            if (enabled == true) {
                when (SmartAutoStopNotifications.availability(requireContext())) {
                    NotificationGate.REQUEST_PERMISSION -> {
                        notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        return@setOnPreferenceChangeListener false
                    }
                    NotificationGate.OPEN_SETTINGS -> {
                        setAutoStopEnabled(false)
                        updateAutoStopPermission()
                        Toast.makeText(requireContext(), R.string.smart_auto_stop_permission_denied, Toast.LENGTH_LONG).show()
                        return@setOnPreferenceChangeListener false
                    }
                    NotificationGate.AVAILABLE -> Unit
                }
            }
            true
        }
        findPreference<Preference>("smartAutoStopPermission")?.setOnPreferenceClickListener {
            startActivity(SmartAutoStopNotifications.settingsIntent(requireContext()))
            true
        }
        val ipv6Mode = findPreference<Preference>(Key.IPV6_MODE)!!
        val trafficSniffing = findPreference<Preference>(Key.TRAFFIC_SNIFFING)!!
        val blockQuic = findPreference<SwitchPreference>(Key.BLOCK_QUIC)!!

        val bypassLan = findPreference<SwitchPreference>(Key.BYPASS_LAN)!!
        val bypassLanInCore = findPreference<SwitchPreference>(Key.BYPASS_LAN_IN_CORE)!!

        val remoteDns = findPreference<EditTextPreference>(Key.REMOTE_DNS)!!
        val directDns = findPreference<EditTextPreference>(Key.DIRECT_DNS)!!
        val enableDnsRouting = findPreference<SwitchPreference>(Key.ENABLE_DNS_ROUTING)!!
        val enableFakeDns = findPreference<SwitchPreference>(Key.ENABLE_FAKEDNS)!!

        val enableTLSFragment = findPreference<SwitchPreference>(Key.ENABLE_TLS_FRAGMENT)!!

        val logLevel = findPreference<LongClickListPreference>(Key.LOG_LEVEL)!!
        val mtu = findPreference<MTUPreference>(Key.MTU)!!
        globalCustomConfig = findPreference(Key.GLOBAL_CUSTOM_CONFIG)!!
        globalCustomConfig.useConfigStore(Key.GLOBAL_CUSTOM_CONFIG)

        logLevel.dialogLayoutResource = R.layout.layout_loglevel_help
        logLevel.setOnPreferenceChangeListener { _, _ ->
            needRestart()
            true
        }
        // remove(D16): 长按设置日志缓冲 logBufSize 已删除（仅内嵌核心生效，瘦管理器无效；键保留不读写）

        mixedPort.setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        dnsHosts.setOnBindEditTextListener(EditTextPreferenceModifiers.Hosts)
        dnsHosts.summaryProvider = ListSummaryProvider(maxLines = 1)

        speedTestMode.setOnPreferenceChangeListener { _, newValue ->
            SpeedTestSettings.isValidMode(newValue.toString())
        }
        speedTestTimeout.setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        speedTestTimeout.setOnPreferenceChangeListener { _, newValue ->
            val valid = SpeedTestSettings.isValidTimeout(newValue.toString())
            if (!valid) {
                Toast.makeText(requireContext(), R.string.speed_test_timeout_invalid, Toast.LENGTH_SHORT).show()
            }
            valid
        }
        simpleDownloadURL.setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            editText.setSingleLine()
        }
        simpleDownloadURL.setOnPreferenceChangeListener { preference, newValue ->
            val value = newValue.toString().trim()
            val valid = SpeedTestSettings.isValidHttpUrl(value)
            if (!valid) {
                Toast.makeText(requireContext(), R.string.speed_test_url_invalid, Toast.LENGTH_SHORT).show()
            } else if (value != newValue) {
                (preference as EditTextPreference).text = value
                return@setOnPreferenceChangeListener false
            }
            valid
        }



        // preview.17: TUN 实现固定为 Sing-Tun，不再提供选择入口
        val resolveDestination = findPreference<SwitchPreference>(Key.RESOLVE_DESTINATION)!!

        val rulesProvider = findPreference<SimpleMenuPreference>(Key.RULES_PROVIDER)!!
        // @author 雾晚：自定义规则库地址只在“自定义”来源（4）下显示
        fun updateRulesUrlVisibility(provider: Int = DataStore.rulesProvider) {
            findPreference<Preference>(Key.RULES_GEOSITE_URL)?.isVisible = provider == 4
            findPreference<Preference>(Key.RULES_GEOIP_URL)?.isVisible = provider == 4
        }
        rulesProvider.setOnPreferenceChangeListener { _, newValue ->
            updateRulesUrlVisibility((newValue as String).toInt())
            true
        }

        // 禁用混合入站时代理端口及身份验证设置项变灰。
        fun updateMixedPortState(disabled: Boolean = DataStore.disableMixedInbound) {
            mixedPort.isEnabled = !disabled
            mixedAuthConfig.isEnabled = !disabled
            if (disabled) {
                mixedPort.summaryProvider = null
                mixedPort.summary = getString(R.string.mixed_inbound_disabled)
            } else {
                mixedPort.summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
            }
        }
        updateMixedPortState()
        disableMixedInbound.setOnPreferenceChangeListener { _, newValue ->
            val disabled = newValue as Boolean
            updateMixedPortState(disabled)
            needReload()
            true
        }
        // 配置身份验证：弹窗编辑混合入站用户名/密码，两项均留空则不启用认证
        fun updateMixedAuthSummary() {
            mixedAuthConfig.summary = DataStore.mixedUsername.takeIf { it.isNotBlank() }
                ?.let { getString(R.string.mixed_auth_enabled_sum, it) }
                ?: getString(R.string.mixed_auth_no_auth)
        }
        updateMixedAuthSummary()
        mixedAuthConfig.setOnPreferenceClickListener {
            val view = layoutInflater.inflate(R.layout.layout_mixed_auth_dialog, null)
            val usernameEdit = view.findViewById<EditText>(R.id.mixed_username_edit)
            val passwordEdit = view.findViewById<EditText>(R.id.mixed_password_edit)
            usernameEdit.setText(DataStore.mixedUsername)
            passwordEdit.setText(DataStore.mixedPassword)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.mixed_auth_config)
                .setView(view)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    DataStore.mixedUsername = usernameEdit.text.toString().trim()
                    DataStore.mixedPassword = passwordEdit.text.toString()
                    updateMixedAuthSummary()
                    needReload()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }

        mixedPort.onPreferenceChangeListener = reloadListener
        dnsHosts.onPreferenceChangeListener = reloadListener
        strictRoute.onPreferenceChangeListener = reloadListener
        trafficSniffing.onPreferenceChangeListener = reloadListener
        blockQuic.onPreferenceChangeListener = reloadListener
        bypassLan.onPreferenceChangeListener = reloadListener
        bypassLanInCore.onPreferenceChangeListener = reloadListener
        mtu.setOnPreferenceChangeListener { _, newValue ->
            newValue.toString().toIntOrNull()?.let { it in 1000..10000 } == true
        }

        val dualNetworkAcceleration = findPreference<SwitchPreference>(Key.DUAL_NETWORK_ACCELERATION)!!
        dualNetworkAcceleration.onPreferenceChangeListener = reloadListener
        findPreference<SwitchPreference>(Key.CONCURRENT_DIAL)?.onPreferenceChangeListener = reloadListener
        findPreference<SwitchPreference>(Key.AUTO_SELECT_LOWEST_LATENCY)?.onPreferenceChangeListener = reloadListener

        enableFakeDns.onPreferenceChangeListener = reloadListener
        remoteDns.onPreferenceChangeListener = reloadListener
        directDns.onPreferenceChangeListener = reloadListener
        enableDnsRouting.onPreferenceChangeListener = reloadListener

        ipv6Mode.onPreferenceChangeListener = reloadListener

        resolveDestination.onPreferenceChangeListener = reloadListener
        // preview.17: TUN 实现固定为 Sing-Tun，移除选择器
        // Root-inapplicable controls are absent from XML; their historical
        // persisted keys remain available to backup/restore without mutation.
        val performancePriorityMode = findPreference<SwitchPreference>(Key.PERFORMANCE_PRIORITY_MODE)
        performancePriorityMode?.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            SagerNet.updatePerformancePriorityMode(enabled)
            needReload()
            true
        }

        enableTLSFragment.onPreferenceChangeListener = reloadListener

        // 恢复默认设置功能
        val resetSettings = findPreference<Preference>("resetSettings")!!
        resetSettings.setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(requireContext()).apply {
                setTitle(R.string.confirm)
                setMessage(R.string.reset_settings_message)
                setNegativeButton(R.string.no, null)
                setPositiveButton(R.string.yes) { _, _ ->
                    DataStore.configurationStore.reset()
                    triggerFullRestart(requireContext())
                }
            }.show()
            true
        }

        // 清理缓存功能
        val clearCache = findPreference<Preference>(Key.CLEAR_CACHE)!!
        clearCache.setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(requireContext()).apply {
                setTitle(R.string.clear_cache)
                setMessage(R.string.clear_cache_confirm)
                setPositiveButton(android.R.string.ok) { _, _ ->
                    clearAppCache()
                }
                setNegativeButton(android.R.string.cancel, null)
            }.show()
            true
        }

        // 局域网共享
        val lanSharingPref = findPreference<Preference>("lanSharing")

        fun getLocalIps(): String {
            val ips = runCatching {
                java.net.NetworkInterface.getNetworkInterfaces()?.toList()
                    ?.filter { !it.isLoopback && it.isUp }
                    ?.flatMap { it.inetAddresses.toList() }
                    ?.filter { !it.isLoopbackAddress && it is java.net.Inet4Address }
                    ?.mapNotNull { it.hostAddress }
                    ?.distinct()
            }.getOrNull() ?: emptyList()
            return when {
                ips.isEmpty() -> "192.168.43.1"
                ips.any { it.startsWith("192.168.43.") } -> ips.first { it.startsWith("192.168.43.") }
                else -> ips.joinToString(" / ")
            }
        }

        fun updateLanSharingSummary() {
            val enabled = DataStore.allowAccess
            if (enabled) {
                val localIp = getLocalIps()
                val port = DataStore.mixedPort
                lanSharingPref?.summary = getString(R.string.lan_sharing_enabled_sum, localIp, port)
            } else {
                lanSharingPref?.summary = getString(R.string.lan_sharing_disabled_sum)
            }
        }
        updateLanSharingSummary()

        lanSharingPref?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), LanSharingActivity::class.java))
            true
        }
        // @author 雾晚：所有绑定保持不变，再按入口把对应的设置项平铺到本页。
        // 旧实现按已不存在的分组 key 过滤，导致 外观/Root/DNS/网络 子页几乎是空白。
        val section = arguments?.getString("section")
        applySection(section)
        updateRulesUrlVisibility()
        if (section == "appearance") {
            val colors = ColorPickerPreference(requireContext()).apply {
                key = "minimalThemePicker"
                title = getString(R.string.theme)
                order = -1
                setOnPreferenceChangeListener { _, value ->
                    val selection = value as ColorPickerPreference.Selection
                    selection.customColor?.let { DataStore.customThemeColor = it }
                    DataStore.appTheme = selection.themeId
                    Theme.applyNightTheme()
                    requireActivity().recreate()
                    true
                }
            }
            preferenceScreen.addPreference(colors)
        }
        if (section == "root") {
            // v3.0.15: module status row follows hot updates live — the
            // versionCode comes from RootModuleClient.moduleVersionFlow,
            // which jumps when a no-reboot switch commits.
            val moduleStatus = Preference(requireContext()).apply {
                key = "hotUpdateModuleStatus"
                title = getString(R.string.minimal_root)
                summary = "—"
                order = -1
                isSelectable = false
            }
            preferenceScreen.addPreference(moduleStatus)
            val diagnosticPreferences = mutableListOf<Preference>()
            fun renderDiagnostics(status: io.nekohasekai.sagernet.bg.RootModuleClient.Status) {
                diagnosticPreferences.forEach { preferenceScreen.removePreference(it) }
                diagnosticPreferences.clear()
                val dateFormat = java.text.DateFormat.getDateTimeInstance()
                ModuleDiagnostics.format(status).forEachIndexed { index, line ->
                    val label = when (line.kind) {
                        ModuleDiagnostics.Kind.HEALTH -> R.string.module_diagnostics_health
                        ModuleDiagnostics.Kind.EXIT_REASON -> R.string.module_diagnostics_exit_reason
                        ModuleDiagnostics.Kind.EXIT_CODE -> R.string.module_diagnostics_exit_code
                        ModuleDiagnostics.Kind.EXIT_TIME -> R.string.module_diagnostics_exit_time
                        ModuleDiagnostics.Kind.RETRY_COUNT -> R.string.module_diagnostics_retry_count
                        ModuleDiagnostics.Kind.EVENT -> R.string.module_diagnostics_event
                    }
                    val time = if (line.epochSeconds > 0L) {
                        // Clamp malformed timestamps before converting protocol seconds to milliseconds.
                        dateFormat.format(java.util.Date(line.epochSeconds.coerceAtMost(Long.MAX_VALUE / 1000) * 1000))
                    } else ""
                    val preference = Preference(requireContext()).apply {
                        key = "moduleDiagnostic$index"
                        setTitle(label)
                        summary = listOf(time, line.value).filter { it.isNotEmpty() }.joinToString(" · ")
                        order = index
                        isSelectable = false
                        isPersistent = false
                    }
                    diagnosticPreferences += preference
                    preferenceScreen.addPreference(preference)
                }
            }
            var stateText = "—"
            fun renderModuleStatus() {
                val code = io.nekohasekai.sagernet.bg.RootModuleClient.moduleVersionFlow.value
                moduleStatus.summary = if (code > 0) "$stateText · 模块版本码 $code" else stateText
            }
            lifecycleScope.launch {
                stateText = try {
                    val module = io.nekohasekai.sagernet.bg.RootModuleClient.call("status")
                    renderDiagnostics(module)
                    getString(if (module.connected) R.string.service_state_connected else R.string.service_state_idle)
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                  catch (e: Exception) { io.nekohasekai.sagernet.bg.RootModuleErrors.message(requireContext(), e) }
                renderModuleStatus()
            }
            lifecycleScope.launch {
                io.nekohasekai.sagernet.bg.RootModuleClient.moduleVersionFlow.collect {
                    renderModuleStatus()
                    try {
                        renderDiagnostics(io.nekohasekai.sagernet.bg.RootModuleClient.callFreshStatus())
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                      catch (_: Exception) { /* Keep the last successful diagnostics on status failure. */ }
                }
            }
            addHealthCheckPreferences()
            addModuleActionPreferences()
            // B5（N2）：脱敏诊断包一键导出。目标为系统 Download 目录，
            // 导出前用 design.md §10 原句确认残留风险。
            val exportDiagnostics = Preference(requireContext()).apply {
                key = "exportDiagnostics"
                title = getString(R.string.export_diagnostics)
                order = 100
                isSelectable = true
                isPersistent = false
            }
            exportDiagnostics.setOnPreferenceClickListener {
                MaterialAlertDialogBuilder(requireContext()).apply {
                    setTitle(R.string.export_diagnostics)
                    setMessage(R.string.export_diagnostics_confirm)
                    setNegativeButton(android.R.string.cancel, null)
                    setPositiveButton(R.string.export_diagnostics) { _, _ ->
                        exportDiagnosticsPackage()
                    }
                }.show()
                true
            }
            preferenceScreen.addPreference(exportDiagnostics)
        }
    }

    // B5（N2）：导出脱敏诊断包到系统 Download 目录，成功/失败均以 Toast 反馈。
    private fun exportDiagnosticsPackage() {
        val context = requireContext()
        lifecycleScope.launch {
            val file = io.nekohasekai.sagernet.bg.DiagnosticsExport.destinationFile(
                io.nekohasekai.sagernet.bg.DiagnosticsExport.defaultDownloadDir(),
                java.time.OffsetDateTime.now(),
            )
            try {
                io.nekohasekai.sagernet.bg.RootModuleClient.exportDiagnostics(
                    file.absolutePath,
                    io.nekohasekai.sagernet.bg.DiagnosticsVersions(
                        appVersion = io.nekohasekai.sagernet.BuildConfig.VERSION_NAME,
                        appVersionCode = io.nekohasekai.sagernet.BuildConfig.VERSION_CODE_RAW,
                        previewVersion = io.nekohasekai.sagernet.BuildConfig.PRE_VERSION_NAME,
                        previewVersionCode = io.nekohasekai.sagernet.BuildConfig.PRE_VERSION_CODE_RAW,
                        singBoxVersion = io.nekohasekai.sagernet.BuildConfig.SINGBOX_VERSION,
                    ),
                )
                io.nekohasekai.sagernet.bg.DiagnosticsExport.scanFile(context, file)
                val size = android.text.format.Formatter.formatFileSize(context, file.length())
                Toast.makeText(
                    context,
                    context.getString(R.string.export_diagnostics_success, file.absolutePath, size),
                    Toast.LENGTH_LONG,
                ).show()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = io.nekohasekai.sagernet.bg.RootModuleErrors.message(context, e)
                Toast.makeText(
                    context,
                    context.getString(R.string.export_diagnostics_failed, reason),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /** phase2: node health check policy (module side reads files/health_check.json) + one-tap failover. */
    private fun addHealthCheckPreferences() {
        val ctx = requireContext()
        val modes = arrayOf("notify", "auto", "off")
        val summaries = arrayOf(R.string.health_check_summary_notify, R.string.health_check_summary_auto, R.string.health_check_summary_off)
        fun summaryOf(mode: String) = getString(summaries[modes.indexOf(mode).coerceAtLeast(0)]) + "\n" + getString(R.string.health_check_apply_hint)
        val list = androidx.preference.ListPreference(ctx).apply {
            key = "healthCheckMode"
            setTitle(R.string.health_check_title)
            setIcon(R.drawable.ic_line_heart)
            entries = summaries.map { getString(it).substringBefore('：').substringBefore(':') }.toTypedArray()
            entryValues = modes
            setDefaultValue("notify")
            value = DataStore.healthCheckMode.takeIf { it in modes } ?: "notify"
            summary = summaryOf(value)
            order = 100
            setOnPreferenceChangeListener { pref, newValue ->
                pref.summary = summaryOf(newValue as String)
                needReload()
                true
            }
        }
        preferenceScreen.addPreference(list)
        preferenceScreen.addPreference(Preference(ctx).apply {
            key = "healthCheckFailoverNow"
            setTitle(R.string.home_failover)
            setIcon(R.drawable.ic_line_swap)
            isPersistent = false
            order = 101
            setOnPreferenceClickListener {
                val activity = requireActivity()
                runOnDefaultDispatcher {
                    val error = try {
                        io.nekohasekai.sagernet.bg.RootModuleClient.call("node failover"); null
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { e }
                    onMainDispatcher {
                        if (activity.isFinishing || activity.isDestroyed) return@onMainDispatcher
                        android.widget.Toast.makeText(activity, if (error == null) activity.getString(R.string.home_failover_done)
                            else io.nekohasekai.sagernet.bg.RootModuleErrors.message(activity, error), android.widget.Toast.LENGTH_LONG).show()
                    }
                }
                true
            }
        })
    }

    /** UI 对齐模块：给模块已有但 App 没入口的动作补按钮——重启核心、
     * 回滚到上一版配置；并给恢复的开机自启 XML 开关补即时下发。
     * 全部走 RootModuleClient 既有命令，不新增模块侧能力。 */
    private fun addModuleActionPreferences() {
        val ctx = requireContext()
        suspend fun report(activity: android.app.Activity, error: Exception?, doneText: String) {
            onMainDispatcher {
                if (activity.isFinishing || activity.isDestroyed) return@onMainDispatcher
                if (error == null && doneText.isEmpty()) return@onMainDispatcher
                android.widget.Toast.makeText(activity, if (error == null) doneText
                    else io.nekohasekai.sagernet.bg.RootModuleErrors.message(activity, error), android.widget.Toast.LENGTH_LONG).show()
            }
        }
        // 开机自启：isAutoConnect 由 XML 开关承载，但模块的 boot.json 才是
        // “开机是否拉起核心”的依据。这里不让框架直接落库（listener 返回 false），
        // 而是「下发 → 回读确认 → 落库」：失败/超时/结果未知时保持原值并回滚显示；
        // 确认后由 AutoStartSettings 同时写 DataStore 与 BOOT_COMPLETED 接收器，
        // 两侧永远一致（配置应用时还会按同一值再下发一次，作为自愈路径）。
        findPreference<SwitchPreference>(Key.PERSIST_ACROSS_REBOOT)?.apply {
            val defaultSummary = summary
            val originalPersistent = isPersistent
            isPersistent = false
            try {
                isChecked = DataStore.persistAcrossReboot
            } finally {
                isPersistent = originalPersistent
            }
            setOnPreferenceChangeListener { pref, newValue ->
                val enabled = newValue as Boolean
                val switch = pref as SwitchPreference
                switch.summary = getString(R.string.autostart_sync_pending)
                val activity = requireActivity()
                io.nekohasekai.sagernet.bg.AutoStartSettings.request(enabled) { outcome ->
                    runOnMainDispatcher {
                        if (activity.isFinishing || activity.isDestroyed) return@runOnMainDispatcher
                        when (outcome) {
                            is io.nekohasekai.sagernet.bg.AutoStartSync.Outcome.Confirmed -> {
                                val persistent = switch.isPersistent
                                switch.isPersistent = false
                                try {
                                    switch.isChecked = outcome.enabled
                                } finally {
                                    switch.isPersistent = persistent
                                }
                                switch.summary = defaultSummary
                            }
                            is io.nekohasekai.sagernet.bg.AutoStartSync.Outcome.Failed -> {
                                // 保持旧值：界面、DataStore、模块都不动，只把错误说清楚。
                                val persistent = switch.isPersistent
                                switch.isPersistent = false
                                try {
                                    switch.isChecked = DataStore.persistAcrossReboot
                                } finally {
                                    switch.isPersistent = persistent
                                }
                                switch.summary = defaultSummary
                                android.widget.Toast.makeText(activity,
                                    getString(R.string.autostart_sync_failed,
                                        io.nekohasekai.sagernet.bg.RootModuleErrors.message(activity, outcome.code)),
                                    android.widget.Toast.LENGTH_LONG).show()
                            }
                            io.nekohasekai.sagernet.bg.AutoStartSync.Outcome.Superseded -> {
                                // 更新的切换已接管：不落库、不改界面。
                                switch.summary = defaultSummary
                            }
                        }
                    }
                }
                false
            }
            // 返回页面时核对模块与 App：不一致就显示可见的“待同步”状态，
            // 用户再切一次开关即可重新下发（不静默改写用户数据）。
            val preference = this
            lifecycleScope.launch {
                val result = try {
                    io.nekohasekai.sagernet.bg.AutoStartSettings.check()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (result != null && isAdded) {
                    when (result) {
                        is io.nekohasekai.sagernet.bg.AutoStartSync.Check.Mismatch -> {
                            val moduleText = getString(if (result.moduleValue) R.string.autostart_state_on else R.string.autostart_state_off)
                            preference.summary = getString(R.string.autostart_sync_mismatch, moduleText)
                        }
                        is io.nekohasekai.sagernet.bg.AutoStartSync.Check.Unavailable -> {
                            // 模块的 boot 配置损坏/不可读：本次开机不会自启。开关显示什么都是猜，
                            // 所以只说待处理并给出修复动作（再切一次开关即重写配置）。
                            preference.summary = getString(R.string.autostart_boot_config_broken,
                                io.nekohasekai.sagernet.bg.RootModuleErrors.message(requireContext(), result.code))
                        }
                    }
                }
            }
        }
        // 重启核心：模块 restart/reload 早已实现且在 App 白名单内，此前零调用方。
        preferenceScreen.addPreference(Preference(ctx).apply {
            key = "moduleRestartCore"
            setTitle(R.string.restart_core_title)
            setIcon(R.drawable.ic_baseline_refresh_24)
            isPersistent = false
            order = 103
            setOnPreferenceClickListener {
                MaterialAlertDialogBuilder(ctx).apply {
                    setTitle(R.string.restart_core_title)
                    setMessage(R.string.restart_core_confirm)
                    setNegativeButton(android.R.string.cancel, null)
                    setPositiveButton(R.string.restart_core_title) { _, _ ->
                        val activity = requireActivity()
                        val doneText = getString(R.string.restart_core_done)
                        runOnDefaultDispatcher {
                            val error = try {
                                io.nekohasekai.sagernet.bg.RootModuleClient.call("restart"); null
                            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { e }
                            report(activity, error, doneText)
                        }
                    }
                }.show()
                true
            }
        })
        // 配置回滚：模块保留 previous generation 专门供回滚（核心起不来时的
        // 自救通道），此前 App 命令白名单不含 config rollback、UI 也无入口。
        preferenceScreen.addPreference(Preference(ctx).apply {
            key = "moduleConfigRollback"
            setTitle(R.string.config_rollback_title)
            isPersistent = false
            order = 104
            setOnPreferenceClickListener {
                MaterialAlertDialogBuilder(ctx).apply {
                    setTitle(R.string.config_rollback_title)
                    setMessage(R.string.config_rollback_confirm)
                    setNegativeButton(android.R.string.cancel, null)
                    setPositiveButton(R.string.config_rollback_title) { _, _ ->
                        val activity = requireActivity()
                        val doneText = getString(R.string.config_rollback_done)
                        runOnDefaultDispatcher {
                            val error = try {
                                io.nekohasekai.sagernet.bg.RootModuleClient.call("config rollback"); null
                            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { e }
                            report(activity, error, doneText)
                        }
                    }
                }.show()
                true
            }
        })
    }

    private fun collectLeaves(group: PreferenceGroup, out: MutableList<Preference>) {
        for (index in 0 until group.preferenceCount) {
            val preference = group.getPreference(index)
            if (preference is PreferenceGroup) collectLeaves(preference, out) else out.add(preference)
        }
    }

    /** @author 雾晚：按入口平铺设置项；未归入任何入口的项进入“高级”。 */
    private fun applySection(section: String?) {
        val hidden = mutableSetOf<String>()
        if (!CoreRuntime.available) {
            // 测速依赖内嵌核心，瘦管理器无法使用
            hidden += setOf(Key.SPEED_TEST_MODE, Key.SPEED_TEST_TIMEOUT_MS, Key.SIMPLE_DOWNLOAD_URL)
        }
        val leaves = mutableListOf<Preference>().also { collectLeaves(preferenceScreen, it) }
        val byKey = leaves.filter { it.key != null }.associateBy { it.key }
        leaves.forEach { it.parent?.removePreference(it) }
        preferenceScreen.removeAll()
        var order = 0
        fun place(group: PreferenceGroup, keys: List<String>) {
            for (key in keys) {
                if (key in hidden) continue
                val preference = byKey[key] ?: continue
                preference.order = order++
                preference.isVisible = true
                group.addPreference(preference)
            }
        }
        val flat = section?.let { sectionKeys[it] }
        if (flat != null) {
            place(preferenceScreen, flat)
            return
        }
        // 「高级设置」：下沉项按分组展示；未归入任何入口的项进入「其他」，保证不丢设置。默认值均不变。
        val assigned = (sectionKeys.values.flatten() + advancedGroups.flatMap { it.second }).toSet()
        val others = leaves.mapNotNull { it.key }.filter { it !in assigned }
        for ((title, keys) in advancedGroups + (R.string.minimal_advanced_others to others)) {
            if (keys.none { it in byKey && it !in hidden }) continue
            val category = PreferenceCategory(requireContext()).apply {
                setTitle(title)
                this.order = order++
                isIconSpaceReserved = false
            }
            preferenceScreen.addPreference(category)
            place(category, keys)
        }
    }

    override fun onResume() {
        super.onResume()
        updateAutoStopPermission()

        if (::globalCustomConfig.isInitialized) {
            globalCustomConfig.notifyChanged()
        }
        val lanSharingPref = findPreference<Preference>("lanSharing")
        if (lanSharingPref != null) {
            val enabled = DataStore.allowAccess
            if (enabled) {
                val ips = runCatching {
                    java.net.NetworkInterface.getNetworkInterfaces()?.toList()
                        ?.filter { !it.isLoopback && it.isUp }
                        ?.flatMap { it.inetAddresses.toList() }
                        ?.filter { !it.isLoopbackAddress && it is java.net.Inet4Address }
                        ?.mapNotNull { it.hostAddress }
                        ?.distinct()
                }.getOrNull() ?: emptyList()
                val localIp = when {
                    ips.isEmpty() -> "192.168.43.1"
                    ips.any { it.startsWith("192.168.43.") } -> ips.first { it.startsWith("192.168.43.") }
                    else -> ips.joinToString(" / ")
                }
                lanSharingPref.summary = getString(R.string.lan_sharing_enabled_sum, localIp, DataStore.mixedPort)
            } else {
                lanSharingPref.summary = getString(R.string.lan_sharing_disabled_sum)
            }
        }
    }

    private fun clearAppCache() {
        try {
            val cacheDir = SagerNet.application.cacheDir
            clearDirFiles(cacheDir, skipFiles = setOf("neko.log"))
            
            val parentDir = cacheDir.parentFile
            val relativeCache = File(parentDir, "cache")
            if (relativeCache.exists() && relativeCache.isDirectory) {
                clearDirFiles(relativeCache)
            }
            
            Toast.makeText(requireContext(), R.string.clear_cache_success, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), getString(R.string.clear_cache_failed, e.message), Toast.LENGTH_SHORT).show()
            e.printStackTrace()
        }
    }

    private fun clearDirFiles(dir: File, skipFiles: Set<String> = emptySet()): Boolean {
        if (dir.isDirectory) {
            val children = dir.list() ?: return true
            
            for (child in children) {
                val childFile = File(dir, child)
                
                if (child == "neko.log") {
                    try {
                        childFile.writeText("")
                        continue
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                
                if (child in skipFiles) {
                    continue
                }
                
                if (childFile.isDirectory) {
                    clearDirFiles(childFile, skipFiles)
                } else {
                    childFile.delete()
                }
            }
            
            return true
        }
        return false
    }

    companion object {
        /** @author 雾晚：设置首页各入口对应的设置项（顺序即显示顺序）。 */
        private val sectionKeys: Map<String, List<String>> = mapOf(
            "appearance" to listOf(
                "nightTheme", "appLanguage", "profileCardStyle", "profileTrafficStatistics",
                "showLandingIp", "alwaysShowAddress", "show_subscription_info_card",
                "hapticFeedback",
            ),
            // 设置首页「连接与核心」：首屏项，其余下沉到「高级设置」
            "root" to listOf("connectionTestURL", "ipv6Mode", "isAutoConnect"),
        )

        /** 「高级设置」分组（标题 to 键）。仅重新分组，键与默认值均不变。 */
        private val advancedGroups: List<Pair<Int, List<String>>> = listOf(
            R.string.minimal_advanced_inbound to listOf(
                "bypassLan", "bypassLanInCore", "strictRoute",
                "mtu", "performancePriorityMode",
                "disableMixedInbound", "mixedPort", "mixedAuthConfig", "lanSharing",
            ),
            R.string.minimal_dns to listOf(
                "remoteDns", "domain_strategy_for_remote", "directDns", "domain_strategy_for_direct",
                "domain_strategy_for_server", "enableDnsRouting", "enableFakeDns", "dnsHosts",
            ),
            R.string.minimal_network to listOf(
                "trafficSniffing", "resolveDestination", "dualNetworkAcceleration",
                "concurrentDial", "enableTLSFragment", "fragmentLength", "fragmentInterval",
                "speedTestMode", "speedTestTimeoutMs", "simpleDownloadURL",
            ),
            R.string.minimal_advanced_security to listOf(
                "defaultSubscriptionUserAgent", "appTLSVersion", "globalAllowInsecure",
                "allowInsecureOnRequest",
            ),
            R.string.minimal_advanced_ui to listOf("confirmProfileDelete"),
        )
    }

    class ListSummaryProvider(
        private val maxLines: Int,
    ) : Preference.SummaryProvider<EditTextPreference> {

        override fun provideSummary(preference: EditTextPreference): CharSequence {
            val lines = preference.text.orEmpty()
                .lineSequence()
                .filter { it.isNotBlank() }
                .toList()
            if (lines.isEmpty()) {
                return preference.context.getString(androidx.preference.R.string.not_set)
            }
            return if (lines.size > maxLines) {
                lines.take(maxLines).joinToString("\n", postfix = "\n...")
            } else {
                lines.joinToString("\n")
            }
        }

    }

}
