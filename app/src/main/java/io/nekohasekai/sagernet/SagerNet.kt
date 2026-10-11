// @author 雾晚
package io.nekohasekai.sagernet

import android.annotation.SuppressLint
import android.app.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.PowerManager
import android.os.StrictMode
import android.os.UserManager
import androidx.core.content.getSystemService
import go.Seq
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.isOss
import io.nekohasekai.sagernet.ktx.isPreview
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.utils.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.DEBUG_PROPERTY_NAME
import kotlinx.coroutines.DEBUG_PROPERTY_VALUE_ON
import libcore.Libcore
import moe.matsuri.nb4a.NativeInterface
import moe.matsuri.nb4a.net.LocalResolverImpl
import moe.matsuri.nb4a.utils.JavaUtil
import moe.matsuri.nb4a.utils.cleanWebview
import java.io.File
import androidx.work.Configuration as WorkConfiguration

class SagerNet : Application(),
    WorkConfiguration.Provider {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)

        application = this
    }

    val applicationScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    private val appRoutingListener = object : io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener {
        override fun onPreferenceDataStoreChanged(store: androidx.preference.PreferenceDataStore, key: String) {
            io.nekohasekai.sagernet.route.AppRoutingChanges.onSaved(key, ::reloadService)
        }
    }

    private val nativeInterface = NativeInterface()

    val externalAssets: File by lazy { getExternalFilesDir(null) ?: filesDir }
    val process: String = JavaUtil.getProcessName()
    private val isMainProcess = process == BuildConfig.APPLICATION_ID
    val isBgProcess = process.endsWith(":bg")

    override fun onCreate() {
        super.onCreate()

        Thread.setDefaultUncaughtExceptionHandler(CrashHandler)
        if (isMainProcess || isBgProcess) {
            try {
                io.nekohasekai.sagernet.database.preference.PublicDatabase.instance.openHelper.writableDatabase
                io.nekohasekai.sagernet.database.SagerDatabase.instance.openHelper.writableDatabase
            } catch (error: Exception) {
                databaseFailure = error
                Logs.e(error)
                return
            }
        }

        if (isMainProcess) {
            // 主题颜色选择器已移除，强制迁移到浅灰主题
            try {
                val theme = io.nekohasekai.sagernet.database.DataStore.appTheme
                if (theme != io.nekohasekai.sagernet.utils.Theme.LIGHT_GRAY) {
                    io.nekohasekai.sagernet.database.DataStore.appTheme = io.nekohasekai.sagernet.utils.Theme.LIGHT_GRAY
                }
            } catch (_: Exception) { }
        }

        if (isMainProcess || isBgProcess) {
            externalAssets.mkdirs()
            // 官方内核在 PlatformLogWriter != nil 时会为每个 box 强制创建 CacheFile，
            // 无显式 path 时共用工作目录（no_backup）下的 cache.db。主进程批量测速的
            // 并发 TestInstance 曾共享该文件导致 bbolt freelist 损坏（"page already freed"
            // panic 在异步 batch goroutine 中无法 recover → SIGABRT 闪退），且损坏文件
            // 能正常打开、提交时才崩，官方 Open 阶段的校验发现不了。
            // 现测试实例已从 Go 侧彻底不创建 cache（libcore NewTestSingBoxInstance），
            // 这里在进程启动、尚无 box 打开时清扫存量损坏文件及历史残留，实现老用户自愈。
            runCatching {
                File(noBackupFilesDir, "cache.db").delete()
                noBackupFilesDir.listFiles { file -> file.name.startsWith("urltest_") }
                    ?.forEach { it.delete() }
            }
            Seq.setContext(this)
            // Write performance mode flag so Go's InitCore can read it and apply correct GC settings.
            // Using a flag file avoids needing a new JNI binding for SetMemoryProfile.
            runCatching {
                File(noBackupFilesDir, "perf_mode").apply {
                    if (DataStore.performancePriorityMode) writeText("1") else delete()
                }
            }
            Libcore.initCore(
                process,
                cacheDir.absolutePath + "/",
                filesDir.absolutePath + "/",
                externalAssets.absolutePath + "/",
                DataStore.logBufSize,
                DataStore.logLevel > 0,
                nativeInterface, nativeInterface, LocalResolverImpl
            )

            if (isBgProcess) runOnDefaultDispatcher { clearLegacyNotifications() }

            // fix multi process issue in Android 9+
            JavaUtil.handleWebviewDir(this)

            runOnDefaultDispatcher {
                PackageCache.register()
                cleanWebview()
            }
        }

        if (isMainProcess) {
            if (!Theme.isSupportedTheme(DataStore.appTheme)) {
                DataStore.appTheme = Theme.LIGHT_GRAY
            }
            Theme.apply(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && DataStore.useSystemTheme) {
                com.google.android.material.color.DynamicColors.applyToActivitiesIfAvailable(this)
            }
            Theme.applyNightTheme()
            AppLocale.apply()
            AppIconManager.init(this)
            DataStore.migrateSubscriptionUserAgents()
            // @author 雾晚: only persisted app-policy changes request a passive apply.
            // Register after startup/migrations; opening a screen cannot restart the core.
            DataStore.configurationStore.registerChangeListener(appRoutingListener)
            runOnDefaultDispatcher { clearLegacyNotifications() }
        }

        if (BuildConfig.DEBUG) {
            System.setProperty(DEBUG_PROPERTY_NAME, DEBUG_PROPERTY_VALUE_ON)
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedSqlLiteObjects()
                    .detectLeakedClosableObjects()
                    .detectLeakedRegistrationObjects()
                    .penaltyLog()
                    .build()
            )
        }
    }

    override fun getWorkManagerConfiguration(): WorkConfiguration {
        return WorkConfiguration.Builder()
            .setDefaultProcessName("${BuildConfig.APPLICATION_ID}:bg")
            .build()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        if (databaseFailure != null) return
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN && isMainProcess) {
            cleanWebview()
        }
    }

    @SuppressLint("InlinedApi")
    companion object {

        var databaseFailure: Throwable? = null
        lateinit var application: SagerNet

        val isTv by lazy {
            uiMode.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        }

        val configureIntent: (Context) -> PendingIntent by lazy {
            {
                PendingIntent.getActivity(
                    it,
                    0,
                    Intent(
                        application, MainActivity::class.java
                    ).setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
                )
            }
        }
        val activity by lazy { application.getSystemService<ActivityManager>()!! }
        val clipboard by lazy { application.getSystemService<ClipboardManager>()!! }
        val connectivity by lazy { application.getSystemService<ConnectivityManager>()!! }
        val notification by lazy { application.getSystemService<NotificationManager>()!! }
        val user by lazy { application.getSystemService<UserManager>()!! }
        val uiMode by lazy { application.getSystemService<UiModeManager>()!! }
        val power by lazy { application.getSystemService<PowerManager>()!! }

        fun getClipboardText(): String {
            return clipboard.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.text?.toString() ?: ""
        }

        fun trySetPrimaryClip(clip: String) = try {
            clipboard.setPrimaryClip(ClipData.newPlainText(null, clip))
            true
        } catch (e: RuntimeException) {
            Logs.w(e)
            false
        }

        /** The module owns the core; the manager no longer posts tray notifications. @author 雾晚 */
        fun clearLegacyNotifications() {
            notification.cancelAll()
            for (channel in listOf("service-vpn", "service-proxy", "service-subscription", "connection-test")) {
                notification.deleteNotificationChannel(channel)
            }
        }

        // @author 雾晚: short management operations; no Android service owns the core.
        private fun reportModuleError(error: Exception) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(application, io.nekohasekai.sagernet.bg.RootModuleErrors.message(application, error),
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }
        private val configurationReload by lazy {
            io.nekohasekai.sagernet.bg.CoalescedReload(application.applicationScope,
                apply = { io.nekohasekai.sagernet.bg.RootModuleClient.startOrReload(startIfStopped = false) },
                onError = ::reportModuleError)
        }
        private val profileSelection by lazy {
            io.nekohasekai.sagernet.bg.ProfileSelection(application.applicationScope, save = { id ->
                // @author 雾晚：运行时先 selectNode 确认返回 connected&&profileId==目标
                // 之后才持久化 selectedProxy 与发通知；失败保持旧选择/连接并提示
                // 停止时仅保存选择不启动；取消重抛
                val previous = DataStore.selectedProxy
                val isRunning = try {
                    io.nekohasekai.sagernet.bg.RootModuleClient.call("status").connected
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
                if (!isRunning) {
                    // 停止时仅保存选择，不启动
                    if (previous != id) {
                        DataStore.selectedProxy = id
                        io.nekohasekai.sagernet.database.ProfileManager.postUpdate(previous, noTraffic = true)
                    }
                    io.nekohasekai.sagernet.widget.OwnBoxWidgetProvider.updateWidgets(application)
                    return@ProfileSelection
                }
                // 运行时：通过控制器串行确认
                val ok = try {
                    // 直接调用 selectNode 并确认（控制器已处理并发）
                    val s = io.nekohasekai.sagernet.bg.RootModuleClient.selectNode(id)
                    s.connected && s.profileId == id
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val code = io.nekohasekai.sagernet.bg.RootModuleClient.safeError(e)
                    Logs.e("Node select failed ($code), keeping old connection")
                    false
                }
                if (ok) {
                    // 确认后才持久化与通知
                    if (previous != id) {
                        DataStore.selectedProxy = id
                        io.nekohasekai.sagernet.database.ProfileManager.postUpdate(previous, noTraffic = true)
                    }
                    io.nekohasekai.sagernet.widget.OwnBoxWidgetProvider.updateWidgets(application)
                } else {
                    // @author 雾晚：热切换失败，保持旧连接与旧选择，不做无意义的全量重载
                    // （全量重载会读取 DataStore.selectedProxy 的旧值，无法切换到目标节点）
                    // 仅记录错误并通过 onError 通知用户，由用户决定是否重试或手动重载
                    Logs.e("Node switch not confirmed for target $id, keeping old connection (selectedProxy=$previous)")
                }
            }, onError = ::reportModuleError)
        }
        fun selectProfile(id: Long) { profileSelection.request(id) }

        /** @author 雾晚：应用级串行命令控制器 */
        val commandController by lazy {
            io.nekohasekai.sagernet.bg.ServiceCommandController(application.applicationScope)
        }

        private fun moduleCommand(work: suspend () -> Unit) {
            application.applicationScope.launch {
                try { work() }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (error: Exception) { reportModuleError(error) }
            }
        }
        fun toggleService(profileId: Long = -1L) = moduleCommand {
            val client = io.nekohasekai.sagernet.bg.RootModuleClient
            val status = client.call("status")
            if (status.state.canStop && (profileId == -1L || profileId == status.profileId)) client.stop()
            else {
                if (profileId >= 0L) DataStore.selectedProxy = profileId
                client.startOrReload()
            }
            DataStore.serviceState = client.call("status").state
            io.nekohasekai.sagernet.widget.OwnBoxWidgetHelper.updateAllWidgets(application)
        }
        fun enableService() = moduleCommand {
            val client = io.nekohasekai.sagernet.bg.RootModuleClient
            if (!client.call("status").state.canStop) client.startOrReload()
        }
        fun startService() = moduleCommand { io.nekohasekai.sagernet.bg.RootModuleClient.startOrReload() }
        fun reloadService() { configurationReload.request() }
        // @author 雾晚: restart from newly generated App rules/settings, not the old module snapshot.
        fun restartService() { configurationReload.request() }
        fun stopService() = moduleCommand { io.nekohasekai.sagernet.bg.RootModuleClient.stop() }

        fun updatePerformancePriorityMode(enabled: Boolean) {
            DataStore.performancePriorityMode = enabled
            runCatching {
                val file = File(application.noBackupFilesDir, "perf_mode")
                if (enabled) file.writeText("1") else file.delete()
            }
            application.sendBroadcast(
                Intent(Action.SWITCH_PERFORMANCE_MODE).setPackage(application.packageName)
            )
        }

        var underlyingNetwork: Network? = null

        var appVersionNameForDisplay = {
            var n = BuildConfig.VERSION_NAME
            if (isPreview) {
                if (BuildConfig.PRE_VERSION_NAME.isNotBlank() && BuildConfig.PRE_VERSION_NAME != BuildConfig.VERSION_NAME) {
                    n += " " + BuildConfig.PRE_VERSION_NAME
                }
            } else if (!isOss) {
                n += " ${BuildConfig.FLAVOR}"
            }
            if (BuildConfig.DEBUG) {
                n += " DEBUG"
            }
            n
        }()
    }

}
