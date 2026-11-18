// @author 雾晚
package io.nekohasekai.sagernet.utils

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.listenForPackageChanges
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.matsuri.nb4a.plugin.Plugins
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object PackageCache {

    lateinit var installedPackages: Map<String, PackageInfo>
    lateinit var installedPluginPackages: Map<String, PackageInfo>
    lateinit var installedApps: Map<String, ApplicationInfo>
    lateinit var packageMap: Map<String, Int>
    @Volatile
    var uidMap: Map<Int, Set<String>> = emptyMap()
        private set
    val loaded = Mutex(true)
    var registerd = AtomicBoolean(false)

    // called from init (suspend)
    fun register() {
        if (registerd.getAndSet(true)) return
        reload()
        app.listenForPackageChanges(false) {
            reload()
            labelMap.clear()
        }
        loaded.unlock()
    }

    @SuppressLint("InlinedApi")
    fun reload() {
        val installed = if (SagerNet.application.isBgProcess) {
            installedPackages = emptyMap()
            installedPluginPackages = emptyMap()
            app.packageManager.getInstalledApplications(0)
        } else {
            val rawPackageInfo = app.packageManager.getInstalledPackages(
                PackageManager.MATCH_UNINSTALLED_PACKAGES
                        or PackageManager.GET_PERMISSIONS
                        or PackageManager.GET_PROVIDERS
                        or PackageManager.GET_META_DATA
            )

            installedPackages = rawPackageInfo.filter {
                when (it.packageName) {
                    "android" -> true
                    else -> it.requestedPermissions?.contains(Manifest.permission.INTERNET) == true
                }
            }.associateBy { it.packageName }

            installedPluginPackages = rawPackageInfo.filter {
                Plugins.isExe(it)
            }.associateBy { it.packageName }

            app.packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        }
        installedApps = installed.associateBy { it.packageName }
        packageMap = installed.associate { it.packageName to it.uid }
        // Publish a complete snapshot; live core lookups never see a cleared/partial map.
        uidMap = AppUidPackages.snapshot(installed.map { it.packageName to it.uid })
    }

    operator fun get(uid: Int) = AppUidPackages.names(uidMap, uid)
    operator fun get(packageName: String) = packageMap[packageName]

    // @author 雾晚: secondary users' packages are invisible to PackageManager, so
    // per-app rules would miss their traffic. Resolve all users' UIDs via root pm.
    private const val MULTI_USER_TTL_MS = 5 * 60 * 1000L
    // P2-4：跨用户查询失败（su 5 秒超时 / pm 输出解析失败）时不得把空表当成
    // “确实没有其他用户应用”缓存满 5 分钟；30 秒后再试，并把失败码交给
    // PerUserApps 区分“查询失败”与“名单真为空”。
    private const val MULTI_USER_FAILURE_RETRY_MS = 30 * 1000L
    @Volatile private var multiUserMap: Map<String, List<Int>> = emptyMap()
    /** 上次跨用户 UID 查询的失败码（null = 成功或尚未执行）。 */
    @Volatile var multiUserResolveError: String? = null
        private set
    // B6（N5）：按用户归属的跨用户 UID 映射（userId -> package -> UIDs）。
    // 同一包名在不同用户下是不同条目，不得跨用户合并 UID。
    @Volatile private var multiUserMapByUser: Map<Int, Map<String, List<Int>>> = emptyMap()
    @Volatile private var multiUserMapAt = 0L
    private val multiUserMutex = Mutex()

    /** Refresh the cross-user UID map; cheap (TTL) and safe to call often. */
    suspend fun refreshMultiUserUids() {
        val window = if (multiUserResolveError != null) MULTI_USER_FAILURE_RETRY_MS else MULTI_USER_TTL_MS
        if (System.currentTimeMillis() - multiUserMapAt < window) return
        multiUserMutex.withLock {
            val lockedWindow =
                if (multiUserResolveError != null) MULTI_USER_FAILURE_RETRY_MS else MULTI_USER_TTL_MS
            if (System.currentTimeMillis() - multiUserMapAt < lockedWindow) return
            val result = runCatching { queryMultiUserUids() }
            val fresh = result.getOrNull()
            if (fresh != null) {
                multiUserMapByUser = fresh
                multiUserMap = fresh.values.fold(mutableMapOf<String, MutableList<Int>>()) { acc, perUser ->
                    perUser.forEach { (pkg, uids) -> acc.getOrPut(pkg) { mutableListOf() }.addAll(uids) }
                    acc
                }
                multiUserResolveError = null
            } else {
                // 失败时保留上一份成功快照（可能为空），只标记失败码；
                // PerUserApps 据此退回旧行为，不拦截服务启动。
                val error = result.exceptionOrNull()
                multiUserResolveError = error?.message?.takeIf { it.isNotBlank() } ?: "pm_failed"
                io.nekohasekai.sagernet.ktx.Logs.w(
                    "PackageCache: multi-user UID lookup failed ($multiUserResolveError); retry in 30s"
                )
            }
            multiUserMapAt = System.currentTimeMillis()
        }
    }

    /** All UIDs for a package across users (current user first). */
    fun uidsForPackage(packageName: String): List<Int> {
        val out = linkedSetOf<Int>()
        packageMap[packageName]?.let { out.add(it) }
        multiUserMap[packageName]?.let { out.addAll(it) }
        return out.toList()
    }

    /** B6（N5）：指定用户下指定包的 UID（分身/工作资料各自分开）。 */
    fun uidsForUserPackage(userId: Int, packageName: String): List<Int> {
        if (userId == 0) {
            val out = linkedSetOf<Int>()
            packageMap[packageName]?.let { out.add(it) }
            multiUserMapByUser[0]?.get(packageName)?.let { out.addAll(it) }
            return out.toList()
        }
        return multiUserMapByUser[userId]?.get(packageName)?.toList().orEmpty()
    }

    /** B6（N5）：该包名已安装的所有用户（含当前用户）。 */
    fun usersForPackage(packageName: String): List<Int> {
        val users = linkedSetOf<Int>()
        if (packageMap.containsKey(packageName)) users.add(0)
        multiUserMapByUser.forEach { (userId, pkgs) ->
            if (pkgs.containsKey(packageName)) users.add(userId)
        }
        return users.sorted()
    }

    /** B6（N5）：其他用户的应用条目（userId, package, uid），供分应用列表按用户展示。 */
    fun multiUserEntries(): List<Triple<Int, String, Int>> {
        val out = mutableListOf<Triple<Int, String, Int>>()
        multiUserMapByUser.forEach { (userId, pkgs) ->
            pkgs.forEach { (pkg, uids) ->
                uids.forEach { uid -> out.add(Triple(userId, pkg, uid)) }
            }
        }
        return out.sortedWith(compareBy({ it.second }, { it.first }))
    }

    private fun queryMultiUserUids(): Map<Int, Map<String, List<Int>>> {
        // P2：su 可能卡在未响应的 Root 授权弹窗上。旧实现先 readText() 再
        // waitFor()，读管道会无限等进程结束，从而永久占住 multiUserMutex 与
        // 配置生成。这里用读线程 + 5 秒有界等待，超时强制结束并返回空表。
        fun su(cmd: String): String {
            val p = ProcessBuilder("su", "-c", cmd).start()
            val buffer = ByteArrayOutputStream()
            val reader = Thread {
                try {
                    p.inputStream.copyTo(buffer)
                } catch (_: IOException) {
                    // 进程被强制结束时管道会关闭；按失败路径统一处理。
                }
            }.apply {
                isDaemon = true
                start()
            }
            try {
                if (!p.waitFor(5, TimeUnit.SECONDS)) {
                    p.destroyForcibly()
                    throw IOException("pm_timeout")
                }
                reader.join(1_000)
                if (p.exitValue() != 0) throw IOException("pm_failed")
                return buffer.toString(Charsets.UTF_8.name())
            } finally {
                if (p.isAlive) p.destroyForcibly()
                reader.join(500)
            }
        }
        val myUser = android.os.Process.myUid() / 100000
        val users = su("pm list users").lineSequence()
            .mapNotNull { Regex("""UserInfo\{(\d+):""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            .filter { it != myUser }
            .toList()
        if (users.isEmpty()) return emptyMap()
        val map = mutableMapOf<Int, MutableMap<String, MutableList<Int>>>()
        for (u in users) {
            // package:com.example uid:1010123
            su("pm list packages --user $u -U").lineSequence().forEach { line ->
                val m = Regex("""^package:([^\s]+)\s+uid:(\d+)$""").find(line.trim()) ?: return@forEach
                val uid = m.groupValues[2].toIntOrNull() ?: return@forEach
                if (uid >= 1000) map.getOrPut(u) { mutableMapOf() }
                    .getOrPut(m.groupValues[1]) { mutableListOf() }.add(uid)
            }
        }
        // @author 雾晚: identifiers/counts only; never log package names.
        io.nekohasekai.sagernet.ktx.Logs.d("PackageCache: multi-user uid map users=${users.size} packages=${map.values.sumOf { it.size }}")
        return map
    }

    fun awaitLoadSync() {
        if (::packageMap.isInitialized) {
            return
        }
        if (!registerd.get()) {
            register()
            return
        }
        runBlocking {
            loaded.withLock {
                // just await
            }
        }
    }

    private val labelMap = mutableMapOf<String, String>()
    fun loadLabel(packageName: String): String {
        var label = labelMap[packageName]
        if (label != null) return label
        val info = installedApps[packageName] ?: return packageName
        label = info.loadLabel(app.packageManager).toString()
        labelMap[packageName] = label
        return label
    }

}
