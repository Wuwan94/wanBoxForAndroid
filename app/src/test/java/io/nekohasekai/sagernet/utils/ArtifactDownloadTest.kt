// @author 雾晚
package io.nekohasekai.sagernet.utils

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

class ArtifactDownloadTest {
    @Test
    fun failedTransferLeavesNoPartialAndPreservesPreviousCompleteFile() {
        val dir = Files.createTempDirectory("wanbox-download").toFile()
        try {
            val target = File(dir, "update.apk").apply { writeText("old verified download") }
            val broken = object : InputStream() {
                var count = 0
                override fun read(): Int {
                    if (count++ < 4) return 1
                    throw IOException("interrupted transfer")
                }
            }
            assertThrows(IOException::class.java) { ArtifactDownload.write(target, broken) }
            assertEquals("old verified download", target.readText())
            assertEquals(listOf("update.apk"), dir.list()!!.sorted())
            target.delete()
            assertThrows(IOException::class.java) { ArtifactDownload.write(target, broken) }
            assertFalse(target.exists())
            assertTrue(dir.list()!!.isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun completedTransferReplacesTargetWithoutResidue() {
        val dir = Files.createTempDirectory("wanbox-download").toFile()
        try {
            val target = File(dir, "module.zip").apply { writeText("old") }
            val expected = ByteArray(130_000) { (it % 251).toByte() }
            ByteArrayInputStream(expected).use { ArtifactDownload.write(target, it) }
            assertArrayEquals(expected, target.readBytes())
            assertEquals(listOf("module.zip"), dir.list()!!.sorted())
        } finally { dir.deleteRecursively() }
    }
}
