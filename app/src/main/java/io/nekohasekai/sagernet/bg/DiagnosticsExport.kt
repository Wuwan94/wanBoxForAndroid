// @author 雾晚
package io.nekohasekai.sagernet.bg

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

// B5（N2）：诊断包导出目的地。默认系统 Download 目录，文件名固定为
// wanbox-diagnostics-<时间>.tar.gz，时间戳为 RFC 3339 本地时间转文件名
// 安全形式（冒号改连字符），不含设备标识与用户标识。
object DiagnosticsExport {
    const val FILE_PREFIX = "wanbox-diagnostics-"
    const val FILE_SUFFIX = ".tar.gz"

    private val fileNameFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ssXXX")

    fun fileName(timestamp: OffsetDateTime): String =
        FILE_PREFIX + fileNameFormatter.format(timestamp).replace(':', '-') + FILE_SUFFIX

    fun isDiagnosticsFileName(name: String): Boolean =
        name.startsWith(FILE_PREFIX) && name.endsWith(FILE_SUFFIX)

    @Suppress("DEPRECATION")
    fun defaultDownloadDir(): File =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    fun destinationFile(downloadDir: File, timestamp: OffsetDateTime): File =
        File(downloadDir, fileName(timestamp))

    // 模块以 root 写入 Download 后，媒体库不会自动感知；显式扫描一次，
    // 让 Android 12+ 的文件管理器与分享面板立即可见导出包。
    fun scanFile(context: Context, file: File) {
        MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            arrayOf("application/gzip"),
            null,
        )
    }
}
