package io.wanbox.tests

import io.wanbox.domain.ByteFormat
import io.wanbox.domain.TrafficAggregator
import io.wanbox.domain.resetTraffic
import io.wanbox.domain.trafficReport
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/** 用例基线 UC-STAT-01/02 */
class StatsTest {

    @Test
    fun `UC-STAT-01 按小时分桶并汇总`() {
        runBlocking {
            val h = Harness(now = 0L)
            h.traffic.accumulate(rx = 1024, tx = 512, at = 0L)
            h.traffic.accumulate(rx = 1024, tx = 512, at = 3_600_000L)
            h.traffic.accumulate(rx = 2048, tx = 1024, at = 3_700_000L)

            val report = h.core.trafficReport("day")

            assertEquals(2, report.points.size, "同一小时内的两个采样要合并")
            assertEquals(4096L, report.totalRx)
            assertEquals(2048L, report.totalTx)
            assertEquals(3_600_000L, report.peakBucket!!.bucketStart)
            assertEquals("6.0 KB", report.unit)
        }
    }

    @Test
    fun `UC-STAT-01 周窗口按天分桶`() {
        runBlocking {
            val h = Harness(now = 0L)
            h.traffic.accumulate(rx = 1, tx = 1, at = 0L)
            h.traffic.accumulate(rx = 1, tx = 1, at = 3_600_000L)

            val report = h.core.trafficReport("week")

            assertEquals(1, report.points.size, "同一天内的采样应合成一个桶")
        }
    }

    @Test
    fun `UC-STAT-01 无数据时给出空序列而不是抛异常`() {
        runBlocking {
            val h = Harness()
            val report = h.core.trafficReport("day")
            assertEquals(0, report.points.size)
            assertEquals(0L, report.totalRx)
            assertEquals("0 B", report.unit)
            assertEquals(null, report.peakBucket)
        }
    }

    @Test
    fun `UC-STAT-02 清零后计数归零且历史清空`() {
        runBlocking {
            val h = Harness(now = 0L)
            h.traffic.accumulate(rx = 4096, tx = 2048, at = 0L)
            assertNotNull(h.core.trafficReport("day").peakBucket)

            h.core.resetTraffic(emptyList())

            assertEquals(0L, h.traffic.current.rxBytes)
            assertEquals(0L, h.traffic.current.txBytes)
            assertEquals(0, h.core.trafficReport("day").points.size)
        }
    }

    @Test
    fun `单位换算走 1024 进制`() {
        assertEquals("0 B", ByteFormat.human(0))
        assertEquals("1023 B", ByteFormat.human(1023))
        assertEquals("1.0 KB", ByteFormat.human(1024))
        assertEquals("1.5 KB", ByteFormat.human(1536))
        assertEquals("1.0 MB", ByteFormat.human(1024L * 1024))
        assertEquals("1.0 GB", ByteFormat.human(1024L * 1024 * 1024))
        assertEquals("1.0 TB", ByteFormat.human(1024L * 1024 * 1024 * 1024))
    }

    @Test
    fun `分桶粒度是纯函数且未知窗口有兜底`() {
        assertEquals(3_600_000L, TrafficAggregator.bucketSize("day"))
        assertEquals(86_400_000L, TrafficAggregator.bucketSize("week"))
        assertEquals(86_400_000L, TrafficAggregator.bucketSize("month"))
        assertEquals(3_600_000L, TrafficAggregator.bucketSize("whatever"))
    }
}
