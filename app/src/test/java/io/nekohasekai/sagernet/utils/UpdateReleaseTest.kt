// @author 雾晚
package io.nekohasekai.sagernet.utils

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** 更新渠道与版本身份的真实行为测试：展示标题、数组顺序与发布时间都不得影响判定。 */
class UpdateReleaseTest {

    private val apk = "wanBoxForAndroid-3.0.13-preview.2-arm64-v8a-release.apk"
    private val module = UpdateArtifacts.MODULE_NAME
    private val sums = UpdateArtifacts.SUMS_NAME
    private val defaultAssets = listOf(apk, module, sums)

    private fun assetsJson(names: List<String>): JSONArray {
        val array = JSONArray()
        names.forEach { name ->
            array.put(
                JSONObject()
                    .put("name", name)
                    .put("browser_download_url", "https://example.invalid/$name")
                    .put("size", 1024)
            )
        }
        return array
    }

    private fun releaseJson(
        tag: String,
        name: String = "wanBox $tag — 稳定性预览",
        draft: Boolean = false,
        prerelease: Boolean = tag.contains("preview"),
        assetNames: List<String> = defaultAssets,
    ) = JSONObject()
        .put("tag_name", tag)
        .put("name", name)
        .put("html_url", "https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/$tag")
        .put("draft", draft)
        .put("prerelease", prerelease)
        .put("published_at", "2026-11-08T11:31:39Z")
        .put("assets", assetsJson(assetNames))

    private fun page(vararg releases: JSONObject): List<UpdateRelease.Record> {
        val array = JSONArray()
        releases.forEach { array.put(it) }
        return UpdateRelease.parsePage(array.toString())
    }

    private fun record(
        tag: String,
        name: String = "wanBox $tag — 稳定性预览",
        draft: Boolean = false,
        prerelease: Boolean = tag.contains("preview"),
        htmlUrl: String = "https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/$tag",
        assetNames: List<String> = defaultAssets,
    ) = UpdateRelease.Record(
        tagName = tag,
        displayName = name,
        htmlUrl = htmlUrl,
        draft = draft,
        prerelease = prerelease,
        assets = assetNames.map { UpdateArtifacts.ReleaseAsset(it, "https://example.invalid/$it", 1024) },
    )

    private fun select(vararg records: UpdateRelease.Record) =
        UpdateRelease.select(records.toList(), UpdateRelease.Channel.PREVIEW, "3.0.13-preview.1")

    /**
     * 旧实现的等价复现（仅用于证明缺陷，不用于生产）：按连字符分段、逐段取数字，
     * 非数字段按字符串比较。中文标题里多出的数字会被当成更高版本。
     */
    private fun legacyComparator(a: String, b: String): Int {
        val numberRegex = Regex("\\d+")
        fun numbers(segment: String) = numberRegex.findAll(segment).mapNotNull { it.value.toLongOrNull() }.toList()
        val segmentsA = a.split("-")
        val segmentsB = b.split("-")
        for (i in 0 until maxOf(segmentsA.size, segmentsB.size)) {
            val segmentA = segmentsA.getOrNull(i).orEmpty()
            val segmentB = segmentsB.getOrNull(i).orEmpty()
            val numbersA = numbers(segmentA)
            val numbersB = numbers(segmentB)
            if (numbersA.isEmpty() && numbersB.isEmpty()) {
                val compared = segmentA.compareTo(segmentB)
                if (compared != 0) return compared
                continue
            }
            for (j in 0 until maxOf(numbersA.size, numbersB.size)) {
                val numberA = numbersA.getOrNull(j) ?: return -1
                val numberB = numbersB.getOrNull(j) ?: return 1
                if (numberA != numberB) return if (numberA < numberB) -1 else 1
            }
        }
        return 0
    }

    @Test
    fun parseVersionAcceptsOnlyStrictTags() {
        assertEquals(
            UpdateRelease.Version(3L, 0L, 11L, null),
            UpdateRelease.parseVersion("v3.0.11"),
        )
        assertEquals(
            UpdateRelease.Version(3L, 0L, 11L, null),
            UpdateRelease.parseVersion("3.0.11"),
        )
        for (n in 1..4) {
            assertEquals(n, UpdateRelease.parseVersion("v3.0.13-preview.$n")!!.preview)
        }
        for (bad in listOf(
            "v3.0.13-preview.5", "v3.0.13-preview.0", "v3.0.13-preview",
            "v3.0.13-preview.1-rc", "v3.0.13-preview.1 — 稳定性预览",
            "wanBox v3.0.13-preview.1（新增 12 项修复）", "v3.0.13+build.7",
            "v999999999999.0.1-preview.1", "v-3.0.13-preview.1", "v3.0.13-preview.01",
            "v3.0.13-preview.1/../../etc", "", "latest",
        )) {
            assertNull("畸形 tag 不应被宽松解析: $bad", UpdateRelease.parseVersion(bad))
        }
    }

    @Test
    fun versionOrderIsStrictNumericNotLexicographic() {
        val v = { s: String -> UpdateRelease.parseVersion(s)!! }
        assertTrue(v("v3.0.10") > v("v3.0.9"))
        assertTrue(v("v3.0.13-preview.4") > v("v3.0.13-preview.1"))
        assertTrue(v("v3.0.14-preview.1") > v("v3.0.13-preview.4"))
        assertTrue(v("v3.0.13") > v("v3.0.13-preview.4"))
        assertEquals(0, v("v3.0.13-preview.2").compareTo(v("3.0.13-preview.2")))
    }

    @Test
    fun chineseDisplayTitleWithNumbersNeverTriggersRepeatUpdate() {
        val titled = "wanBox v3.0.13-preview.1 — 稳定性预览（新增 12 项修复，共 3 轮验收）"
        // 最小复现：旧的「name 当版本 + 连字符分段取数」会把同版本中文标题判成更新。
        assertTrue(
            "旧比较器应能复现中文标题误判",
            legacyComparator(titled.replace(Regex("(?i)^Ownbox\\s*"), ""), "3.0.13-preview.1") > 0,
        )
        val result = UpdateRelease.select(
            listOf(record("v3.0.13-preview.1", name = titled)),
            UpdateRelease.Channel.PREVIEW,
            "3.0.13-preview.1",
        )
        assertEquals(UpdateRelease.Reason.OK, result.reason)
        assertFalse("同版本带中文标题或数字描述不得反复提示更新", result.newer)
        assertEquals(titled, result.record!!.displayName)
    }

    @Test
    fun firstArrayItemIsNotImplicitlyTheNewestPreview() {
        // 最小复现：旧实现取 /releases 第一项；这里第一项是更老的预览。
        val releases = listOf(
            record("v3.0.12-preview.4"),
            record("v3.0.13-preview.2"),
        )
        val legacyFirst = releases.first()
        assertEquals("v3.0.12-preview.4", legacyFirst.tagName)
        assertFalse(UpdateRelease.parseVersion(legacyFirst.tagName)!! > UpdateRelease.parseVersion("3.0.13-preview.1")!!)
        val result = UpdateRelease.select(releases, UpdateRelease.Channel.PREVIEW, "3.0.13-preview.1")
        assertEquals("v3.0.13-preview.2", result.record!!.tagName)
        assertTrue(result.newer)
    }

    @Test
    fun emptyListAndFullyFilteredListReportNoRelease() {
        assertEquals(
            UpdateRelease.Reason.NO_RELEASE,
            UpdateRelease.select(emptyList(), UpdateRelease.Channel.PREVIEW, "3.0.13-preview.1").reason,
        )
        val invalid = listOf(
            record("v3.0.13-preview.2", draft = true),
            record("wanBox v3.0.13-preview.2 — 预览"),
            record("v3.0.13-preview.9"),
        )
        assertEquals(
            UpdateRelease.Reason.NO_RELEASE,
            UpdateRelease.select(invalid, UpdateRelease.Channel.PREVIEW, "3.0.13-preview.1").reason,
        )
    }

    @Test
    fun formalChannelNeverSelectsPreviewEvenWhenItIsFirst() {
        val releases = listOf(
            record("v3.0.14-preview.1", name = "wanBox v3.0.14-preview.1 — 预览"),
            record("v3.0.11", name = "wanBox v3.0.11", prerelease = false),
            record("v3.0.12", name = "wanBox v3.0.12", prerelease = false),
        )
        val result = UpdateRelease.select(releases, UpdateRelease.Channel.FORMAL, "3.0.11")
        assertEquals("v3.0.12", result.record!!.tagName)
        assertTrue(result.newer)
        assertEquals("wanBox v3.0.12", result.record!!.displayName)
    }

    @Test
    fun formalChannelExcludesMalformedTagInsteadOfCrashing() {
        // 最小复现：FORMAL 过滤若只写 `parseVersion(tag)?.preview == null`，
        // 畸形 tag 也返回 null 而被误当成正式版，后续排序的 !! 会抛 NPE。
        val releases = listOf(
            record("wanBox v3.0.14 — 正式版", prerelease = false),
            record("v999999999999.0.1", prerelease = false),
            record("v3.0.12", name = "wanBox v3.0.12", prerelease = false),
        )
        val result = UpdateRelease.select(releases, UpdateRelease.Channel.FORMAL, "3.0.11")
        assertEquals(UpdateRelease.Reason.OK, result.reason)
        assertEquals("v3.0.12", result.record!!.tagName)
        assertTrue(result.newer)
    }

    @Test
    fun formalChannelWithOnlyMalformedTagsReportsNoRelease() {
        val result = UpdateRelease.select(
            listOf(record("wanBox v3.0.14 — 正式版", prerelease = false)),
            UpdateRelease.Channel.FORMAL,
            "3.0.11",
        )
        assertEquals(UpdateRelease.Reason.NO_RELEASE, result.reason)
        assertNull(result.record)
    }

    @Test
    fun previewChannelDoesNotImplicitlyMigrateToNewerFormalRelease() {
        val releases = listOf(
            record("v3.0.14", name = "wanBox v3.0.14", prerelease = false),
            record("v3.0.13-preview.2"),
        )
        val result = UpdateRelease.select(releases, UpdateRelease.Channel.PREVIEW, "3.0.13-preview.1")
        assertEquals("v3.0.13-preview.2", result.record!!.tagName)
        assertTrue(result.newer)
    }

    @Test
    fun shuffledOrderAndPublicationTimeDoNotChangeSelection() {
        val releases = listOf(
            record("v3.0.13-preview.2"),
            record("v3.0.12-preview.4"),
            record("v3.0.13-preview.4"),
            record("v3.0.11-preview.3"),
            record("v3.0.13-preview.3"),
        )
        val expected = "v3.0.13-preview.4"
        for (ordered in listOf(releases, releases.reversed(), releases.shuffled(kotlin.random.Random(7)))) {
            assertEquals(
                expected,
                UpdateRelease.select(ordered, UpdateRelease.Channel.PREVIEW, "3.0.13-preview.1").record!!.tagName,
            )
        }
        // published_at 存在于 JSON，但解析后的 Record 不保存它，排序不依赖发布时间。
        val olderPublishedLater = page(
            releaseJson("v3.0.13-preview.2").put("published_at", "2030-01-01T00:00:00Z"),
            releaseJson("v3.0.13-preview.3").put("published_at", "2020-01-01T00:00:00Z"),
        )
        assertEquals(
            "v3.0.13-preview.3",
            UpdateRelease.select(olderPublishedLater, UpdateRelease.Channel.PREVIEW, "3.0.13-preview.1").record!!.tagName,
        )
    }

    @Test
    fun sameTagDifferentTitlesPreferCompleteAssetsAndNeverPromptTwice() {
        val incomplete = record(
            "v3.0.13-preview.2",
            name = "草稿标题：3.0.13-preview.2（候选稿）",
            htmlUrl = "https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.13-preview.2-a",
            assetNames = listOf(apk, sums),
        )
        val complete = record(
            "v3.0.13-preview.2",
            name = "wanBox v3.0.13-preview.2 — 稳定性预览",
            htmlUrl = "https://github.com/Wuwan94/wanBoxForAndroid/releases/tag/v3.0.13-preview.2",
        )
        for (ordered in listOf(listOf(incomplete, complete), listOf(complete, incomplete))) {
            val result = UpdateRelease.select(ordered, UpdateRelease.Channel.PREVIEW, "3.0.13-preview.1")
            assertNotNull("同 tag 应择一且优先资产完整者", result.bundle)
            assertEquals(complete.htmlUrl, result.record!!.htmlUrl)
        }
    }

    @Test
    fun incompleteAssetsAreReportedAndNeverMixedAcrossReleases() {
        val newestMissingSums = record("v3.0.13-preview.2", assetNames = listOf(apk, module))
        val olderMissingModule = record("v3.0.13-preview.1", assetNames = listOf(apk, sums))
        val result = UpdateRelease.select(
            listOf(newestMissingSums, olderMissingModule),
            UpdateRelease.Channel.PREVIEW,
            "3.0.13-preview.1",
        )
        assertEquals("v3.0.13-preview.2", result.record!!.tagName)
        assertTrue(result.newer)
        assertNull("不得拼接不同 Release 的文件补齐资产", result.bundle)
    }

    @Test
    fun assetBoundaryCasesProduceIncompleteBundle() {
        val cases = listOf(
            listOf(apk, apk, module, sums),                                   // 重复 APK
            listOf(apk, "wanbox-root-module-with-manager-arm64-v8a.zip", sums), // 仅有 with-manager 模块
            listOf(apk, module),                                              // 缺 SHA256SUMS
            listOf(module, sums),                                             // 缺 APK
            listOf(apk, sums),                                                // 缺普通模块
        )
        for (assetNames in cases) {
            val result = select(record("v3.0.13-preview.2", assetNames = assetNames))
            assertNull("资产不全必须可被 UI 识别: $assetNames", result.bundle)
            assertTrue(result.newer)
        }
    }

    @Test
    fun draftInvalidTagAndOversizedNumbersAreExcluded() {
        val releases = listOf(
            record("v3.0.13-preview.3", draft = true),
            record("v999999999999.0.1-preview.1"),
            record("v3.0.13-preview.1 — 中文标题"),
            record("v3.0.13-preview.2"),
        )
        val result = UpdateRelease.select(releases, UpdateRelease.Channel.PREVIEW, "3.0.13-preview.1")
        assertEquals("v3.0.13-preview.2", result.record!!.tagName)
    }

    @Test
    fun oversizedNumberOnlyReleaseIsNotSwallowedByLooseComparison() {
        val result = UpdateRelease.select(
            listOf(record("v999999999999.0.1-preview.1")),
            UpdateRelease.Channel.PREVIEW,
            "3.0.13-preview.1",
        )
        assertEquals(UpdateRelease.Reason.NO_RELEASE, result.reason)
        assertNull(result.record)
    }

    @Test
    fun invalidLocalVersionFailsClosed() {
        val result = UpdateRelease.select(
            listOf(record("v3.0.13-preview.2")),
            UpdateRelease.Channel.PREVIEW,
            "wanBox v3.0.13-preview.1 — 稳定性预览",
        )
        assertEquals(UpdateRelease.Reason.INVALID_LOCAL_VERSION, result.reason)
        assertFalse(result.newer)
    }

    @Test
    fun sameOrOlderVersionIsNotAnUpgrade() {
        assertFalse(select(record("v3.0.13-preview.1")).newer)
        assertFalse(select(record("v3.0.12-preview.4")).newer)
        assertFalse(select(record("v3.0.11", prerelease = false)).newer)
        assertTrue(select(record("v3.0.13-preview.2")).newer)
        assertTrue(select(record("v3.0.14-preview.1")).newer)
    }

    @Test
    fun preview1Through4AreCompatibleWithVersionCodeRule() {
        var previous = UpdateRelease.parseVersion("v3.0.13-preview.1")!!
        for (n in 2..4) {
            val current = UpdateRelease.parseVersion("v3.0.13-preview.$n")!!
            assertTrue(current > previous)
            previous = current
        }
        assertNull(UpdateRelease.parseVersion("v3.0.13-preview.5"))
        assertNull(UpdateRelease.parseVersion("v3.0.13-preview.0"))
    }

    @Test
    fun paginationStopsOnShortPageAndHardPageLimit() {
        assertEquals(2, UpdateRelease.nextPage(1, UpdateRelease.PAGE_SIZE))
        assertEquals(
            UpdateRelease.MAX_PAGES,
            UpdateRelease.nextPage(UpdateRelease.MAX_PAGES - 1, UpdateRelease.PAGE_SIZE),
        )
        assertNull(UpdateRelease.nextPage(UpdateRelease.MAX_PAGES, UpdateRelease.PAGE_SIZE))
        assertNull(UpdateRelease.nextPage(1, UpdateRelease.PAGE_SIZE - 1))
        assertNull(UpdateRelease.nextPage(1, 0))
        assertNull(UpdateRelease.nextPage(0, UpdateRelease.PAGE_SIZE))
    }

    @Test
    fun parsePageHandlesArraySingleObjectAndEmptyBody() {
        assertEquals(2, page(releaseJson("v3.0.13-preview.1"), releaseJson("v3.0.13-preview.2")).size)
        assertEquals(1, UpdateRelease.parsePage(releaseJson("v3.0.13-preview.2").toString()).size)
        assertTrue(UpdateRelease.parsePage("").isEmpty())
        assertTrue(UpdateRelease.parsePage("not json").isEmpty())
    }
}
