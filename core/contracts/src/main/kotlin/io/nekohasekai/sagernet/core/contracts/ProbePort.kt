// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.core.contracts

/** 节点测速、URL 测试与落地 IP 查询。 */
interface ProbePort {
    suspend fun testNode(node: NodeRef, direction: SpeedTestDirection): SpeedTestOutcome

    suspend fun urlTest(node: NodeRef): UrlTestResult

    suspend fun landingIp(): LandingIpResult
}
