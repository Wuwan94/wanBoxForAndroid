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
     * How long a verified pair is kept on disk after [prepare] hands it out. The system
     * installer reads the APK after we return, and the user flashes the module ZIP in
     * their Root manager after the APK install restarts the app, so a Ready pair must
     * outlive the call. @author 雾晚
     *
     * This is a bounded cache policy, NOT a permanent consumer guarantee: a third
     * successful download pushes the oldest finished pair out ([MAX_RETAINED]), and
     * anything older than [RETENTION_MS] is reclaimed by the next attempt. The real
     * consumer protections are that a running attempt is never touched, a held pair
     * survives a concurrent newer attempt, and the user gets an explicit export entry
     * (see AboutFragment) instead of being told to type a private cache path.
     */
    const val RETENTION_MS = 60L * 60 * 1000

    /** At most this many finished attempts stay on disk, newest first, so the cache stays bounded. */
    const val MAX_RETAINED = 2

    /** Injectable for lifecycle tests; production uses the wall clock. */
    @Volatile internal var clock: () -> Long = System::currentTimeMillis

    /** Injectable for lifecycle tests; production deletes the attempt directory recursively. */
    @Volatile internal var removeAttempt: (File) -> Boolean = { it.deleteRecursively() }

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
            // Deletion failure keeps the registry entry so the next attempt retries the
            // same bounded rule instead of turning the directory into a mtime-only orphan.
            val removed = !ready.directory.exists() || removeAttempt(ready.directory)
            if (removed) {
                heldSince -= ready.directory.name
                attemptSequences -= ready.directory.name
            }
        }
    }

    /**
     * 进程重启后重新定位最近一次已校验的更新包，供「导出已校验 Root 模块」入口使用。
     * 只认三件套齐全的 attempt 目录，且仍在 [RETENTION_MS] 内；目录名随机，
     * 因此这里按目录修改时间取最新，而不是要求用户记住私有路径。
     * 系统时间回拨时保守保留，避免把仍待交接的包误判为过期。
     */
    fun recentReady(cache: File): Ready? {
        if (!cache.isDirectory) return null
        val now = clock()
        val attempts = cache.listFiles { file ->
            file.isDirectory && file.name.startsWith(ATTEMPT_PREFIX)
        } ?: return null
        return attempts.mapNotNull { dir ->
            val files = dir.listFiles()?.filter { it.isFile } ?: return@mapNotNull null
            val apk = files.firstOrNull {
                it.name.endsWith(".apk", ignoreCase = true) && it.name.contains("arm64-v8a")
            } ?: return@mapNotNull null
            val module = files.firstOrNull { it.name == UpdateArtifacts.MODULE_NAME }
                ?: return@mapNotNull null
            if (files.none { it.name == UpdateArtifacts.SUMS_NAME }) return@mapNotNull null
            val since = dir.lastModified()
            if (now >= since && now - since >= RETENTION_MS) return@mapNotNull null
            Ready(apk, module, dir) to since
        }.maxByOrNull { it.second }?.first
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
                is kotlinx.coroutines.CancellationException -> e
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
                // Clock rollback makes now - since negative: keep the pair instead of
                // deleting an update that the installer / Root manager may still read.
                val expired = now >= since && now - since >= RETENTION_MS
                if ((expired || index >= slots) && removeAttempt(attempt)) {
                    heldSince -= attempt.name
                    attemptSequences -= attempt.name
                }
                // On deletion failure the registry entries stay: the next attempt
                // retries the same bounded rule rather than losing track of the pair.
            }
        }
    }
}
