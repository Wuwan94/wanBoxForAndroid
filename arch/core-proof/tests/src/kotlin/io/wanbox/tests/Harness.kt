package io.wanbox.tests

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
import io.wanbox.contracts.dto.SubscriptionInfo
import io.wanbox.domain.AppCore
import java.util.Base64

/** 组装一份全内存的 core，供所有用例测试共用。 */
class Harness(
    now: Long = 1_000L,
    initialSubs: List<SubscriptionInfo> = emptyList(),
) {
    val profiles = InMemoryProfileRepository()
    val subs = InMemorySubscriptions(initialSubs, profiles)
    val routes = InMemoryRoutes()
    val settings = InMemorySettings()
    val clock = FixedClock(now)
    val traffic = InMemoryTraffic(clock)
    val files = InMemoryFileGateway(profiles)
    val logs = InMemoryLogGateway()
    val root = FakeRootModuleGateway()
    val storage = FakeStorage()
    val permissions = FakePermissions()
    val ports = RecordingPlatformPorts()
    val logger = BufferLogger()

    val core = AppCore(
        profiles = profiles,
        subscriptions = subs,
        routes = routes,
        settings = settings,
        traffic = traffic,
        files = files,
        logs = logs,
        root = root,
        storage = storage,
        permissions = permissions,
        ports = ports,
        clock = clock,
        logger = logger,
    )
}

object Links {
    fun base64(text: String): String =
        Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    fun base64Url(text: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray(Charsets.UTF_8))

    /** SIP002：userinfo = base64(method:password) */
    fun shadowsocks(host: String = "hk1.example", port: Int = 8388, name: String = "香港01"): String {
        val secret = base64Url("aes-256-gcm:pass")
        return "ss://$secret@$host:$port#$name"
    }

    fun vmess(host: String = "jp1.example", port: Int = 443, name: String = "日本01"): String {
        val json = """{"v":"2","ps":"$name","add":"$host","port":"$port",""" +
            """"id":"11111111-2222-3333-4444-555555555555","aid":"0","net":"ws","tls":"tls"}"""
        return "vmess://" + base64(json)
    }

    fun trojan(host: String = "tr.example", port: Int = 443, name: String = "东京01"): String =
        "trojan://pw@$host:$port?sni=$host#$name"
}
