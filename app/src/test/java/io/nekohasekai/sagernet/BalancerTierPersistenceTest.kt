// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet

import com.esotericsoftware.kryo.io.ByteBufferInput
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.internal.BalancerBean
import io.nekohasekai.sagernet.ktx.byteBuffer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** B7c/O7 BalancerBean 记录版本 v6→v7（层级规则 + 节点优先级顺序 + 排除名单）测试。@author 雾晚 */
class BalancerTierPersistenceTest {

    /** 按 v6 serialize 的字段顺序手写一份历史 payload（与新代码无关的独立副本）。 */
    private fun buildV6Payload(): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = out.byteBuffer()
        buffer.writeInt(6) // version
        buffer.writeInt(BalancerBean.TYPE_MIXED)
        buffer.writeLong(11L) // targetGroupId
        buffer.writeString(BalancerBean.STRATEGY_LEAST_PING)
        buffer.writeString("http://example.invalid/204")
        buffer.writeInt(300) // interval
        buffer.writeInt(2) // proxies
        buffer.writeLong(4L)
        buffer.writeLong(5L)
        buffer.writeInt(1) // targetGroupIds
        buffer.writeLong(11L)
        buffer.writeInt(300) // tolerance
        buffer.writeString("ms")
        buffer.writeBoolean(false) // useFrontProxy
        buffer.writeBoolean(true) // useLandingProxy
        buffer.writeString("") // nameExclude
        buffer.writeString("^HK") // nameInclude
        buffer.writeLong(-1L) // frontProxy
        buffer.writeLong(7L) // landingProxy
        buffer.writeInt(5) // v6 maxMembers
        // AbstractBean 附加段
        buffer.writeInt(1)
        buffer.writeString("Mixed 01")
        buffer.writeString("")
        buffer.writeString("")
        buffer.flush()
        buffer.close()
        return out.toByteArray()
    }

    @Test fun v6HistoryPayloadReadsAndUpgradesWithSafeDefaults() {
        val bean = KryoConverters.deserialize(BalancerBean(), buildV6Payload())
        assertNotNull(bean)
        bean!!
        assertEquals(BalancerBean.TYPE_MIXED, bean.balancerType)
        assertEquals(BalancerBean.STRATEGY_LEAST_PING, bean.strategy)
        assertEquals(5, bean.maxMembers)
        assertEquals("Mixed 01", bean.name)
        // v6 无 v7 字段：升级后取安全默认值（空规则 = 只有保底层级、
        // 空顺序 = 解析顺序、空排除名单），旧字段原值保留。
        assertEquals("", bean.tierRules)
        assertEquals(emptyList<Long>(), bean.priorityOrder)
        assertEquals(emptyList<Long>(), bean.priorityExcluded)
    }

    @Test fun v7RoundTripPreservesTierFields() {
        val bean = BalancerBean().apply {
            balancerType = BalancerBean.TYPE_MIXED
            targetGroupIds = listOf(11L, 12L)
            proxies = listOf(4L, 2L)
            strategy = BalancerBean.STRATEGY_PRIORITY_ORDER
            tierRules = "Xkg6Sg:;Xlhw:Mg=="
            priorityOrder = listOf(2L, 4L, 1L)
            priorityExcluded = listOf(9L)
            maxMembers = 5
            name = "Priority 01"
        }
        val restored = KryoConverters.deserialize(BalancerBean(), KryoConverters.serialize(bean))
        assertNotNull(restored)
        restored!!
        assertEquals("Xkg6Sg:;Xlhw:Mg==", restored.tierRules)
        assertEquals(listOf(2L, 4L, 1L), restored.priorityOrder)
        assertEquals(listOf(9L), restored.priorityExcluded)
        assertEquals(5, restored.maxMembers)
        assertEquals("Priority 01", restored.name)
        assertEquals(BalancerBean.STRATEGY_PRIORITY_ORDER, restored.strategy)
    }

    @Test fun serializeWritesVersion7() {
        val bytes = KryoConverters.serialize(BalancerBean())
        val version = ByteBufferInput(ByteBuffer.wrap(bytes)).readInt()
        assertEquals(7, version)
    }

    @Test fun tierFieldDefaultsNotOverwritten() {
        val bean = BalancerBean().apply {
            tierRules = ""
            priorityOrder = ArrayList()
            priorityExcluded = ArrayList()
        }
        bean.initializeDefaultValues()
        assertEquals("", bean.tierRules)
        assertEquals(emptyList<Long>(), bean.priorityOrder)
        assertEquals(emptyList<Long>(), bean.priorityExcluded)

        // 空值（老数据反序列化后的 null）归一化为空，不留 null。
        val nullBean = BalancerBean().apply {
            tierRules = null
            priorityOrder = null
            priorityExcluded = null
        }
        nullBean.initializeDefaultValues()
        assertEquals("", nullBean.tierRules)
        assertNotNull(nullBean.priorityOrder)
        assertNotNull(nullBean.priorityExcluded)
    }

    @Test fun priorityDisplayAddress() {
        val bean = BalancerBean().apply {
            balancerType = BalancerBean.TYPE_LIST
            proxies = listOf(1L, 2L)
            strategy = BalancerBean.STRATEGY_PRIORITY_ORDER
        }
        assertEquals("[节点 (2)] 策略: 按优先级顺序", bean.displayAddress())
    }

    @Test fun downgradeToV6BytesReadsAsV6WithResolvedMembers() {
        val bean = BalancerBean().apply {
            balancerType = BalancerBean.TYPE_MIXED
            targetGroupIds = listOf(11L)
            proxies = listOf(4L, 2L)
            strategy = BalancerBean.STRATEGY_PRIORITY_ORDER
            interval = 300
            tolerance = 100
            maxMembers = 2
            tierRules = "Xkg6Sg:;"
            priorityOrder = listOf(2L, 4L)
            priorityExcluded = listOf(9L)
            name = "Priority 01"
        }
        // 调用方先解析再降级：层级与顺序已 baked 进成员列表，降级本身不再丢字段；
        // v7 专属字段（规则串、顺序、排除名单）在 v6 读端取默认值。
        val resolved = listOf(2L, 4L)
        val v6bytes = bean.downgradeToV6Bytes(resolved)
        val version = ByteBufferInput(ByteBuffer.wrap(v6bytes)).readInt()
        assertEquals(6, version)

        val restored = KryoConverters.deserialize(BalancerBean(), v6bytes)
        assertNotNull(restored)
        restored!!
        assertEquals(BalancerBean.TYPE_LIST, restored.balancerType)
        assertEquals(resolved, restored.proxies)
        assertEquals(BalancerBean.STRATEGY_PRIORITY_ORDER, restored.strategy)
        assertEquals(300, restored.interval)
        assertEquals(100, restored.tolerance)
        assertEquals(2, restored.maxMembers)
        assertEquals("Priority 01", restored.name)
        assertEquals("", restored.tierRules)
        assertEquals(emptyList<Long>(), restored.priorityOrder)
        assertEquals(emptyList<Long>(), restored.priorityExcluded)
    }

    @Test fun downgradeToV5BytesStillWorksFromV7() {
        val bean = BalancerBean().apply {
            balancerType = BalancerBean.TYPE_MIXED
            strategy = BalancerBean.STRATEGY_PRIORITY_ORDER
            interval = 300
            tolerance = 100
            landingProxy = 7L
            maxMembers = 2
            tierRules = "Xkg6Sg:;"
            priorityOrder = listOf(2L, 4L)
            name = "Priority 01"
        }
        val v5bytes = bean.downgradeToV5Bytes(listOf(1L, 2L))
        val version = ByteBufferInput(ByteBuffer.wrap(v5bytes)).readInt()
        assertEquals(5, version)

        val restored = KryoConverters.deserialize(BalancerBean(), v5bytes)
        assertNotNull(restored)
        restored!!
        assertEquals(BalancerBean.TYPE_LIST, restored.balancerType)
        assertEquals(listOf(1L, 2L), restored.proxies)
        assertEquals(0, restored.maxMembers)
        assertEquals("", restored.tierRules)
        assertEquals(emptyList<Long>(), restored.priorityOrder)
        assertEquals(emptyList<Long>(), restored.priorityExcluded)
    }
}
