package io.nekohasekai.sagernet.route

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.InflaterInputStream

/**
 * `.srs` 规则集缓存校验（B3/O6，借鉴 OwnBox 8.8 `RuleSets.isPopulatedSrsCache`，
 * 上游 commit d401e93）。
 *
 * 校验的是实际规则条数，不是文件存在与否：启动兜底生成的 0 规则空 SRS 占位
 * （`PlainRuleSet{}`）不是有效缓存，不得静默当作有效规则集使用。
 *
 * wanBox 当前 Kotlin 侧没有 `.srs` 缓存查找调用点（无 `RuleSetCatalog` /
 * `RuleSets`）：规则集下载与初始路径由核心内置更新器负责。本函数是前后端
 * 共用的校验定义（与 Go 侧 `isPopulatedSRSFile` 同一条判定：>128 字节直接
 * 放行，否则解析首个 varint 规则数），配单测；生效的校验在 Go 侧 `box.New`
 * 前的 `prepareRemoteRuleSets` / `convertGeoRuleSetToSRS`。
 */
object RuleSetCache {

    fun isPopulatedSrsCache(file: File): Boolean {
        if (!file.isFile) return false
        val len = file.length()
        if (len <= 0L) return false
        if (len > 128L) return true
        return runCatching {
            val bytes = file.readBytes()
            if (bytes.size >= 4 &&
                bytes[0] == 0x53.toByte() &&
                bytes[1] == 0x52.toByte() &&
                bytes[2] == 0x53.toByte()
            ) {
                InflaterInputStream(ByteArrayInputStream(bytes, 4, bytes.size - 4)).use { inflater ->
                    readUnsignedVarint(inflater) > 0L
                }
            } else {
                bytes.size > 2
            }
        }.getOrDefault(false)
    }

    private fun readUnsignedVarint(input: InputStream): Long {
        var value = 0L
        var shift = 0
        while (shift < 64) {
            val b = input.read()
            if (b < 0) return 0L
            value = value or ((b and 0x7F).toLong() shl shift)
            if ((b and 0x80) == 0) return value
            shift += 7
        }
        return 0L
    }
}
