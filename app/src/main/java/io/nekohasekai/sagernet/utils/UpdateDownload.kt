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

    /**
     * How long a verified pair stays on disk after [prepare] hands it out. The system
     * installer reads the APK after we return, installing the APK kills this process,
     * and the user flashes the module ZIP in their Root manager afterwards, so a Ready
     * pair must outlive both the call and the process. @author 雾晚
     */
    const val RETENTION_MS = 60L * 60 * 1000

    /** At most this many finished attempts stay on disk, newest first, so the cache stays bounded. */
    const val MAX_RETAINED = 2

    /** Injectable for lifecycle tests; production uses the wall clock. */
    @Volatile internal var clock: () -> Long = System::currentTimeMillis

    // Attempt directories get random names, so this process can only tell its
    // own attempts apart through this registry: a directory that is still
    // active belongs to a running attempt, and a higher sequence was begun
    // after the current one. Finished attempts are retained (held) until the
    // consumer releases them, they age past RETENTION_MS, or newer finished
    // attempts push them past MAX_RETAINED. Directories this process does not
    // know (a killed process's leftovers) age by their modification time.
    private val registryLock = Any()
    private var attemptCounter = 0L
    private val attemptSequences = mutableMapOf<String, Long>()
    private val activeAttempts = mutableSetOf<String>()
    private val heldSince = mutableMapOf<String, Long>()

    /**
     * The consumer is done with [ready] (install prompt failed, user cancelled, or
     * install finished): its files may be reclaimed now. Safe to call twice.
     */
    fun release(ready: Ready) {
        synchronized(registryLock) {
            heldSince -= ready.directory.name
            attemptSequences -= ready.directory.name
            ready.directory.deleteRecursively()
        }
    }

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
            synchronized(registryLock) { heldSince[directory.name] = clock() }
            deleteStaleAttempts(cache, directory, sequence)
            return Ready(apk, module, directory)
        } catch (e: Exception) {
            // A failed attempt only ever removes its own directory.
            synchronized(registryLock) {
                heldSince -= directory.name
                attemptSequences -= directory.name
            }
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
     * Reclaims finished attempts that nobody can still be consuming:
     * released ones are already gone; held ones go once they age past
     * [RETENTION_MS] or fall outside the newest [MAX_RETAINED]; unknown leftovers
     * age by their modification time. Running attempts, attempts begun after
     * [current], and [current] itself are never touched.
     */
    private fun deleteStaleAttempts(cache: File, current: File, currentSequence: Long) {
        synchronized(registryLock) {
            val now = clock()
            val attempts = cache.listFiles { file ->
                file.isDirectory && file.name.startsWith(ATTEMPT_PREFIX)
            } ?: return
            val finished = attempts.filter { attempt ->
                attempt.name != current.name &&
                    attempt.name !in activeAttempts &&
                    (attemptSequences[attempt.name] ?: 0L) <= currentSequence
            }.map { it to (heldSince[it.name] ?: it.lastModified()) }
                .sortedByDescending { it.second }
            // [current] counts against the bound once it holds a verified pair.
            val slots = MAX_RETAINED - if (current.name in heldSince) 1 else 0
            finished.forEachIndexed { index, (attempt, since) ->
                val expired = now - since >= RETENTION_MS
                if (expired || index >= slots) {
                    heldSince -= attempt.name
                    attemptSequences -= attempt.name
                    attempt.deleteRecursively()
                }
            }
        }
    }
}
