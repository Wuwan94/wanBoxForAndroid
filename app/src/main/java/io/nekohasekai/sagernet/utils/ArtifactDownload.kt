// @author 雾晚
package io.nekohasekai.sagernet.utils

import java.io.File
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Publish only complete downloads; failed transfers never leave a partial target. */
internal object ArtifactDownload {
    fun write(target: File, input: InputStream) {
        val directory = requireNotNull(target.absoluteFile.parentFile)
        val partial = File.createTempFile(".${target.name}.", ".part", directory)
        try {
            partial.outputStream().use { output -> input.copyTo(output) }
            try {
                Files.move(partial.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            partial.delete()
        }
    }
}
