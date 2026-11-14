// @author 雾晚
package io.nekohasekai.sagernet.utils

import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest

class UpdateDownloadTest {
    private val apkName = "wanBoxForAndroid-3.0.12-preview.2-arm64-v8a.apk"
    private val releaseTag = "v3.0.12-preview.2"
    private val expectedVersion = UpdateRelease.parseVersion("3.0.12-preview.2")!!
    private val moduleName = UpdateArtifacts.MODULE_NAME
    private val payload = mapOf(apkName to "manager".toByteArray(), moduleName to "root-module".toByteArray())
    private val assets = listOf(apkName, moduleName, UpdateArtifacts.SUMS_NAME).map {
        UpdateArtifacts.ReleaseAsset(it, "https://example.invalid/$it", 1)
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private fun bytes(name: String): ByteArray = payload[name] ?: payload.entries.joinToString("\n") {
        "${digest(it.value)}  ${it.key}"
    }.toByteArray()

    private fun transfer(asset: UpdateArtifacts.ReleaseAsset, target: File) {
        ByteArrayInputStream(bytes(asset.name)).use { ArtifactDownload.write(target, it) }
    }

    private fun withCache(body: (File, File) -> Unit) {
        val cache = Files.createTempDirectory("wanbox-update-test").toFile()
        try {
            val previous = File(cache, "previous-verified.apk").apply { writeText("previous update") }
            body(cache, previous)
        } finally { cache.deleteRecursively() }
    }

    private fun assertFailure(
        cache: File,
        previous: File,
        reason: UpdateDownload.Reason,
        block: () -> UpdateDownload.Ready,
    ) {
        var installPrompts = 0
        val failure = assertThrows(UpdateDownload.Failure::class.java) {
            block()
            installPrompts++ // Same success-only boundary used by AboutFragment.
        }
        assertEquals(reason, failure.reason)
        assertEquals(0, installPrompts)
        assertEquals("previous update", previous.readText())
        assertEquals(listOf(previous.name), cache.list()!!.sorted())
    }

    @Test
    fun disconnectedTransferAtEveryStageCleansAttemptAndNeverPrompts() = withCache { cache, previous ->
        for (failedName in assets.map { it.name }) {
            assertFailure(cache, previous, UpdateDownload.Reason.TRANSFER) {
                UpdateDownload.prepare(assets, releaseTag, cache) { asset, target ->
                    if (asset.name != failedName) transfer(asset, target) else {
                        val disconnected = object : InputStream() {
                            var readCount = 0
                            override fun read(): Int {
                                if (readCount++ < 4) return 1
                                throw IOException("connection reset")
                            }
                        }
                        ArtifactDownload.write(target, disconnected)
                    }
                }
            }
        }
    }

    @Test
    fun cancellationAtEveryStageIsPreservedAndCleansOnlyItsAttempt() = withCache { cache, previous ->
        for (failedName in assets.map { it.name }) {
            val cancel = kotlinx.coroutines.CancellationException("view destroyed")
            val actual = assertThrows(kotlinx.coroutines.CancellationException::class.java) {
                UpdateDownload.prepare(assets, releaseTag, cache) { asset, target ->
                    if (asset.name == failedName) throw cancel
                    transfer(asset, target)
                }
            }
            assertSame(cancel, actual)
            assertEquals("previous update", previous.readText())
            assertEquals(listOf(previous.name), cache.list()!!.sorted())
        }
    }

    @Test
    fun fullDiskAtEveryStageCleansAttemptAndNeverPrompts() = withCache { cache, previous ->
        // /dev/full is Linux's real ENOSPC device: no host disk is filled and no fake exception is thrown.
        assumeTrue("Linux ENOSPC device required", File("/dev/full").exists())
        for (failedName in assets.map { it.name }) {
            assertFailure(cache, previous, UpdateDownload.Reason.STORAGE) {
                UpdateDownload.prepare(assets, releaseTag, cache) { asset, target ->
                    if (asset.name != failedName) transfer(asset, target) else {
                        ByteArrayInputStream(bytes(asset.name)).use { input ->
                            ArtifactDownload.write(target, input) { FileOutputStream("/dev/full") }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun wrongApkOrModuleDigestCleansWholePairAndNeverPrompts() = withCache { cache, previous ->
        for (failedName in payload.keys) {
            assertFailure(cache, previous, UpdateDownload.Reason.CHECKSUM) {
                UpdateDownload.prepare(assets, releaseTag, cache) { asset, target ->
                    if (asset.name == failedName) {
                        ByteArrayInputStream("corrupt".toByteArray()).use { ArtifactDownload.write(target, it) }
                    } else transfer(asset, target)
                }
            }
        }
    }

    @Test
    fun missingReleaseAssetRejectsBeforeDownload() = withCache { cache, previous ->
        for (missing in assets) {
            var calls = 0
            assertFailure(cache, previous, UpdateDownload.Reason.ASSETS) {
                UpdateDownload.prepare(assets - missing, releaseTag, cache) { _, _ -> calls++ }
            }
            assertEquals(0, calls)
        }
    }

    @Test
    fun missingManifestEntryStopsBeforePackageDownload() = withCache { cache, previous ->
        var calls = 0
        assertFailure(cache, previous, UpdateDownload.Reason.CHECKSUM) {
            UpdateDownload.prepare(assets, releaseTag, cache) { _, target ->
                calls++
                ByteArrayInputStream("invalid manifest".toByteArray()).use { ArtifactDownload.write(target, it) }
            }
        }
        assertEquals(1, calls)
    }

    @Test
    fun successReturnsVerifiedFilesWithoutPartialFiles() = withCache { cache, previous ->
        val ready = UpdateDownload.prepare(assets, releaseTag, cache, ::transfer)
        assertArrayEquals(payload[apkName], ready.apk.readBytes())
        assertArrayEquals(payload[moduleName], ready.module.readBytes())
        assertEquals(
            (assets.map { it.name } + UpdateDownload.TAG_FILE_NAME).sorted(),
            ready.directory.list()!!.sorted()
        )
        assertEquals("3.0.12-preview.2", File(ready.directory, UpdateDownload.TAG_FILE_NAME).readText())
        assertEquals("previous update", previous.readText())
        assertTrue(ready.directory.parentFile == cache)
    }

    @Test
    fun failedOverlappingAttemptCannotDeleteSuccessfulAttempt() = withCache { cache, previous ->
        var ready: UpdateDownload.Ready? = null
        assertThrows(UpdateDownload.Failure::class.java) {
            UpdateDownload.prepare(assets, releaseTag, cache) { asset, target ->
                transfer(asset, target)
                if (asset.name == apkName) {
                    ready = UpdateDownload.prepare(assets, releaseTag, cache, ::transfer)
                    throw IOException("connection reset after concurrent success")
                }
            }
        }
        assertArrayEquals(payload[apkName], ready!!.apk.readBytes())
        assertArrayEquals(payload[moduleName], ready!!.module.readBytes())
        assertEquals(setOf(previous.name, ready!!.directory.name), cache.list()!!.toSet())
    }

    private fun <T> withClock(start: Long, body: (LongArray) -> T): T {
        val now = longArrayOf(start)
        val original = UpdateDownload.clock
        UpdateDownload.clock = { now[0] }
        try { return body(now) } finally { UpdateDownload.clock = original }
    }

    @Test
    fun heldReadyPairStaysReadableWhileNewerAttemptRuns() = withCache { cache, previous ->
        withClock(1_000_000L) { _ ->
            val first = UpdateDownload.prepare(assets, releaseTag, cache, ::transfer)
            // The consumer (system installer / Root manager) still holds A while B downloads and finishes.
            val second = UpdateDownload.prepare(assets, releaseTag, cache) { asset, target ->
                assertArrayEquals(payload[apkName], first.apk.readBytes())
                assertArrayEquals(payload[moduleName], first.module.readBytes())
                transfer(asset, target)
            }
            assertArrayEquals(payload[apkName], first.apk.readBytes())
            assertArrayEquals(payload[moduleName], first.module.readBytes())
            assertArrayEquals(payload[apkName], second.apk.readBytes())
            assertEquals(setOf(previous.name, first.directory.name, second.directory.name), cache.list()!!.toSet())
        }
    }

    @Test
    fun failedNewerAttemptOnlyCleansItselfAndKeepsHeldPair() = withCache { cache, previous ->
        withClock(1_000_000L) { _ ->
            val first = UpdateDownload.prepare(assets, releaseTag, cache, ::transfer)
            assertThrows(UpdateDownload.Failure::class.java) {
                UpdateDownload.prepare(assets, releaseTag, cache) { _, _ -> throw IOException("connection reset") }
            }
            assertArrayEquals(payload[moduleName], first.module.readBytes())
            assertEquals(setOf(previous.name, first.directory.name), cache.list()!!.toSet())
        }
    }

    @Test
    fun releasedPairIsReclaimedImmediately() = withCache { cache, previous ->
        val ready = UpdateDownload.prepare(assets, releaseTag, cache, ::transfer)
        UpdateDownload.release(ready)
        UpdateDownload.release(ready) // idempotent
        assertFalse(ready.directory.exists())
        assertEquals(listOf(previous.name), cache.list()!!.sorted())
    }

    @Test
    fun expiredHeldPairIsReclaimedByNextAttempt() = withCache { cache, previous ->
        withClock(1_000_000L) { now ->
            val first = UpdateDownload.prepare(assets, releaseTag, cache, ::transfer)
            now[0] += UpdateDownload.RETENTION_MS
            val second = UpdateDownload.prepare(assets, releaseTag, cache, ::transfer)
            assertFalse(first.directory.exists())
            assertEquals(setOf(previous.name, second.directory.name), cache.list()!!.toSet())
        }
    }

    @Test
    fun retainedPairsAreBoundedNewestFirst() = withCache { cache, previous ->
        withClock(1_000_000L) { now ->
            val attempts = (1..4).map {
                now[0] += 1_000
                UpdateDownload.prepare(assets, releaseTag, cache, ::transfer)
            }
            val kept = attempts.takeLast(UpdateDownload.MAX_RETAINED)
            attempts.dropLast(UpdateDownload.MAX_RETAINED).forEach { assertFalse(it.directory.exists()) }
            kept.forEach { assertArrayEquals(payload[moduleName], it.module.readBytes()) }
            assertEquals(setOf(previous.name) + kept.map { it.directory.name }, cache.list()!!.toSet())
        }
    }

    @Test
    fun attemptLeftoverFromKilledProcessIsReclaimedOnlyAfterRetention() = withCache { cache, _ ->
        withClock(System.currentTimeMillis()) { now ->
            val leftover = File(cache, "attempt-leftover").apply { mkdirs() }
            File(leftover, "module.zip").writeText("flash me after the APK install restarted the app")
            leftover.setLastModified(now[0] - 60_000)
            val unrelated = File(cache, "unrelated").apply { mkdirs() }
            val ready = UpdateDownload.prepare(assets, releaseTag, cache, ::transfer)
            // A previous process's verified pair may still be waiting for the Root manager.
            assertTrue(leftover.isDirectory)
            UpdateDownload.release(ready)
            leftover.setLastModified(now[0] - UpdateDownload.RETENTION_MS - 1)
            val next = UpdateDownload.prepare(assets, releaseTag, cache, ::transfer)
            assertFalse(leftover.exists())
            assertTrue(unrelated.isDirectory)
            assertTrue(next.directory.isDirectory)
        }
    }

    @Test
    fun clockRollbackDoesNotExpireAStillConsumablePair() = withCache { cache, _ ->
        withClock(1_000_000L) { now ->
            val first = UpdateDownload.prepare(assets, releaseTag, cache, ::transfer)
            now[0] += UpdateDownload.RETENTION_MS
            now[0] = 1L // 系统时间被回拨到远早于下载开始
            val second = UpdateDownload.prepare(assets, releaseTag, cache, ::transfer)
            // 回拨时保守保留：不因负的 elapsed 把仍待交接的第一份误判为过期。
            assertTrue(first.directory.exists())
            assertTrue(second.directory.exists())
            assertArrayEquals(payload[moduleName], first.module.readBytes())
        }
    }

    @Test
    fun thirdSuccessfulDownloadDropsOldestPairByPolicy() = withCache { cache, _ ->
        withClock(1_000_000L) { now ->
            val attempts = (1..3).map {
                now[0] += 1_000
                UpdateDownload.prepare(assets, releaseTag, cache, ::transfer)
            }
            // 既定策略是「最多两份、1 小时」，不是对消费者的永久持有保证：
            // 第三次成功后最旧一份被挤出，UI 必须提供明确的导出入口。
            assertFalse(attempts[0].directory.exists())
            assertTrue(attempts[1].directory.exists())
            assertTrue(attempts[2].directory.exists())
        }
    }

    @Test
    fun failedDeletionKeepsRegistryAndIsRetriedByNextAttempt() = withCache { cache, _ ->
        val original = UpdateDownload.removeAttempt
        withClock(1_000_000L) { now ->
            val first = UpdateDownload.prepare(assets, releaseTag, cache, ::transfer)
            UpdateDownload.removeAttempt = { false }
            try {
                now[0] += UpdateDownload.RETENTION_MS
                // 删除失败时目录仍在，且仍能被导出入口找到。
                val second = UpdateDownload.prepare(assets, releaseTag, cache, ::transfer)
                assertTrue(first.directory.exists())
                assertNotNull(UpdateDownload.recentReady(cache, expectedVersion))
            } finally {
                UpdateDownload.removeAttempt = original
            }
            now[0] += 1_000
            UpdateDownload.prepare(assets, releaseTag, cache, ::transfer)
            assertFalse("恢复删除后应按同一规则重试回收", first.directory.exists())
        }
    }

    @Test
    fun recentReadyLocatesVerifiedPairWithoutPrivatePath() = withCache { cache, _ ->
        assertNull(UpdateDownload.recentReady(cache, expectedVersion))
        val incomplete = File(cache, "attempt-incomplete").apply { mkdirs() }
        File(incomplete, "module.zip").writeText("only a module")
        assertNull(UpdateDownload.recentReady(cache, expectedVersion))
        val ready = UpdateDownload.prepare(assets, releaseTag, cache, ::transfer)
        val found = UpdateDownload.recentReady(cache, expectedVersion)!!
        assertArrayEquals(ready.apk.readBytes(), found.apk.readBytes())
        assertArrayEquals(ready.module.readBytes(), found.module.readBytes())
        assertEquals(ready.directory.name, found.directory.name)
    }

    @Test
    fun recentReadyRefusesExpiredAttemptDirectory() = withCache { cache, _ ->
        val now = System.currentTimeMillis()
        withClock(now) { clock ->
            val ready = UpdateDownload.prepare(assets, releaseTag, cache, ::transfer)
            assertNotNull(UpdateDownload.recentReady(cache, expectedVersion))
            ready.directory.setLastModified(now - UpdateDownload.RETENTION_MS - 1)
            assertNull(UpdateDownload.recentReady(cache, expectedVersion))
            clock[0] = now - 10L // 时间回拨：保守保留，仍可导出
            assertNotNull(UpdateDownload.recentReady(cache, expectedVersion))
        }
    }

    @Test
    fun recentReadyReturnsThePairMatchingTheInstalledVersionNotJustTheNewest() = withCache { cache, _ ->
        withClock(1_000_000L) { now ->
            val older = UpdateDownload.prepare(assets, "v3.0.12-preview.2", cache, ::transfer)
            now[0] += 1_000
            val newer = UpdateDownload.prepare(assets, "v3.0.13-preview.1", cache, ::transfer)
            // 两份都在保留期内：按「最近」取会拿到较新的一份，但导出必须按版本对号。
            val forOlder = UpdateDownload.recentReady(cache, UpdateRelease.parseVersion("3.0.12-preview.2")!!)!!
            assertEquals(older.directory.name, forOlder.directory.name)
            val forNewer = UpdateDownload.recentReady(cache, UpdateRelease.parseVersion("3.0.13-preview.1")!!)!!
            assertEquals(newer.directory.name, forNewer.directory.name)
            assertNull(UpdateDownload.recentReady(cache, UpdateRelease.parseVersion("3.0.13-preview.2")!!))
        }
    }

    @Test
    fun recentReadyIgnoresCompletePairWithoutAVersionStamp() = withCache { cache, _ ->
        // 本修复之前下载的包没有版本戳：三件套再齐全也不能当作「同版」导出。
        val unstamped = File(cache, "attempt-unstamped").apply { mkdirs() }
        File(unstamped, apkName).writeBytes(payload[apkName]!!)
        File(unstamped, moduleName).writeBytes(payload[moduleName]!!)
        File(unstamped, UpdateArtifacts.SUMS_NAME).writeText("sums")
        assertNull(UpdateDownload.recentReady(cache, expectedVersion))
    }

    @Test
    fun prepareRejectsAnUnparseableReleaseTag() = withCache { cache, previous ->
        assertFailure(cache, previous, UpdateDownload.Reason.ASSETS) {
            UpdateDownload.prepare(assets, "not-a-version", cache, ::transfer)
        }
    }

    @Test
    fun outOfOrderCompletionKeepsBothVerifiedPairs() = withCache { cache, _ ->
        var later: UpdateDownload.Ready? = null
        val earlier = UpdateDownload.prepare(assets, releaseTag, cache) { asset, target ->
            // 后开始的尝试先完成，先开始的尝试随后才完成。
            if (later == null) later = UpdateDownload.prepare(assets, releaseTag, cache, ::transfer)
            transfer(asset, target)
        }
        assertArrayEquals(payload[apkName], earlier.apk.readBytes())
        assertArrayEquals(payload[apkName], later!!.apk.readBytes())
        assertTrue(earlier.directory.exists())
        assertTrue(later!!.directory.exists())
    }
}
