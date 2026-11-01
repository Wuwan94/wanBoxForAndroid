// @author 雾晚
package io.nekohasekai.sagernet.core

import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.IOException

/**
 * @author 雾晚 (B 路线)
 *
 * 完整版 APK 里，`geoip.db` / `geosite.db` 由 `libcore.InitCore` → `extractAssets()` 从
 * APK assets 解压落地。瘦管理器没有 gojni，这条路径不会执行，而模块侧对它们是**硬校验**：
 * 规则里出现 `geoip:`/`geosite:` 时要求 `assets/geoip.db` / `assets/geosite.db` 存在，
 * 否则 `config_resource_missing` 直接拒绝启动。
 *
 * 因此这里用纯 Kotlin + Tukaani XZ 复刻同一套落地规则（目标路径、版本文件、数值版本比较、
 * `Custom` 版本不回退覆盖），保证瘦管理器下规则库照常可用。
 * 完整版（[CoreRuntime.available]）直接返回，仍由 Go 侧负责，避免双写。
 *
 * 注：yacd 网页面板已整体移除，这里不再有 dashboard 资产的落地逻辑。
 */
object CoreAssets {

    private const val SING_BOX_ASSET_DIR = "sing-box/"
    private val GEO_DATABASES = listOf("geoip", "geosite")
    /** 版本文件里写成它表示用户自备数据，任何情况下都不覆盖。 */
    private const val VERSION_CUSTOM = "Custom"

    /** 幂等；仅在瘦构建下工作，失败不抛出（下次启动会重试）。 */
    @Synchronized
    fun ensure(app: SagerNet) {
        if (CoreRuntime.available) return
        runCatching { syncGeoDatabases(app) }
            .onFailure { Logs.w("geo asset bootstrap failed", it) }
    }

    private fun syncGeoDatabases(app: SagerNet) {
        val base = app.externalAssets
        base.mkdirs()
        for (name in GEO_DATABASES) {
            val target = File(base, "$name.db")
            val versionFile = File(base, "$name.version.txt")
            val assetVersion = readAssetText(app, SING_BOX_ASSET_DIR + "$name.version.txt") ?: continue
            if (!shouldExtract(target, versionFile, assetVersion)) continue

            val temp = File(base, "$name.db.tmp")
            app.assets.open(SING_BOX_ASSET_DIR + "$name.db.xz").use { raw ->
                XZInputStream(raw).use { xz ->
                    temp.outputStream().use { out -> xz.copyTo(out) }
                }
            }
            if (temp.length() <= 0L) throw IOException("${name}_decompress_empty")
            if (!temp.renameTo(target)) {
                temp.delete()
                throw IOException("${name}_rename_failed")
            }
            versionFile.writeText(assetVersion)
            Logs.i("$name.db deployed (asset version ${assetVersion.trim()})")
        }
    }

    /** 与 Go 侧 `extractAssetName` 语义一致：缺文件、缺版本、资产版本更新才解压。 */
    private fun shouldExtract(target: File, versionFile: File, assetVersion: String): Boolean {
        if (!target.exists()) return true
        val local = runCatching { versionFile.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: return true
        if (local == VERSION_CUSTOM) return false
        val asset = assetVersion.trim()
        val assetNumber = asset.toULongOrNull()
        val localNumber = local.toULongOrNull()
        return if (assetNumber != null && localNumber != null) {
            assetNumber > localNumber
        } else {
            asset != local
        }
    }

    private fun readAssetText(app: SagerNet, name: String): String? =
        runCatching { app.assets.open(name).bufferedReader().use { it.readText() } }.getOrNull()
}
