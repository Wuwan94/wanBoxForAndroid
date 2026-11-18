package moe.matsuri.nb4a

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * B3/O6 核查结论：wanBox 规则模型不支持 local: 本地规则集
 *（无 RuleSetCatalog、无多目录解析、无 local: 标签模型）。
 * 此类条目在配置生成时即被明确拒绝，绝不静默当作远端规则集处理。
 */
class RulesetEntryTest {

    @Test
    fun rsipAndRssiteEntriesAreAccepted() {
        assertEquals(
            "https://example.com/geoip-cn.srs" to true,
            processRulesetUrl("rsip:https://example.com/geoip-cn.srs")
        )
        assertEquals(
            "https://example.com/geosite-cn.srs" to false,
            processRulesetUrl("rssite:https://example.com/geosite-cn.srs")
        )
    }

    @Test
    fun localRuleSetEntryIsRejectedNotSilentlyAccepted() {
        // JVM 单测里 SagerNet.application 未初始化，抛出的具体异常类型与真机上的
        // ruleset_prefix_error 不同，但“拒绝”这一契约相同。
        assertThrows(Exception::class.java) { processRulesetUrl("local:custom.srs") }
        assertThrows(Exception::class.java) { processRulesetUrl("local:geoip-lite.db") }
        assertThrows(Exception::class.java) { processRulesetUrl("geoip-cn.srs") }
    }
}
