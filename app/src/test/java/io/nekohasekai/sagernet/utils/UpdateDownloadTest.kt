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
}
