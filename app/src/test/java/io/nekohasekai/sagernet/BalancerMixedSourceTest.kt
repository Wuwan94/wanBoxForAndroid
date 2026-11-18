// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet

import com.esotericsoftware.kryo.io.ByteBufferInput
import io.nekohasekai.sagernet.fmt.BalancerMemberRef
import io.nekohasekai.sagernet.fmt.BalancerMemberResolution
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.balancerRegexError
import io.nekohasekai.sagernet.fmt.internal.BalancerBean
import io.nekohasekai.sagernet.fmt.resolveBalancerMembers
import io.nekohasekai.sagernet.ktx.byteBuffer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** B7a/O7 混搭来源 + 组隔离 + BalancerBean v5→v6 测试。@author 雾晚 */
class BalancerMixedSourceTest {

    private fun groupMembers(vararg pairs: Pair<Long, List<BalancerMemberRef>>): (Long) -> List<BalancerMemberRef> {
        val map = pairs.toMap()
        return { gid -> map[gid] ?: emptyList() }
    }

    private fun mixedBean() = BalancerBean().apply {
        balancerType = BalancerBean.TYPE_MIXED
        targetGroupIds = listOf(11L, 12L)
        proxies = listOf(4L, 2L)
    }

    @Test fun mixedSourcesDedupGroupFirstLooseAfter() {
        val bean = mixedBean()
        val resolution = resolveBalancerMembers(
            bean,
            groupMembers(
                11L to listOf(BalancerMemberRef(1L, "HK-1"), BalancerMemberRef(2L, "HK-2")),
                12L to listOf(BalancerMemberRef(2L, "HK-2"), BalancerMemberRef(3L, "JP-1")),
            ),
        )
        // 分组在前、散装在后；2 号在分组与散装各出现一次，去重后只剩一个。
        assertEquals(listOf(1L, 2L, 3L, 4L), resolution.memberIds)
        assertEquals(4, resolution.groupMatched) // 去重前：2 + 2
        assertEquals(1, resolution.looseMatched) // 散装 4、2，只追加了 4
        assertEquals(2, resolution.duplicatesRemoved) // 组内重复 2 + 散装重复 2
        assertFalse(resolution.hasRegexError)
    }

    @Test fun regexIncludeExcludeOnlyApplyToGroupSource() {
        val bean = mixedBean().apply {
            nameInclude = "^HK"
            nameExclude = "2$"
        }
        val resolution = resolveBalancerMembers(
            bean,
            groupMembers(
                11L to listOf(BalancerMemberRef(1L, "HK-1"), BalancerMemberRef(2L, "HK-2")),
                12L to listOf(BalancerMemberRef(3L, "JP-1")),
            ),
        )
        // 分组来源：^HK 留下 1、2，再排除 2$ 剩 1；散装 4、2 不受正则影响，
        // 其中 2 追加（分组里已被排除，不算重复）。
        assertEquals(listOf(1L, 4L, 2L), resolution.memberIds)
        assertEquals(1, resolution.groupMatched)
        assertEquals(2, resolution.looseMatched)
    }

    @Test fun invalidRegexReportedAndFallsBackToUnfiltered() {
        val bean = mixedBean().apply { nameInclude = "[invalid" }
        val resolution = resolveBalancerMembers(
            bean,
            groupMembers(11L to listOf(BalancerMemberRef(1L, "HK-1"))),
        )
        assertNotNull(resolution.includeRegexError)
        assertTrue(resolution.hasRegexError)
        // 配置生成不丢成员：失效的过滤条件按未设置处理（与既有 ConfigBuilder 行为一致）。
        assertEquals(listOf(1L, 4L, 2L), resolution.memberIds)
        val error = balancerRegexError("[invalid")
        assertNotNull(error)
        assertTrue(error!!.contains("[invalid"))
        assertNull(balancerRegexError("^HK"))
        assertNull(balancerRegexError(""))
        assertNull(balancerRegexError(null))
    }

    @Test fun maxMembersCapsAfterDedupAndZeroMeansUnlimited() {
        val groups = groupMembers(
            11L to listOf(BalancerMemberRef(1L, "A"), BalancerMemberRef(2L, "B")),
            12L to listOf(BalancerMemberRef(3L, "C")),
        )
        val capped = mixedBean().apply { maxMembers = 2 }
        assertEquals(listOf(1L, 2L), resolveBalancerMembers(capped, groups).memberIds)

        val unlimited = mixedBean().apply { maxMembers = 0 }
        assertEquals(listOf(1L, 2L, 3L, 4L), resolveBalancerMembers(unlimited, groups).memberIds)

        // 防御性：未经 initializeDefaultValues 的负值也不得截断成空。
        val negative = mixedBean().apply { maxMembers = -5 }
        assertEquals(listOf(1L, 2L, 3L, 4L), resolveBalancerMembers(negative, groups).memberIds)
    }

    @Test fun listTypeIgnoresGroupsAndGroupTypeIgnoresLoose() {
        val groups = groupMembers(11L to listOf(BalancerMemberRef(1L, "A")))
        val listBean = BalancerBean().apply {
            balancerType = BalancerBean.TYPE_LIST
            targetGroupIds = listOf(11L)
            proxies = listOf(4L, 4L, 5L)
        }
        val listResolution: BalancerMemberResolution = resolveBalancerMembers(listBean, groups)
        assertEquals(listOf(4L, 5L), listResolution.memberIds)
        assertEquals(0, listResolution.groupMatched)
        assertEquals(1, listResolution.duplicatesRemoved)

        val groupBean = BalancerBean().apply {
            balancerType = BalancerBean.TYPE_GROUP
            targetGroupIds = listOf(11L)
            proxies = listOf(4L)
        }
        val groupResolution = resolveBalancerMembers(groupBean, groups)
        assertEquals(listOf(1L), groupResolution.memberIds)
        assertEquals(0, groupResolution.looseMatched)
    }

    @Test fun emptySourcesResolveToEmpty() {
        // B7c 修：原用例沿用 mixedBean（含散装 proxies=[4,2]），MIXED 下散装节点
        // 本就独立解析（见 mixedSourcesDedupGroupFirstLooseAfter），空分组时仍应
        // 返回散装成员；测“空来源”须用无散装节点的 bean。
        val bean = BalancerBean().apply {
            balancerType = BalancerBean.TYPE_MIXED
            targetGroupIds = listOf(11L, 12L)
            proxies = emptyList()
        }
        val resolution = resolveBalancerMembers(bean) { emptyList() }
        assertEquals(emptyList<Long>(), resolution.memberIds)
        assertEquals(0, resolution.totalMatched)
    }

    // ---- BalancerBean 记录版本 v5 → v6 ----

    /** 按 v5 serialize 的字段顺序手写一份历史 payload（与新代码无关的独立副本）。 */
    private fun buildV5Payload(): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = out.byteBuffer()
        buffer.writeInt(5) // version
        buffer.writeInt(BalancerBean.TYPE_GROUP)
        buffer.writeLong(11L) // targetGroupId
        buffer.writeString(BalancerBean.STRATEGY_LEAST_PING)
        buffer.writeString("http://example.invalid/204")
        buffer.writeInt(300) // interval
        buffer.writeInt(2)
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
        // AbstractBean 附加段
        buffer.writeInt(1)
        buffer.writeString("Old Group")
        buffer.writeString("")
        buffer.writeString("")
        buffer.flush()
        buffer.close()
        return out.toByteArray()
    }

    @Test fun v5HistoryPayloadReadsAndUpgradesWithSafeDefaults() {
        val bean = KryoConverters.deserialize(BalancerBean(), buildV5Payload())
        assertNotNull(bean)
        bean!!
        assertEquals(BalancerBean.TYPE_GROUP, bean.balancerType)
        assertEquals(11L, bean.targetGroupId)
        assertEquals(listOf(11L), bean.targetGroupIds)
        assertEquals(BalancerBean.STRATEGY_LEAST_PING, bean.strategy)
        assertEquals("http://example.invalid/204", bean.testUrl)
        assertEquals(300, bean.interval)
        assertEquals(listOf(4L, 5L), bean.proxies)
        assertEquals(300, bean.tolerance)
        assertEquals("ms", bean.toleranceUnit)
        assertFalse(bean.useFrontProxy)
        assertTrue(bean.useLandingProxy)
        assertEquals("^HK", bean.nameInclude)
        assertEquals(-1L, bean.frontProxy)
        assertEquals(7L, bean.landingProxy)
        assertEquals("Old Group", bean.name)
        // v5 无 maxMembers：升级后取 0 = 不限。
        assertEquals(0, bean.maxMembers)
    }

    @Test fun v6RoundTripPreservesNewFields() {
        val bean = mixedBean().apply {
            maxMembers = 5
            strategy = BalancerBean.STRATEGY_LEAST_PING
            interval = 300
            name = "Mixed 01"
        }
        val restored = KryoConverters.deserialize(BalancerBean(), KryoConverters.serialize(bean))
        assertNotNull(restored)
        restored!!
        assertEquals(BalancerBean.TYPE_MIXED, restored.balancerType)
        assertEquals(5, restored.maxMembers)
        assertEquals(listOf(11L, 12L), restored.targetGroupIds)
        assertEquals(listOf(4L, 2L), restored.proxies)
        assertEquals("Mixed 01", restored.name)
    }

    @Test fun serializeWritesVersion7() {
        // B7c/O7：记录已升至 v7（层级规则 + 节点优先级顺序 + 排除名单），
        // v6 字段仍在 v7 payload 里原样保留（见 BalancerTierPersistenceTest）。
        val bytes = KryoConverters.serialize(BalancerBean())
        val version = ByteBufferInput(ByteBuffer.wrap(bytes)).readInt()
        assertEquals(7, version)
    }

    @Test fun zeroUnlimitedNotOverwrittenByDefaults() {
        val bean = BalancerBean().apply {
            // 0（不限）是显式值：tolerance 0 = 严格切换、maxMembers 0 = 不限，
            // initializeDefaultValues MUST NOT 把它们吃掉。
            tolerance = 0
            maxMembers = 0
            frontProxy = 0L // 0 不是合法 profile id，归一化为 -1（无）不算丢数据
        }
        bean.initializeDefaultValues()
        assertEquals(0, bean.tolerance)
        assertEquals(0, bean.maxMembers)
        assertEquals(-1L, bean.frontProxy)
        // interval 的 0 无“不限”语义（探测周期），既有行为是回填 300，钉住它以防误改。
        val intervalBean = BalancerBean().apply { interval = 0 }
        intervalBean.initializeDefaultValues()
        assertEquals(300, intervalBean.interval)

        val negativeBean = BalancerBean().apply { maxMembers = -3 }
        negativeBean.initializeDefaultValues()
        assertEquals(0, negativeBean.maxMembers)
    }

    @Test fun downgradeToV5BytesReadsAsV5WithResolvedMembers() {
        val bean = mixedBean().apply {
            maxMembers = 2
            strategy = BalancerBean.STRATEGY_LEAST_PING
            interval = 300
            tolerance = 100
            landingProxy = 7L
            name = "Mixed 01"
        }
        // 调用方先解析再降级：上限已 baked 进成员列表，降级本身不再丢字段。
        val resolved = listOf(1L, 2L)
        val v5bytes = bean.downgradeToV5Bytes(resolved)
        val version = ByteBufferInput(ByteBuffer.wrap(v5bytes)).readInt()
        assertEquals(5, version)

        val restored = KryoConverters.deserialize(BalancerBean(), v5bytes)
        assertNotNull(restored)
        restored!!
        assertEquals(BalancerBean.TYPE_LIST, restored.balancerType)
        assertEquals(resolved, restored.proxies)
        assertEquals(BalancerBean.STRATEGY_LEAST_PING, restored.strategy)
        assertEquals(300, restored.interval)
        assertEquals(100, restored.tolerance)
        assertEquals(7L, restored.landingProxy)
        assertEquals("Mixed 01", restored.name)
        assertEquals(0, restored.maxMembers) // v5 无此字段，读回为默认值
        assertEquals("[节点 (2)] 策略: 最低延迟", restored.displayAddress())
    }

    @Test fun mixedDisplayAddressShowsGroupsAndLooseCounts() {
        val bean = mixedBean()
        assertEquals("[混合 (分组 2 + 节点 2)] 策略: 最低延迟", bean.displayAddress())
    }

    // ---- 配置生成层接线（源码级契约，完整 buildChain 需 DB，待 CI 单测验证） ----

    @Test fun configBuilderUsesResolverAndPerBalancerIsolation() {
        val source = java.io.File(
            "src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt"
        ).readText()
        // 成员展开走解析层，不再由 buildChain 内联两套分支。
        assertTrue(source.contains("resolveBalancerMembers(balancerBean"))
        // 组隔离：带上下文的成员链不再共用 tagMap 的 -member.id 槽位，
        // 改走以 (balancerId, memberId) 为键的隔离缓存。
        assertTrue(source.contains("balancerScopedTagMap"))
        assertFalse(source.contains("tagMap[customKey]"))
        // 链级隔离：带组 detour 的构建绕过 globalOutbounds 共享出口跳点
        //（否则第二组的 detour 被丢弃），中间跳点与出口 tag 加组后缀防重复；
        // 落地链的 detour 就是本组 tag，同样隔离构建。
        assertTrue(source.contains("isolationKey"))
        assertTrue(source.contains("needGlobal && isolationKey == null"))
        assertTrue(source.contains("\"g-\${proxyEntity.id}-\$isolationKey\""))
        // 混搭类型的前置/落地开关与分组类型一致（不再只认 TYPE_GROUP）。
        assertTrue(source.contains("balancerType != BalancerBean.TYPE_LIST"))
    }

    @Test fun settingsActivityWiresMixedEditingAndRegexGuard() {
        val source = java.io.File(
            "src/main/java/io/nekohasekai/sagernet/ui/profile/BalancerSettingsActivity.kt"
        ).readText()
        // 混搭编辑：分组选择 + 正则 + 散装节点，匹配计数实时展示。
        assertTrue(source.contains("updateMemberCount"))
        assertTrue(source.contains("resolveBalancerMembers("))
        // 正则语法错误阻止保存。
        assertTrue(source.contains("balancerRegexError("))
        assertTrue(source.contains("override suspend fun saveAndExit()"))
    }

    @Test fun editorReferenceGraphCoversMixedChildren() {
        // 编辑器校验的引用图：混搭策略组的分组边与散装节点边都要进图，
        // 否则经由散装成员的环引用检查不到（完整 validate 需 Room，待 CI/真机验证）。
        val source = java.io.File(
            "src/main/java/io/nekohasekai/sagernet/group/editor/GroupEditorService.kt"
        ).readText()
        assertTrue(source.contains("BalancerBean.TYPE_MIXED"))
        assertTrue(source.contains("EntityId.Group(it)"))
        assertTrue(source.contains("balancer.proxies.map { EntityId.Node(it) }"))
    }
}
