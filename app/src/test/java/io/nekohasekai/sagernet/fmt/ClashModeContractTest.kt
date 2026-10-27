package io.nekohasekai.sagernet.fmt

import org.junit.Assert.assertEquals
import org.junit.Test

class ClashModeContractTest {

    @Test
    fun testClashModeStringOfMapping() {
        assertEquals("rule", clashModeStringOf(0))
        assertEquals("global", clashModeStringOf(1))
        assertEquals("direct", clashModeStringOf(2))

        // 未知值及异常输入严格回退为 "rule"
        assertEquals("rule", clashModeStringOf(-1))
        assertEquals("rule", clashModeStringOf(99))
    }
}
