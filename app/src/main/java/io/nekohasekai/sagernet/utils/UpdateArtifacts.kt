// @author 雾晚
package io.nekohasekai.sagernet.utils

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 只读本仓库 GitHub Release 的资产选择与 SHA256SUMS 校验。
 * 只做解析与校验，不负责下载/安装，保持纯函数便于 JVM 单测。 @author 雾晚
 */
object UpdateArtifacts {
    const val SUMS_NAME = "SHA256SUMS"
    const val MODULE_NAME = "wanbox-root-module-arm64-v8a.zip"

    data class ReleaseAsset(val name: String, val downloadUrl: String, val size: Long)

    data class Bundle(val apk: ReleaseAsset, val module: ReleaseAsset, val sums: ReleaseAsset)

    fun parseAssets(releaseJson: String): List<ReleaseAsset> {
        val assets = JSONObject(releaseJson).optJSONArray("assets") ?: return emptyList()
        return (0 until assets.length()).mapNotNull { index ->
            val item = assets.optJSONObject(index) ?: return@mapNotNull null
            val name = item.optString("name").trim()
            val url = item.optString("browser_download_url").trim()
            if (name.isEmpty() || url.isEmpty()) null
            else ReleaseAsset(name, url, item.optLong("size", 0L))
        }
    }

    /**
     * 管理器 APK、Root 模块与校验清单必须来自同一 Release，缺一不可。
     * 命名规则与发布流水线一致：APK 含 arm64-v8a，模块按普通版完整文件名精确匹配，不能误选 with-manager。
     */
    fun selectBundle(assets: List<ReleaseAsset>): Bundle? {
        val sums = assets.firstOrNull { it.name == SUMS_NAME } ?: return null
        val apk = assets.firstOrNull {
            it.name.endsWith(".apk", ignoreCase = true) && it.name.contains("arm64-v8a")
        } ?: return null
        val module = assets.firstOrNull {
            it.name == MODULE_NAME
        } ?: return null
        return Bundle(apk, module, sums)
    }

    /** 解析 `SHA256SUMS`：`<64 位十六进制>  <文件名>`，允许 `*` 前缀。 */
    fun parseSums(text: String): Map<String, String> = text.lineSequence()
        .mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val hash = parts[0].lowercase()
            val name = parts[1].removePrefix("*").trim()
            if (name.isEmpty() || !hash.matches(Regex("[0-9a-f]{64}"))) null else name to hash
        }
        .toMap()

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 文件名必须在清单里且摘要一致；缺项按失败处理，不做「无清单放行」。 */
    fun verify(file: File, sums: Map<String, String>): Boolean {
        val expected = sums[file.name] ?: return false
        return sha256(file) == expected
    }
}
