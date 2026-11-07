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

    fun prepare(
        assets: List<UpdateArtifacts.ReleaseAsset>,
        cache: File,
        download: (UpdateArtifacts.ReleaseAsset, File) -> Unit,
    ): Ready {
        val bundle = UpdateArtifacts.selectBundle(assets) ?: throw Failure(Reason.ASSETS)
        val directory = try {
            if (!cache.isDirectory && !cache.mkdirs()) throw IOException("Cannot create update cache")
            Files.createTempDirectory(cache.toPath(), "attempt-").toFile()
        } catch (e: IOException) {
            throw Failure(Reason.STORAGE, e)
        }
        try {
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
            return Ready(apk, module, directory)
        } catch (e: Exception) {
            directory.deleteRecursively()
            throw when (e) {
                is Failure -> e
                is ArtifactDownload.StorageException -> Failure(Reason.STORAGE, e)
                else -> Failure(Reason.TRANSFER, e)
            }
        }
    }
}
