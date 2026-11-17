// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** B1/O2d + O2c 契约测试：纯节点切换走热切换通道，测速间隔默认 1000ms。@author 雾晚 */
class SwitchProfileHotSwitchTest {

    private fun sagerNet() = File("src/main/java/io/nekohasekai/sagernet/SagerNet.kt").readText()
    private fun switchActivity() = File("src/main/java/io/nekohasekai/sagernet/ui/SwitchActivity.kt").readText()
    private fun rootModuleClient() = File("src/main/java/io/nekohasekai/sagernet/bg/RootModuleClient.kt").readText()

    @Test fun switchProfileServiceChannelExists() {
        val source = sagerNet()
        assertTrue(source.contains("fun switchProfileService(profileId: Long)"))
        // 运行时走既有热切换通道
        assertTrue(source.contains("selectProfile(profileId)"))
        // 停止时保存选择并连接（保留快捷选择“停止时也能连上”的能力）
        assertTrue(source.contains("RootModuleClient.startOrReload()"))
        // 纯节点切换不得走完整配置重建
        val body = source.substringAfter("fun switchProfileService(profileId: Long)")
            .substringBefore("\n        fun ", "")
        assertFalse(body.contains("reloadService()"))
        assertFalse(body.contains("configurationReload.request()"))
    }

    @Test fun switchActivityUsesHotSwitchChannel() {
        val source = switchActivity()
        assertTrue(source.contains("SagerNet.switchProfileService(profileId)"))
        // 不再直接改 selectedProxy、也不再直接 startService（全量重建）
        assertFalse(source.contains("DataStore.selectedProxy = profileId"))
        assertFalse(source.contains("SagerNet.startService()"))
    }

    @Test fun toggleServiceHotSwitchesWhenRunningWithDifferentProfile() {
        val source = rootModuleClient()
        // 运行中切到另一节点：直接调 selector，不过 selectNode()（会重复加锁死锁）
        assertTrue(source.contains("call(\"node select \$profileId\")"))
        // 确认失败保持旧连接与旧选择，不静默回退到全量重建
        assertTrue(source.contains("node_switch_not_confirmed:\$profileId"))
        // 停止时仍走全量重建
        assertTrue(source.contains("startOrReloadLocked(startIfStopped = true, beforeStatus = status)"))
    }

    @Test fun speedIntervalDefaultsTo1000() {
        val source = File("src/main/java/io/nekohasekai/sagernet/database/DataStore.kt").readText()
        assertTrue(source.contains("var speedInterval by configurationStore.stringToInt(Key.SPEED_INTERVAL) { 1000 }"))
    }

    @Test fun configClassChangesStillRebuild() {
        // 配置类变更（路由规则、代理模式、分组设置）仍走配置重建：reloadService
        // 必须继续经过 CoalescedReload（全量 startOrReload），不得被热切换短路。
        val source = sagerNet()
        assertTrue(source.contains("fun reloadService() { configurationReload.request() }"))
    }
}
