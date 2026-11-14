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
        if (key == Key.SMART_AUTO_STOP_RULES || key == Key.SMART_AUTO_STOP_ENABLED) {
            updateAutoStopPermission()
            viewLifecycleOwner.lifecycleScope.launch {
                try { io.nekohasekai.sagernet.bg.RootModuleClient.startOrReload(startIfStopped = false) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) {
                    val activity = activity
                    if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                        activity.alert(io.nekohasekai.sagernet.bg.RootModuleErrors.message(activity, e)).tryToShow()
                    }
                }
            }
        }
        if (key == Key.PROFILE_CARD_STYLE) {
            runOnMainDispatcher {
                listView?.adapter?.notifyDataSetChanged()
            }
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
        findPreference<EditTextPreference>(Key.SMART_AUTO_STOP_RULES)?.setOnPreferenceChangeListener { _, value ->
            val result = io.nekohasekai.sagernet.bg.SmartAutoStopRuleParser.parse(value as String)
            if (result.isFailure) {
                val message = if (result.exceptionOrNull()?.message == "too_many_rules") R.string.smart_auto_stop_too_many else R.string.smart_auto_stop_invalid
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
            }
            result.isSuccess
        }

        findPreference<EditTextPreference>(Key.SMART_AUTO_STOP_RULES)?.setOnBindEditTextListener {
            it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            it.setSingleLine(false)
            it.minLines = 4
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
        if (section == "root") addHealthCheckPreferences()
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
            // 设置首页「连接与核心」：只留首屏需要的两项，其余下沉到「高级设置」
            "root" to listOf("connectionTestURL", "ipv6Mode"),
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
