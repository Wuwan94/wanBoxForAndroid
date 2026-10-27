package io.wanbox.cli

import io.wanbox.adapters.memory.BufferLogger
import io.wanbox.adapters.memory.FakePermissions
import io.wanbox.adapters.memory.FakeRootModuleGateway
import io.wanbox.adapters.memory.FakeStorage
import io.wanbox.adapters.memory.FixedClock
import io.wanbox.adapters.memory.InMemoryFileGateway
import io.wanbox.adapters.memory.InMemoryLogGateway
import io.wanbox.adapters.memory.InMemoryProfileRepository
import io.wanbox.adapters.memory.InMemoryRoutes
import io.wanbox.adapters.memory.InMemorySettings
import io.wanbox.adapters.memory.InMemorySubscriptions
import io.wanbox.adapters.memory.InMemoryTraffic
import io.wanbox.adapters.memory.RecordingPlatformPorts
import io.wanbox.contracts.dto.ConnectionState
import io.wanbox.contracts.dto.RouteRule
import io.wanbox.contracts.dto.StorageState
import io.wanbox.contracts.dto.SubscriptionInfo
import io.wanbox.contracts.dto.SubscriptionUpdate
import io.wanbox.domain.AppCore
import io.wanbox.domain.LinkCodec
import io.wanbox.domain.TrafficAggregator
import io.wanbox.domain.addSubscription
import io.wanbox.domain.checkAndRecoverStorage
import io.wanbox.domain.deleteProfiles
import io.wanbox.domain.exportConfig
import io.wanbox.domain.exportLogs
import io.wanbox.domain.importBackup
import io.wanbox.domain.importProfiles
import io.wanbox.domain.installRootModule
import io.wanbox.domain.migrateSettings
import io.wanbox.domain.resetTraffic
import io.wanbox.domain.rootCommand
import io.wanbox.domain.saveRouteRule
import io.wanbox.domain.setAutoStart
import io.wanbox.domain.setPerAppProxy
import io.wanbox.domain.setProxyMode
import io.wanbox.domain.trafficReport
import kotlinx.coroutines.runBlocking
import java.util.Base64
import kotlin.system.exitProcess

/**
 * 第二个入口：无 Android、无界面，跑一遍全部用例。
 * 这条能跑通，说明 core 没有被界面绑住（验收标准 5：同一份 core 能接出第二种界面）。
 */
fun main() = runBlocking {
    var failed = 0
    fun check(name: String, ok: Boolean, detail: String = "") {
        println((if (ok) "  PASS  " else "  FAIL  ") + name + if (detail.isEmpty()) "" else "  → $detail")
        if (!ok) failed++
    }

    val profiles = InMemoryProfileRepository()
    val subs = InMemorySubscriptions(
        listOf(
            SubscriptionInfo(1, "机场A", "https://a.example/sub", 0L, true, 60),
            SubscriptionInfo(2, "机场B", "https://b.example/sub", 99_999_999L, true, 60),
            SubscriptionInfo(3, "手动组", "https://c.example/sub", 0L, false, 60),
        ),
        profiles,
    )
    val routes = InMemoryRoutes()
    val settings = InMemorySettings()
    val clock = FixedClock(100_000_000L)
    val traffic = InMemoryTraffic(clock)
    val files = InMemoryFileGateway(profiles)
    val logs = InMemoryLogGateway()
    val root = FakeRootModuleGateway()
    val storage = FakeStorage()
    val permissions = FakePermissions()
    val ports = RecordingPlatformPorts()
    val logger = BufferLogger()

    val core = AppCore(
        profiles, subs, routes, settings, traffic, files, logs, root, storage,
        permissions, ports, clock, logger,
    )

    println("== UC-PROF-01 导入（ss / vmess / trojan，含去重与非法行） ==")
    val ssSecret = Base64.getUrlEncoder().withoutPadding()
        .encodeToString("aes-256-gcm:pass".toByteArray())
    val vmessJson = """{"v":"2","ps":"日本01","add":"jp1.example","port":"443",""" +
        """"id":"11111111-2222-3333-4444-555555555555","aid":"0","net":"ws","tls":"tls"}"""
    val batch = listOf(
        "ss://$ssSecret@hk1.example:8388#香港01",
        "vmess://" + Base64.getEncoder().encodeToString(vmessJson.toByteArray()),
        "trojan://pw@tr.example:443?sni=tr.example#东京01",
        "this-is-not-a-link",
    ).joinToString("\n")
    val imported = core.importProfiles(batch)
    check("导入 3 条（实得 ${imported.imported}）", imported.imported == 3)
    check("非法行进入 errors（${imported.errors.size} 条）", imported.errors.size == 1)
    val again = core.importProfiles("ss://$ssSecret@hk1.example:8388#香港01")
    check("重复导入被跳过（skipped=${again.skipped}）", again.imported == 0 && again.skipped == 1)

    println("== UC-CONN-01 连接 ==")
    val connected = core.connect()
    check("状态进入 Connected", connected is ConnectionState.Connected)
    check("选中节点已落库设置", core.snapshot.value.settings.selectedProfileId != null)
    check("通知/磁贴/小组件收到同一份快照", ports.notifications.lastOrNull() === ports.widgets.lastOrNull())

    println("== UC-CONN-03 热切换 ==")
    val jpId = core.snapshot.value.profiles.first { it.server == "jp1.example" }.id
    val switched = core.switchTo(jpId)
    check(
        "切换后仍 Connected 且节点已变",
        switched is ConnectionState.Connected && switched.profileId == jpId,
    )

    println("== UC-CONN-02 断开 ==")
    val idle = core.disconnect()
    check("回到 Idle", idle is ConnectionState.Idle)
    check("本次流量已落库（persist=${traffic.persistCalls}）", traffic.persistCalls >= 1)

    println("== UC-ROUTE-01 分流规则 ==")
    val rules = core.saveRouteRule(RouteRule(1, true, "geosite:cn", jpId, 0))
    check("规则已写入（${rules.size} 条）", rules.size == 1)

    println("== UC-APP-01 分应用代理 ==")
    val apps = core.setPerAppProxy(listOf(" com.a ", "com.b", "com.a"), "include")
    check("选择集归一化（$apps）", apps == listOf("com.a", "com.b"))

    println("== UC-NET-01 代理模式（连接中重连） ==")
    core.connect(jpId)
    val before = traffic.persistCalls
    val snapAfterMode = core.setProxyMode("tun")
    check("模式已切换为 tun", snapAfterMode.proxyMode == "tun")
    check("重连触发了一次落库", traffic.persistCalls > before)

    println("== UC-SUB-01 添加订阅 ==")
    subs.enqueue(SubscriptionUpdate(1, added = 2))
    val sub = core.addSubscription("https://d.example/sub", "机场D")
    check("添加订阅后立即拉取（分组 ${sub.groupId}）", subs.updateCalls.isNotEmpty())

    println("== UC-DATA-01/02 导出与导入 ==")
    val bundle = core.exportConfig(includePrivate = true)
    val text = String(bundle.bytes)
    check("导出文件带文件头", text.startsWith("wanbox-backup"))
    val restored = core.importBackup(text)
    check("导出的内容能被导入链路读回（+${restored.imported}）", restored.imported >= 1)
    val rejected = core.importBackup("garbage")
    check("损坏备份被拒绝且未写入", !rejected.ok && files.importCalls == 1)

    println("== UC-LOG-01 日志脱敏 ==")
    val redacted = core.exportLogs("info")
    check("password 已脱敏", !redacted.content.contains("hunter2"))
    check("Bearer 已脱敏", !redacted.content.contains("abc.def.ghi"))
    check("命中条数 > 0（${redacted.redactedCount}）", redacted.redactedCount > 0)

    println("== UC-STAT-01/02 流量 ==")
    traffic.accumulate(rx = 4096, tx = 1024, at = 100_000_000L, profileId = jpId)
    val report = core.trafficReport("day")
    check("聚合总量正确（${report.totalRx}/${report.totalTx}）", report.totalRx >= 4096)
    check("单位可读（${report.unit}）", report.unit.endsWith("B"))
    core.resetTraffic(emptyList())
    check("清零生效", traffic.current.rxBytes == 0L)

    println("== UC-DATA-03 存储损坏恢复 ==")
    storage.state = StorageState.Corrupted
    check("损坏后可恢复", core.checkAndRecoverStorage())

    println("== UC-DATA-04 设置迁移 ==")
    val migrated = core.migrateSettings(mapOf("proxy_mode" to "proxy", "auto_start" to "1"))
    check("迁移了 2 个键", migrated == 2)
    check("迁移后 autoStart=true", settings.value.autoStart)
    val bad = core.migrateSettings(mapOf("proxy_mode" to "bogus"))
    check("非法值拒绝迁移且不改设置", bad == 0 && settings.value.proxyMode == "proxy")

    println("== UC-SYS-01 权限 ==")
    permissions.vpn = false
    core.disconnect()
    val denied = core.connect()
    check("拒绝 VPN 权限时保持 Idle", denied is ConnectionState.Idle)

    println("== UC-SYS-02 开机自启 ==")
    permissions.vpn = true
    core.setAutoStart(false)
    core.disconnect()
    check("关闭自启则保持 Idle", core.onBoot() is ConnectionState.Idle)
    core.setAutoStart(true)
    check("开启自启则自动连接", core.onBoot() is ConnectionState.Connected)

    println("== UC-SYS-03 小组件动作 ==")
    core.disconnect()
    check("toggle 从 Idle 变 Connected", core.onPlatformAction("toggle") is ConnectionState.Connected)
    check("toggle 再点回 Idle", core.onPlatformAction("toggle") is ConnectionState.Idle)

    println("== UC-ROOT-01 root 模块 ==")
    check("白名单命令放行", core.rootCommand("cat /data/adb/modules/wanbox/module.prop") != null)
    check("注入命令被拒", core.rootCommand("cat /data/adb/modules/wanbox/module.prop; rm -rf /") == null)
    check("安装返回版本", core.installRootModule("bundle").version == "1.2.3")

    println("== UC-PROF-04 删除级联 ==")
    val deleted = core.deleteProfiles(core.snapshot.value.profiles.map { it.id })
    check("节点全部删除（$deleted）", deleted > 0)
    check("指向被删节点的规则出站已清空", routes.all.all { it.outboundProfileId == null })

    println("== LinkCodec 编解码 ==")
    val trojan = LinkCodec.parse("trojan://pw@tr.example:443?sni=tr.example#东京")
    check("trojan 解析", trojan != null && trojan.fields["password"] == "pw")
    val roundTrip = trojan?.let { LinkCodec.toUri(it) }?.let { LinkCodec.parse(it) }
    check("导出后可再解析回同一主机", roundTrip?.server == "tr.example")

    println("== 单一状态源 / 纯函数 ==")
    val snap = core.snapshot.value
    check(
        "快照与控制器一致",
        (snap.connection is ConnectionState.Idle) == (core.connection.value is ConnectionState.Idle),
    )
    check("窗口分桶是纯函数", TrafficAggregator.bucketSize("week") == 86_400_000L)

    println()
    if (failed == 0) {
        println("CLI: ALL PASS")
    } else {
        println("CLI: $failed FAILED")
        exitProcess(1)
    }
}
