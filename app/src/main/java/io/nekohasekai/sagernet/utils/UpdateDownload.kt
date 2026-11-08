// @author 雾晚
package io.nekohasekai.sagernet.utils

import java.io.File
import java.io.IOException
import java.nio.file.Files

/** One isolated attempt: only a verified pair may reach the caller's installation prompt. */
internal object UpdateDownload {
    enum class Reason { TRANSFER, STORAGE, CHECKSUM, ASSETS }
    class Failure(val reason: Reason, cause: Exception? = null) : IOException(reason.name, cause)
    data class Ready(val apk: File, val module: File, val directory: File)

    private const val ATTEMPT_PREFIX = "attempt-"

    // Attempt directories get random names, so this process can only tell its
    // own attempts apart through this registry: a directory that is still
    // active belongs to a running attempt, and a higher sequence was begun
    // after the current one. Anything else on disk is a finished attempt or a
    // leftover from a killed process, and is reclaimed by newer attempts.
    private val registryLock = Any()
    private var attemptCounter = 0L
    private val attemptSequences = mutableMapOf<String, Long>()
    private val activeAttempts = mutableSetOf<String>()

    fun prepare(
        assets: List<UpdateArtifacts.ReleaseAsset>,
        cache: File,
        download: (UpdateArtifacts.ReleaseAsset, File) -> Unit,
    ): Ready {
        val bundle = UpdateArtifacts.selectBundle(assets) ?: throw Failure(Reason.ASSETS)
        var directory: File
        var sequence: Long
        synchronized(registryLock) {
            directory = try {
                if (!cache.isDirectory && !cache.mkdirs()) throw IOException("Cannot create update cache")
                Files.createTempDirectory(cache.toPath(), ATTEMPT_PREFIX).toFile()
            } catch (e: IOException) {
                throw Failure(Reason.STORAGE, e)
            }
            sequence = ++attemptCounter
            attemptSequences[directory.name] = sequence
            activeAttempts += directory.name
        }
        try {
            deleteStaleAttempts(cache, directory, sequence)
            val sumsFile = File(directory, bundle.sums.name)
            val apk = File(directory, bundle.apk.name)
            val module = File(directory, bundle.module.name)
            download(bundle.sums, sumsFile)
            val sums = UpdateArtifacts.parseSums(sumsFile.readText())
            // Missing entries are an incomplete verification manifest, never an unverified fallback.
            if (bundle.apk.name !in sums || bundle.module.name !in sums) throw Failure(Reason.CHECKSUM)
            download(bundle.apk, apk)
            download(bundle.module, module)
            if (!UpdateArtifacts.verify(apk, sums) || !UpdateArtifacts.verify(module, sums)) {
                throw Failure(Reason.CHECKSUM)
            }
            deleteStaleAttempts(cache, directory, sequence)
            return Ready(apk, module, directory)
        } catch (e: Exception) {
            directory.deleteRecursively()
            throw when (e) {
                is Failure -> e
                is ArtifactDownload.StorageException -> Failure(Reason.STORAGE, e)
                else -> Failure(Reason.TRANSFER, e)
            }
        } finally {
            synchronized(registryLock) { activeAttempts -= directory.name }
        }
    }

    /**
     * Attempt directories are disposable: the newest successful attempt is kept
     * only until a newer attempt begins, so stale ones never accumulate in cache.
     * Running attempts and attempts begun after [current] are never touched.
     */
    private fun deleteStaleAttempts(cache: File, current: File, currentSequence: Long) {
        synchronized(registryLock) {
            val attempts = cache.listFiles { file ->
                file.isDirectory && file.name.startsWith(ATTEMPT_PREFIX)
            } ?: return
            for (attempt in attempts) {
                if (attempt.name == current.name) continue
                val keep = attempt.name in activeAttempts ||
                    (attemptSequences[attempt.name] ?: 0L) > currentSequence
                if (!keep) attempt.deleteRecursively()
            }
        }
    }
}
