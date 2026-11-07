// @author 雾晚
package io.nekohasekai.sagernet.utils

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Publish only complete downloads; failed transfers never leave a partial target. */
internal object ArtifactDownload {
    class StorageException(cause: IOException) : IOException("Update storage write failed", cause)

    // The output boundary also lets JVM tests exercise a real ENOSPC sink without filling a host disk.
    fun write(target: File, input: InputStream, openOutput: (File) -> OutputStream = { it.outputStream() }) {
        val directory = requireNotNull(target.absoluteFile.parentFile)
        val partial = storage { File.createTempFile(".${target.name}.", ".part", directory) }
        try {
            val output = storage { openOutput(partial) }
            try {
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    storage { output.write(buffer, 0, count) }
                }
            } finally {
                storage { output.close() }
            }
            storage {
                // Fail closed if this filesystem cannot atomically publish the complete file.
                Files.move(partial.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }
        } finally {
            partial.delete()
        }
    }

    private inline fun <T> storage(action: () -> T): T = try {
        action()
    } catch (e: IOException) {
        throw StorageException(e)
    }
}
