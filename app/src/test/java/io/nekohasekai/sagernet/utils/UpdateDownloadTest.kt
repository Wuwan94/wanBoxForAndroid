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
                UpdateDownload.prepare(assets, cache) { asset, target ->
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
    fun fullDiskAtEveryStageCleansAttemptAndNeverPrompts() = withCache { cache, previous ->
        // /dev/full is Linux's real ENOSPC device: no host disk is filled and no fake exception is thrown.
        assumeTrue("Linux ENOSPC device required", File("/dev/full").exists())
        for (failedName in assets.map { it.name }) {
            assertFailure(cache, previous, UpdateDownload.Reason.STORAGE) {
                UpdateDownload.prepare(assets, cache) { asset, target ->
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
                UpdateDownload.prepare(assets, cache) { asset, target ->
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
                UpdateDownload.prepare(assets - missing, cache) { _, _ -> calls++ }
            }
            assertEquals(0, calls)
        }
    }

    @Test
    fun missingManifestEntryStopsBeforePackageDownload() = withCache { cache, previous ->
        var calls = 0
        assertFailure(cache, previous, UpdateDownload.Reason.CHECKSUM) {
            UpdateDownload.prepare(assets, cache) { _, target ->
                calls++
                ByteArrayInputStream("invalid manifest".toByteArray()).use { ArtifactDownload.write(target, it) }
            }
        }
        assertEquals(1, calls)
    }

    @Test
    fun successReturnsVerifiedFilesWithoutPartialFiles() = withCache { cache, previous ->
        val ready = UpdateDownload.prepare(assets, cache, ::transfer)
        assertArrayEquals(payload[apkName], ready.apk.readBytes())
        assertArrayEquals(payload[moduleName], ready.module.readBytes())
        assertEquals(assets.map { it.name }.sorted(), ready.directory.list()!!.sorted())
        assertEquals("previous update", previous.readText())
        assertTrue(ready.directory.parentFile == cache)
    }

    @Test
    fun failedOverlappingAttemptCannotDeleteSuccessfulAttempt() = withCache { cache, previous ->
        var ready: UpdateDownload.Ready? = null
        assertThrows(UpdateDownload.Failure::class.java) {
            UpdateDownload.prepare(assets, cache) { asset, target ->
                transfer(asset, target)
                if (asset.name == apkName) {
                    ready = UpdateDownload.prepare(assets, cache, ::transfer)
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
            val first = UpdateDownload.prepare(assets, cache, ::transfer)
            // The consumer (system installer / Root manager) still holds A while B downloads and finishes.
            val second = UpdateDownload.prepare(assets, cache) { asset, target ->
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
            val first = UpdateDownload.prepare(assets, cache, ::transfer)
            assertThrows(UpdateDownload.Failure::class.java) {
                UpdateDownload.prepare(assets, cache) { _, _ -> throw IOException("connection reset") }
            }
            assertArrayEquals(payload[moduleName], first.module.readBytes())
            assertEquals(setOf(previous.name, first.directory.name), cache.list()!!.toSet())
        }
    }

    @Test
    fun releasedPairIsReclaimedImmediately() = withCache { cache, previous ->
        val ready = UpdateDownload.prepare(assets, cache, ::transfer)
        UpdateDownload.release(ready)
        UpdateDownload.release(ready) // idempotent
        assertFalse(ready.directory.exists())
        assertEquals(listOf(previous.name), cache.list()!!.sorted())
    }

    @Test
    fun expiredHeldPairIsReclaimedByNextAttempt() = withCache { cache, previous ->
        withClock(1_000_000L) { now ->
            val first = UpdateDownload.prepare(assets, cache, ::transfer)
            now[0] += UpdateDownload.RETENTION_MS
            val second = UpdateDownload.prepare(assets, cache, ::transfer)
            assertFalse(first.directory.exists())
            assertEquals(setOf(previous.name, second.directory.name), cache.list()!!.toSet())
        }
    }

    @Test
    fun retainedPairsAreBoundedNewestFirst() = withCache { cache, previous ->
        withClock(1_000_000L) { now ->
            val attempts = (1..4).map {
                now[0] += 1_000
                UpdateDownload.prepare(assets, cache, ::transfer)
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
            val ready = UpdateDownload.prepare(assets, cache, ::transfer)
            // A previous process's verified pair may still be waiting for the Root manager.
            assertTrue(leftover.isDirectory)
            UpdateDownload.release(ready)
            leftover.setLastModified(now[0] - UpdateDownload.RETENTION_MS - 1)
            val next = UpdateDownload.prepare(assets, cache, ::transfer)
            assertFalse(leftover.exists())
            assertTrue(unrelated.isDirectory)
            assertTrue(next.directory.isDirectory)
        }
    }
}
