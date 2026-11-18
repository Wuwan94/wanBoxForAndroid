package io.nekohasekai.sagernet.route

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.DeflaterOutputStream

/**
 * B3/O6：.srs 规则集缓存按实际规则条数校验（借鉴 OwnBox 8.8
 * RuleSets.isPopulatedSrsCache，上游 commit d401e93；wanBox 无 RuleSetCatalog，
 * 故只落校验定义本身，不含 findCachedSrsFile）。
 * 0 规则占位文件不是有效缓存。
 */
class RuleSetCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** 与上游 RuleSetLocalAndCacheTest 相同的最小二进制 SRS 构造：magic + 版本 + deflate(varint 规则数)。 */
    private fun buildBinarySrs(ruleCount: Int): ByteArray {
        return ByteArrayOutputStream().apply {
            write(byteArrayOf(0x53, 0x52, 0x53, 0x03))
            DeflaterOutputStream(this).use { deflater ->
                deflater.write(ruleCount)
                if (ruleCount > 0) {
                    deflater.write(byteArrayOf(0x00, 0x01, 0x02))
                }
            }
        }.toByteArray()
    }

    @Test
    fun emptySrsPlaceholderIsNotTreatedAsPopulatedCache() {
        val srsDir = tempFolder.newFolder("srs")
        val placeholderFile = File(srsDir, "geosite-cn.srs")
        placeholderFile.writeBytes(buildBinarySrs(ruleCount = 0))

        assertTrue(placeholderFile.length() > 0L)
        assertFalse(RuleSetCache.isPopulatedSrsCache(placeholderFile))
    }

    @Test
    fun populatedSrsCacheIsRecognized() {
        val srsDir = tempFolder.newFolder("srs2")
        val populated = File(srsDir, "geoip-cn.srs")
        populated.writeBytes(buildBinarySrs(ruleCount = 1))

        assertTrue(RuleSetCache.isPopulatedSrsCache(populated))
    }

    @Test
    fun missingAndEmptyFilesAreNotPopulated() {
        val srsDir = tempFolder.newFolder("srs3")
        assertFalse(RuleSetCache.isPopulatedSrsCache(File(srsDir, "missing.srs")))
        val zero = File(srsDir, "zero.srs").apply { writeBytes(ByteArray(0)) }
        assertFalse(RuleSetCache.isPopulatedSrsCache(zero))
    }

    @Test
    fun largeFilesUseFastPath() {
        val srsDir = tempFolder.newFolder("srs4")
        val large = File(srsDir, "large.srs").apply { writeBytes(ByteArray(129)) }
        assertTrue(RuleSetCache.isPopulatedSrsCache(large))
    }
}
