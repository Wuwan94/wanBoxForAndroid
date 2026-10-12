// @author 雾晚
package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.addCallback
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.asStateFlow
import androidx.annotation.IdRes
import androidx.core.graphics.ColorUtils
import androidx.preference.PreferenceDataStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.aidl.TrafficDataBatch
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import libcore.Libcore
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.databinding.LayoutMainBinding
import io.nekohasekai.sagernet.utils.Theme
import io.nekohasekai.sagernet.utils.LandingIpManager
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.PluginEntry
import io.nekohasekai.sagernet.group.GroupInterfaceAdapter
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.deduplicateProxies
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ktx.alert
import io.nekohasekai.sagernet.ktx.deduplicateProxies
import io.nekohasekai.sagernet.ktx.isPlay
import io.nekohasekai.sagernet.ktx.deduplicateProxies
import io.nekohasekai.sagernet.ktx.isPreview
import io.nekohasekai.sagernet.ktx.deduplicateProxies
import io.nekohasekai.sagernet.ktx.launchCustomTab
import io.nekohasekai.sagernet.ktx.deduplicateProxies
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.deduplicateProxies
import io.nekohasekai.sagernet.ktx.parseProxies
import io.nekohasekai.sagernet.ktx.deduplicateProxies
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.deduplicateProxies
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.ui.MessageStore
import io.nekohasekai.sagernet.ktx.deduplicateProxies
import io.nekohasekai.sagernet.ktx.Logs
import moe.matsuri.nb4a.utils.Util

class MainActivity : ThemedActivity(),
    SagerConnection.Callback,
    OnPreferenceDataStoreChangeListener,
    NavigationView.OnNavigationItemSelectedListener {

    lateinit var binding: LayoutMainBinding
    lateinit var navigation: NavigationView
    private var currentMainFragment: ToolbarFragment? = null
    private var installerDataJob: Job? = null
    private var installerDataDialog: android.app.Dialog? = null

    // preview.18 UI 改造：仪表盘支持
    var serviceState: BaseService.State = BaseService.State.Idle
    // @author 雾晚：服务真实启动时间（毫秒），用于仪表盘运行时长显示
        private set
    // @author 雾晚：AIDL 回调的测速数据改为 StateFlow，UI 只读观察
    // Stopped/Idle 时清空样本
    private val _speedStatsFlow = kotlinx.coroutines.flow.MutableStateFlow<SpeedDisplayData?>(null)
    val speedStatsFlow: kotlinx.coroutines.flow.StateFlow<SpeedDisplayData?> = _speedStatsFlow.asStateFlow()
    // 保留旧字段兼容（已废弃，逐步迁移）
    var lastSpeedStats: SpeedDisplayData?
        get() = _speedStatsFlow.value
        set(value) { _speedStatsFlow.value = value }

    fun navigateToNodes() {
        // 跳转到节点页（底部导航的节点 tab）
        binding.bottomNav.selectedItemId = R.id.nav_nodes
    }

    private val serviceModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Action.SERVICE_MODE_CHANGED) connection.rebindIfServiceChanged(this@MainActivity)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (SagerNet.databaseFailure == null) DataStore.appTheme = Theme.LIGHT_GRAY
        super.onCreate(savedInstanceState)
        if (SagerNet.databaseFailure != null) {
            startActivity(Intent(this, DatabaseRecoveryActivity::class.java))
            finish()
            return
        }
        MessageStore.setCurrentActivity(this)
        val animateInitialControls = savedInstanceState == null

        binding = LayoutMainBinding.inflate(layoutInflater)
        // preview.19: 移除悬浮小飞机，连接操作统一在仪表盘
        val isNight = Theme.usingNightMode()
        val fabBgColor = when {
            Theme.isBlackTheme() -> Color.BLACK
            Theme.isWhiteTheme() -> Color.parseColor("#212121")
            Theme.isLightGrayTheme() -> Color.parseColor("#1F2937")
            else -> {
                val bg = getColorAttr(R.attr.fabColorBackground)
                if (isNight && ColorUtils.calculateLuminance(bg) > 0.75) {
                    val primary = getColorAttr(R.attr.colorPrimary)
                    if (ColorUtils.calculateLuminance(primary) > 0.75) {
                        Color.parseColor("#2C2C2E")
                    } else {
                        primary
                    }
                } else {
                    bg
                }
            }
        }
        if (themeResId !in intArrayOf(
                R.style.Theme_SagerNet_Black
            )
        ) {
            // @author 雾晚：统一使用 navView，黑色主题通过样式语义保留
            navigation = binding.navView
        } else {
            navigation = binding.navView
        }
        navigation.setNavigationItemSelectedListener(this)
        binding.drawerLayout.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_LOCKED_CLOSED)

        // preview.18 UI 改造：底部导航
        binding.bottomNav.setOnItemSelectedListener { item ->
            supportFragmentManager.popBackStackImmediate(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
            displayFragmentWithId(item.itemId)
        }

        if (savedInstanceState == null) {
            if (TileNavigation.destinationFor(intent?.action) == null) {
                displayFragmentWithId(R.id.nav_dashboard)
                binding.bottomNav.selectedItemId = R.id.nav_dashboard
            }
        } else {
            currentMainFragment =
                supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment
        }
        onBackPressedDispatcher.addCallback {
            val fragment = supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment
            if (fragment?.onBackPressed() == true) return@addCallback
            if (supportFragmentManager.backStackEntryCount > 0) {
                supportFragmentManager.popBackStackImmediate()
                currentMainFragment = supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment
            } else if (fragment is DashboardFragment) {
                moveTaskToBack(true)
            } else {
                binding.bottomNav.selectedItemId = R.id.nav_dashboard
            }
        }


        setContentView(binding.root)
        handleTileIntent(intent)
        currentMainFragment =
            supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment
                ?: currentMainFragment
        if (!animateInitialControls) {
            syncMainControls(showWhenConnected = false, animate = false)
        }
        changeState(
            BaseService.State.Idle,
            animate = false,
            animateControls = animateInitialControls,
        )
        connection.connect(this, this)
        // @author 雾晚: fallback is written in :bg and does not fire this process's DataStore listener.
        val modeFilter = IntentFilter(Action.SERVICE_MODE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceModeReceiver, modeFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(serviceModeReceiver, modeFilter)
        }
        DataStore.configurationStore.registerChangeListener(this)
        GroupManager.userInterface = GroupInterfaceAdapter(this)

        if (intent?.action == Intent.ACTION_VIEW) {
            onNewIntent(intent)
        }

        refreshNavMenu(DataStore.enableClashAPI)

        val isPreRelease = isPreview && (BuildConfig.PRE_VERSION_NAME.contains("preview", true) || BuildConfig.PRE_VERSION_NAME.contains("beta", true) || BuildConfig.PRE_VERSION_NAME.contains("alpha", true))
        if (isPreRelease && DataStore.previewHintDismissedVersion != BuildConfig.PRE_VERSION_NAME) {
            MaterialAlertDialogBuilder(this)
                .setTitle(BuildConfig.PRE_VERSION_NAME)
                .setMessage(R.string.preview_version_hint)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.preview_hint_dont_show_again) { _, _ ->
                    DataStore.previewHintDismissedVersion = BuildConfig.PRE_VERSION_NAME
                }
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (SagerNet.databaseFailure != null) return
        connection.rebindIfServiceChanged(this)
        MessageStore.setCurrentActivity(this)

        if (DataStore.hideFromRecentApps) {
            applyHideFromRecentApps(DataStore.hideFromRecentApps)
        }

        checkClipboardOnResume()
        refreshNavMenu(DataStore.enableClashAPI)
    }

    private var lastPromptedClipboard: String = ""

    private fun checkClipboardOnResume() {
        try {
            val text = SagerNet.getClipboardText().trim()
            if (text.isBlank() || text == lastPromptedClipboard || text.length < 8) return
            val looksLikeProxy = text.startsWith("vless://", ignoreCase = true) ||
                    text.startsWith("vmess://", ignoreCase = true) ||
                    text.startsWith("ss://", ignoreCase = true) ||
                    text.startsWith("ssr://", ignoreCase = true) ||
                    text.startsWith("trojan://", ignoreCase = true) ||
                    text.startsWith("hysteria://", ignoreCase = true) ||
                    text.startsWith("hysteria2://", ignoreCase = true) ||
                    text.startsWith("hy2://", ignoreCase = true) ||
                    text.startsWith("tuic://", ignoreCase = true) ||
                    text.startsWith("sn://", ignoreCase = true) ||
                    text.startsWith("clash://", ignoreCase = true)

            if (!looksLikeProxy) return
            lastPromptedClipboard = text

            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.action_import)
                .setMessage(R.string.import_clipboard_prompt)
                .setPositiveButton(R.string.action_import) { _, _ ->
                    runOnDefaultDispatcher {
                        try {
                            val rawProxies = io.nekohasekai.sagernet.group.RawUpdater.parseRaw(text)
                            val currentGroup = DataStore.currentGroup()
                            val targetId = DataStore.selectedGroupForImport()
                            val targetGroup = io.nekohasekai.sagernet.database.SagerDatabase.groupDao.getById(targetId)
                            val shouldDeduplicate = (targetGroup?.subscription?.deduplication == true) || (currentGroup.subscription?.deduplication == true)
                            val proxies = if (shouldDeduplicate) rawProxies?.deduplicateProxies() else rawProxies
                            if (!proxies.isNullOrEmpty()) {
                                proxies.forEach { profile ->
                                    ProfileManager.createProfile(targetId, profile)
                                }
                                onMainDispatcher {
                                    displayFragmentWithId(R.id.nav_configuration)
                                    snackbar(resources.getQuantityString(R.plurals.added, proxies.size, proxies.size)).show()
                                }
                            }
                        } catch (e: io.nekohasekai.sagernet.ktx.SubscriptionFoundException) {
                            if (e.link.startsWith("sn://")) {
                                importSubscription(android.net.Uri.parse(e.link))
                            } else {
                                onMainDispatcher {
                                    val subscriptionLink = android.net.Uri.parse(e.link).getQueryParameter("url") ?: e.link
                                    startActivity(Intent(this@MainActivity, GroupSettingsActivity::class.java).apply {
                                        putExtra(GroupSettingsActivity.EXTRA_FROM_CLIPBOARD, true)
                                        putExtra(GroupSettingsActivity.EXTRA_GROUP_SUBSCRIPTION_LINK, subscriptionLink)
                                    })
                                }
                            }
                        } catch (e: Exception) {
                            io.nekohasekai.sagernet.ktx.Logs.w(e)
                            onMainDispatcher {
                                snackbar(e.readableMessage).show()
                            }
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } catch (e: Exception) {
            io.nekohasekai.sagernet.ktx.Logs.w(e)
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        if (SagerNet.databaseFailure != null) return
        val restoredFragment =
            supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment
        if (restoredFragment != null && restoredFragment !== currentMainFragment) {
            currentMainFragment = restoredFragment
            syncMainControls(
                fragment = restoredFragment,
                showWhenConnected = DataStore.serviceState == BaseService.State.Connected,
                animate = false,
            )
        }
    }

    fun applyHideFromRecentApps(hide: Boolean) {
        try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val tasks = activityManager.appTasks
            if (tasks.isNotEmpty()) {
                val task = tasks[0]
                task.setExcludeFromRecents(hide)
            }
        } catch (e: Exception) {
            Logs.w("Failed to set excludeFromRecents: ${e.message}")
        }
    }

    fun refreshNavMenu(clashApi: Boolean) {
        navigation.menu.findItem(R.id.nav_dashboard)?.isVisible = clashApi
    }

    private fun handleTileIntent(incoming: Intent?): Boolean {
        when (TileNavigation.destinationFor(incoming?.action)) {
            TileNavigation.Destination.CONFIGURATION -> displayFragmentWithId(R.id.nav_configuration)
            TileNavigation.Destination.CUSTOM_ICON -> {
                displayFragment(ToolsFragment.forCustomIcon())
                setCheckedItem(R.id.nav_tools)
            }
            null -> return false
        }
        // A configuration change must not replay a tile navigation request.
        setIntent(Intent(this, MainActivity::class.java).apply { action = Intent.ACTION_MAIN })
        return true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (handleTileIntent(intent)) return

        val uri = intent.data ?: return

        runOnDefaultDispatcher {
            if (uri.scheme == "sn" && uri.host == "subscription" || uri.scheme == "clash") {
                importSubscription(uri)
            } else {
                importProfile(uri)
            }
        }
    }

    fun urlTest(service: ISagerNetService? = connection.service): Int {
        if (!DataStore.serviceState.connected || service == null || connection.service !== service) {
            error("not started")
        }
        return service.urlTest()
    }

    suspend fun importSubscription(uri: Uri) {
        val group: ProxyGroup

        val url = uri.getQueryParameter("url")
        if (!url.isNullOrBlank()) {
            group = ProxyGroup(type = GroupType.SUBSCRIPTION)
            val subscription = SubscriptionBean()
            group.subscription = subscription

            // cleartext format
            subscription.link = url
            group.name = uri.getQueryParameter("name")
        } else {
            val data = uri.encodedQuery.takeIf { !it.isNullOrBlank() } ?: return
            try {
                group = KryoConverters.deserialize(
                    ProxyGroup().apply { export = true }, Util.zlibDecompress(Util.b64Decode(data))
                ).apply {
                    export = false
                }
            } catch (e: Exception) {
                onMainDispatcher {
                    alert(e.readableMessage).show()
                }
                return
            }
        }

        val name = group.name.takeIf { !it.isNullOrBlank() } ?: group.subscription?.link
        ?: group.subscription?.token
        if (name.isNullOrBlank()) return

        if (group.name.isNullOrBlank()) {
            val candidate = group.subscription?.link?.takeIf { it.isNotBlank() }?.let {
                io.nekohasekai.sagernet.group.RawUpdater.extractAirportName(it)
            }
            group.name = candidate ?: ("Subscription #" + System.currentTimeMillis())
        }

        onMainDispatcher {

            displayFragmentWithId(R.id.nav_group)

            MaterialAlertDialogBuilder(this@MainActivity).setTitle(R.string.subscription_import)
                .setMessage(getString(R.string.subscription_import_message, name))
                .setPositiveButton(R.string.yes) { _, _ ->
                    runOnDefaultDispatcher {
                        finishImportSubscription(group)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()

        }

    }

    private suspend fun finishImportSubscription(subscription: ProxyGroup) {
        GroupManager.createGroup(subscription)
        GroupUpdater.startUpdate(subscription, true)
    }

    suspend fun importProfile(uri: Uri) {
        val profile = try {
            parseProxies(uri.toString()).getOrNull(0) ?: error(getString(R.string.no_proxies_found))
        } catch (e: Exception) {
            onMainDispatcher {
                alert(e.readableMessage).show()
            }
            return
        }

        onMainDispatcher {
            MaterialAlertDialogBuilder(this@MainActivity).setTitle(R.string.profile_import)
                .setMessage(getString(R.string.profile_import_message, profile.displayName()))
                .setPositiveButton(R.string.yes) { _, _ ->
                    runOnDefaultDispatcher {
                        finishImportProfile(profile)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

    }

    private suspend fun finishImportProfile(profile: AbstractBean) {
        val targetId = DataStore.selectedGroupForImport()

        ProfileManager.createProfile(targetId, profile)

        onMainDispatcher {
            displayFragmentWithId(R.id.nav_configuration)

            snackbar(resources.getQuantityString(R.plurals.added, 1, 1)).show()
        }
    }

    override fun missingPlugin(profileName: String, pluginName: String) {
        val pluginEntity = PluginEntry.find(pluginName)

        // unknown exe or neko plugin
        if (pluginEntity == null) {
            snackbar(getString(R.string.plugin_unknown, pluginName)).show()
            return
        }

        // official exe

        MaterialAlertDialogBuilder(this).setTitle(R.string.missing_plugin)
            .setMessage(
                getString(
                    R.string.profile_requiring_plugin, profileName, pluginEntity.displayName
                )
            )
            .setPositiveButton(R.string.action_download) { _, _ ->
                showDownloadDialog(pluginEntity)
            }
            .setNeutralButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.action_learn_more) { _, _ ->
                launchCustomTab("https://t.me/OwnBoxs")
            }
            .show()
    }

    private fun showDownloadDialog(pluginEntry: PluginEntry) {
        var index = 0
        var playIndex = -1
        var fdroidIndex = -1

        val items = mutableListOf<String>()
        if (pluginEntry.downloadSource.playStore) {
            items.add(getString(R.string.install_from_play_store))
            playIndex = index++
        }
        if (pluginEntry.downloadSource.fdroid) {
            items.add(getString(R.string.install_from_fdroid))
            fdroidIndex = index++
        }

        items.add(getString(R.string.download))
        val downloadIndex = index

        MaterialAlertDialogBuilder(this).setTitle(pluginEntry.name)
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    playIndex -> launchCustomTab("https://play.google.com/store/apps/details?id=${pluginEntry.packageName}")
                    fdroidIndex -> launchCustomTab("https://f-droid.org/packages/${pluginEntry.packageName}/")
                    downloadIndex -> launchCustomTab(pluginEntry.downloadSource.downloadLink)
                }
            }
            .show()
    }

    fun isCurrentFragment(@IdRes id: Int): Boolean {
        val current = currentMainFragment
            ?: supportFragmentManager.findFragmentById(R.id.fragment_holder)
        return when (id) {
            R.id.nav_configuration -> current is NodesFragment
            R.id.nav_group -> current is SubscriptionsFragment
            R.id.nav_route -> current is RouteFragment
            R.id.nav_settings -> current is SettingsFragment
            R.id.nav_dashboard -> current is DashboardFragment
            R.id.nav_nodes -> current is NodesFragment
            R.id.nav_subscriptions -> current is SubscriptionsFragment
            R.id.nav_tools -> current is ToolsFragment
            R.id.nav_logcat -> current is LogcatFragment
            R.id.nav_docs -> current is DocsFragment
            R.id.nav_about -> current is AboutFragment
            else -> false
        }
    }

    fun setCheckedItem(@IdRes id: Int) {
        val menu = navigation.menu
        fun uncheckAll(m: Menu) {
            for (i in 0 until m.size()) {
                val item = m.getItem(i)
                if (item.hasSubMenu()) {
                    item.subMenu?.let { uncheckAll(it) }
                }
                item.isChecked = false
            }
        }
        uncheckAll(menu)
        menu.findItem(id)?.isChecked = true
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        if (isCurrentFragment(item.itemId)) {
            binding.drawerLayout.closeDrawers()
            return true
        }
        return displayFragmentWithId(item.itemId)
    }


    @SuppressLint("CommitTransaction")
    fun displayFragment(fragment: ToolbarFragment) {
        currentMainFragment = fragment
        val transaction = supportFragmentManager.beginTransaction().replace(R.id.fragment_holder, fragment)
        if (fragment.arguments?.getBoolean("referenceDetail") == true) transaction.addToBackStack("detail")
        transaction.commitAllowingStateLoss()
        binding.drawerLayout.closeDrawers()
        syncMainControls(fragment, showWhenConnected = false, animate = true)
    }

    fun openDetail(fragment: ToolbarFragment) {
        fragment.arguments = (fragment.arguments ?: Bundle()).apply { putBoolean("referenceDetail", true) }
        displayFragment(fragment)
    }

    private fun syncMainControls(
        fragment: Any? = currentMainFragment
            ?: supportFragmentManager.findFragmentById(R.id.fragment_holder),
        showWhenConnected: Boolean,
        animate: Boolean,
    ) {
        val showControls = fragment is ConfigurationFragment || DataStore.showBottomBar
        // preview.19: 悬浮按钮已移除，StatsBar 已删除
        // @author 雾晚：诊断入口已移至 Dashboard 诊断卡
    }

    private fun refreshConfigurationProfileState() {
        val fragment = currentMainFragment
            ?: supportFragmentManager.findFragmentById(R.id.fragment_holder)
        (fragment as? ConfigurationFragment)?.refreshProfileState()
    }

    fun driveBottomBar(scrollDy: Int) {
    }

    fun displayFragmentWithId(@IdRes id: Int): Boolean {
        when (id) {
            // preview.18 UI 改造：底部导航四栏
            R.id.nav_dashboard -> {
                displayFragment(DashboardFragment())
            }
            R.id.nav_nodes -> {
                displayFragment(NodesFragment())
            }
            R.id.nav_subscriptions -> {
                displayFragment(SubscriptionsFragment())
            }
            R.id.nav_settings -> displayFragment(SettingsFragment())

            R.id.nav_configuration -> {
                displayFragment(NodesFragment())
            }

            R.id.nav_group -> displayFragment(SubscriptionsFragment())
            R.id.nav_route -> displayFragment(RouteFragment())
            R.id.nav_tools -> displayFragment(ToolsFragment())
            R.id.nav_logcat -> displayFragment(LogcatFragment())
            R.id.nav_faq -> {
                launchCustomTab("https://t.me/OwnBoxs")
                return false
            }

            R.id.nav_docs -> displayFragment(DocsFragment())

            R.id.nav_about -> displayFragment(AboutFragment())

            else -> return false
        }
        setCheckedItem(id)
        return true
    }

    /**
     * @author 雾晚：模块状态确认回调（公有，供 Dashboard 调用）。
     */
    fun onModuleStateConfirmed(status: io.nekohasekai.sagernet.bg.RootModuleClient.Status) {
        changeState(status.state)
    }

    private fun changeState(
        state: BaseService.State,
        msg: String? = null,
        animate: Boolean = false,
        animateControls: Boolean = animate,
    ) {
        // @author 雾晚：Stopped/Idle 时清空测速样本
        if (state == BaseService.State.Stopped || state == BaseService.State.Idle) {
            _speedStatsFlow.value = null
        }
        // @author 雾晚：统一更新 serviceState，确保 onServiceConnected/
        // onServiceDisconnected 也能正确同步，避免 Dashboard 读取到过期状态。
        val oldState = serviceState
        serviceState = state
        DataStore.serviceState = state
        // @author 雾晚：跟踪服务真实启动时间，用于仪表盘运行时长
        if (state == BaseService.State.Connected && oldState != BaseService.State.Connected) {
            // @author 雾晚：真实运行时长由模块端 Telemetry 提供，不再用 Activity 内存时间
        } else if (state == BaseService.State.Idle || state == BaseService.State.Stopped) {
            // @author 雾晚：断开时模块端清空 Telemetry
        }
        refreshConfigurationProfileState()
        io.nekohasekai.sagernet.widget.OwnBoxWidgetProvider.updateWidgets(this)

        // preview.19: 服务状态由仪表盘处理
        syncMainControls(
            showWhenConnected = state == BaseService.State.Connected,
            animate = animateControls,
        )
        // @author 雾晚：通知 DashboardFragment 更新状态（生命周期感知）
        (currentMainFragment as? DashboardFragment)?.onServiceStateChanged(state)
        if (msg != null) snackbar(getString(R.string.vpn_error, msg)).show()
    }

    override fun snackbarInternal(text: CharSequence): Snackbar {
        return Snackbar.make(binding.coordinator, text, Snackbar.LENGTH_LONG).apply {
            // preview.19: 锚定到底部导航
            anchorView = binding.bottomNav
            // TODO
        }
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        changeState(state, msg, true)
    }

    val connection = SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND, true)
    override fun onServiceConnected(service: ISagerNetService) = changeState(
        try {
            BaseService.State.values()[service.state]
        } catch (_: RemoteException) {
            BaseService.State.Idle
        }
    )

    override fun onServiceDisconnected() = changeState(BaseService.State.Idle)
    override fun onBinderDied() {
        connection.disconnect(this)
        connection.connect(this, this)
    }

    // @author 雾晚: connection is controlled by the independent module, without VPN authorization.

    // may NOT called when app is in background
    // ONLY do UI update here, write DB in bg process
    override fun cbSpeedUpdate(stats: SpeedDisplayData) {
        // @author 雾晚：Stopped/Idle 时清空样本，避免显示旧数据
        _speedStatsFlow.value = stats
    }

    override suspend fun cbTrafficUpdate(data: TrafficDataBatch) {
        ProfileManager.postUpdate(data.items)
    }

    override fun cbSelectorUpdate(id: Long) {
        val old = DataStore.selectedProxy
        // @author 雾晚: module acknowledgement updates the running node, never
        // overwrites a newer user selection with an older sampled profile.
        if (DataStore.serviceMode != Key.MODE_ROOT) DataStore.selectedProxy = id
        DataStore.currentProfile = id
        refreshConfigurationProfileState()
        runOnDefaultDispatcher {
            ProfileManager.postUpdate(old, true)
            ProfileManager.postUpdate(id, true)
        }
        // @author 雾晚：落地 IP 诊断已移至 Dashboard 诊断卡
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        runOnMainDispatcher {
            if (isDestroyed || isFinishing) return@runOnMainDispatcher
            when (key) {
                Key.SERVICE_MODE -> {
                    connection.rebindIfServiceChanged(this@MainActivity)
                }
                // @author 雾晚：StatsBar 已删除，连接测试配置由 Dashboard 诊断卡处理
                Key.PROFILE_ID -> {
                    refreshConfigurationProfileState()
                    LandingIpManager.clearCache()
                    if (DataStore.serviceState.connected && DataStore.showLandingIp) {
                    }
                }
                Key.SHOW_BOTTOM_BAR -> {
                    syncMainControls(
                        showWhenConnected = DataStore.showBottomBar,
                        animate = true,
                    )
                    when (val fragment = currentMainFragment
                        ?: supportFragmentManager.findFragmentById(R.id.fragment_holder)
                    ) {
                        is GroupFragment -> fragment.updateBottomPadding()
                        is RouteFragment -> fragment.updateBottomPadding()
                    }
                }
            }
        }
    }

    override fun onStart() {
        if (SagerNet.databaseFailure != null) { super.onStart(); return }
        connection.updateConnectionId(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
        connection.connect(this, this)
        super.onStart()
        if (installerDataJob?.isActive != true) installerDataJob = lifecycleScope.launch {
            try {
                if (io.nekohasekai.sagernet.bg.RootModuleDataUpdate.applyInstallerSelection() && !isFinishing)
                    recreate()
            } catch (error: CancellationException) { throw error }
              catch (error: Exception) {
                // Stage/type/code are safe diagnostics; exception text may contain credentials.
                val stage = (error as? io.nekohasekai.sagernet.bg.InstallerDataCommit.Failure)?.stage?.name ?: "SELECTION"
                val types = generateSequence(error as Throwable) { it.cause }.take(4).joinToString("/") { it.javaClass.simpleName }
                Logs.e("InstallerData stage=$stage type=$types code=${io.nekohasekai.sagernet.bg.RootModuleClient.safeError(error)}")
                if (!isFinishing && !isDestroyed && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                    installerDataDialog?.dismiss()
                    installerDataDialog = alert(io.nekohasekai.sagernet.bg.RootModuleErrors.message(this@MainActivity, error)).also { it.show() }
                }
            }
        }
    }

    override fun onStop() {
        installerDataDialog?.dismiss()
        installerDataDialog = null
        if (SagerNet.databaseFailure == null) {
            connection.updateConnectionId(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND)
            connection.disconnect(this) // Module owns the core; no App polling while UI is hidden.
        }
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (SagerNet.databaseFailure != null) return
        unregisterReceiver(serviceModeReceiver)
        GroupManager.userInterface = null
        DataStore.configurationStore.unregisterChangeListener(this)
        connection.disconnect(this)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (SagerNet.databaseFailure != null) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (super.onKeyDown(keyCode, event)) return true
                binding.drawerLayout.open()
                navigation.requestFocus()
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (binding.drawerLayout.isOpen) {
                    binding.drawerLayout.close()
                    return true
                }
            }
        }

        if (super.onKeyDown(keyCode, event)) return true
        if (binding.drawerLayout.isOpen) return false

        val fragment =
            supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment
        return fragment != null && fragment.onKeyDown(keyCode, event)
    }

}
