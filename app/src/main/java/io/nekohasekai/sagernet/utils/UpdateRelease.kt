// @author 雾晚
package io.nekohasekai.sagernet.utils

import org.json.JSONArray
import org.json.JSONObject

/**
 * 更新渠道与版本身份的纯逻辑：不访问网络、不依赖 Android 框架，便于 JVM 单测。
 *
 * - 机器版本身份只取自校验后的 `tag_name`；`name` 是展示标题，永不参与比较。
 * - 排序只按严格语义版本，不按中文标题、发布时间或 GitHub API 的数组顺序。
 * - 正式渠道只选正式版，预览渠道只选预览版；预览渠道不隐式迁移到更新的正式版。
 * - 资产只取自被选中的那一个 Release，绝不跨 Release 拼接。
 * @author 雾晚
 */
object UpdateRelease {

    enum class Channel { FORMAL, PREVIEW }

    enum class Reason {
        /** 找到该渠道的 Release，版本可比较。 */
        OK,

        /** 列表为空，或被 draft / 无效 tag / 渠道不匹配全部过滤掉。 */
        NO_RELEASE,

        /** 本地版本名不符合规则，不能安全地判断是否升级。 */
        INVALID_LOCAL_VERSION,
    }

    /** GitHub /releases 单页上限；分页循环有 MAX_PAGES 硬边界。 */
    const val PAGE_SIZE = 100
    const val MAX_PAGES = 5

    /**
     * 严格语义版本。预览序号 1..4 与 `tools/version_code.py` 的版本码规则一致；
     * 数字用 Long 保存，避免宽松比较把超大数字截断或吞掉。
     */
    data class Version(
        val major: Long,
        val minor: Long,
        val patch: Long,
        val preview: Int?,
    ) : Comparable<Version> {
        override fun compareTo(other: Version): Int {
            var result = major.compareTo(other.major)
            if (result != 0) return result
            result = minor.compareTo(other.minor)
            if (result != 0) return result
            result = patch.compareTo(other.patch)
            if (result != 0) return result
            return when {
                preview == null && other.preview == null -> 0
                preview == null -> 1
                other.preview == null -> -1
                else -> preview!!.compareTo(other.preview!!)
            }
        }

        val tag: String
            get() = "$major.$minor.$patch" + (preview?.let { "-preview.$it" } ?: "")
    }

    /**
     * 只接受 `v?X.Y.Z` 或 `v?X.Y.Z-preview.N`；其余一律视为无效 tag。
     * 预览序号范围是 tools/version_code.py 版本规则的 App 侧复刻，由
     * `version_code.py --check-app-tag` 守卫：改范围必须先改规则单源。
     */
    private val TAG_REGEX =
        Regex("^v?([0-9]{1,9})\\.([0-9]{1,9})\\.([0-9]{1,9})(?:-preview\\.([1-4]))?$")

    fun parseVersion(tag: String): Version? {
        val match = TAG_REGEX.matchEntire(tag.trim()) ?: return null
        return Version(
            match.groupValues[1].toLong(),
            match.groupValues[2].toLong(),
            match.groupValues[3].toLong(),
            match.groupValues[4].takeIf { it.isNotEmpty() }?.toInt(),
        )
    }

    data class Record(
        val tagName: String,
        /** 仅用于展示，例如「wanBox v3.0.13-preview.1 — 稳定性预览」。 */
        val displayName: String,
        val htmlUrl: String,
        val draft: Boolean,
        val prerelease: Boolean,
        val assets: List<UpdateArtifacts.ReleaseAsset>,
    )
    // 刻意不保存 published_at：它不是版本身份，也不参与候选排序。

    /** 解析 /releases 的 JSONArray，或 /releases/latest 的单个 JSONObject。 */
    fun parsePage(body: String): List<Record> {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return emptyList()
        val array = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            trimmed.startsWith("{") -> JSONArray().put(JSONObject(trimmed))
            else -> return emptyList()
        }
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            Record(
                tagName = item.optString("tag_name").trim(),
                displayName = item.optString("name").trim(),
                htmlUrl = item.optString("html_url").trim(),
                draft = item.optBoolean("draft", false),
                prerelease = item.optBoolean("prerelease", false),
                assets = UpdateArtifacts.parseAssets(item.toString()),
            )
        }
    }

    /** 有界分页：只有满页且未达上限时才请求下一页。 */
    fun nextPage(page: Int, received: Int, maxPages: Int = MAX_PAGES): Int? =
        if (page < 1 || received < 0 || page >= maxPages || received < PAGE_SIZE) null else page + 1

    data class Selection(
        val reason: Reason,
        val record: Record? = null,
        val version: Version? = null,
        /** 严格高于本地版本。 */
        val newer: Boolean = false,
        /** 只来自 record 自身；为 null 表示资产不完整，必须引导到发布页。 */
        val bundle: UpdateArtifacts.Bundle? = null,
    )

    /**
     * 按渠道选出唯一的候选 Release。
     *
     * 同一 tag 带不同标题视为同一版本：优先取资产完整的那一个，仍只使用该
     * Release 自己的资产；不会被当成两个版本重复提示，也不会跨 Release 补齐。
     */
    fun select(releases: List<Record>, channel: Channel, localTag: String): Selection {
        val current = parseVersion(localTag)
            ?: return Selection(Reason.INVALID_LOCAL_VERSION)
        val eligible = releases.filter { record ->
            if (record.draft || record.htmlUrl.isBlank()) return@filter false
            if (channel == Channel.FORMAL && record.prerelease) return@filter false
            // 先要求 tag 可解析：畸形 tag 不能因为 `?.preview == null` 被误当成正式版。
            val parsed = parseVersion(record.tagName) ?: return@filter false
            when (channel) {
                Channel.FORMAL -> parsed.preview == null
                Channel.PREVIEW -> parsed.preview != null
            }
        }
        if (eligible.isEmpty()) return Selection(Reason.NO_RELEASE)

        val deduplicated = eligible.groupBy { it.tagName }.map { (_, group) ->
            group.firstOrNull { UpdateArtifacts.selectBundle(it.assets) != null }
                ?: group.minByOrNull { it.htmlUrl }!!
        }
        val best = deduplicated.maxWith(
            compareBy<Record> { parseVersion(it.tagName)!! }.thenBy { it.tagName }
        )
        val bestVersion = parseVersion(best.tagName)!!
        return Selection(
            reason = Reason.OK,
            record = best,
            version = bestVersion,
            newer = bestVersion > current,
            bundle = UpdateArtifacts.selectBundle(best.assets),
        )
    }
}
