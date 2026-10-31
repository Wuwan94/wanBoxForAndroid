package io.nekohasekai.sagernet.core

import android.util.Log
import io.nekohasekai.sagernet.BuildConfig

/**
 * @author 雾晚 (B 路线 · 核心只随模块分发)
 *
 * 瘦管理器构建（`-PwanboxThin=true`）不含 `lib/libgojni.so`，任何 `go.Seq` / `libcore.Libcore`
 * 调用都会在类初始化阶段 `UnsatisfiedLinkError`。所有对内嵌核心的调用必须先经过这里。
 *
 * 约定：
 * - `available == true`：行为与历史版本完全一致（全功能 APK，可免 root 单机运行）。
 * - `available == false`：核心只存在于 root 模块里，管理器通过 wanboxctl / 回环 HTTP 驱动它；
 *   与本地核心强耦合的功能（单机 VPN、本地测速、STUN 探测、规则本地校验、进程内 HTTP 客户端）
 *   必须降级并给出明确提示，绝不能落到 libcore 上。
 *
 * 静态守卫：`tools/check_core_gate.py` 会拦截新增的裸调用。
 */
object CoreRuntime {

    /** 本次构建是否携带内嵌核心。由构建期 `BuildConfig.WITH_CORE` 决定。 */
    val available: Boolean = BuildConfig.WITH_CORE

    private const val TAG = "wanBox"

    /**
     * 核心日志通道。瘦版回退到 logcat，避免"日志凭空消失"。
     * 级别语义与 ConfigBuilder 的 sing-box log.level 一致，由调用方门控。
     */
    fun log(message: String) {
        if (available) {
            runCatching { libcore.Libcore.nekoLogPrintln(message) }
            return
        }
        Log.println(Log.INFO, TAG, message)
    }

    /** 清空核心日志缓冲；瘦版无缓冲可清。 */
    fun clearLog() {
        if (available) runCatching { libcore.Libcore.nekoLogClear() }
    }

    /** 断开所有已建立连接（重置出站）。瘦版由模块侧负责，管理器只发命令。 */
    fun resetAllConnections() {
        if (available) {
            runCatching { libcore.Libcore.resetAllConnections(true) }
        }
    }

    /**
     * HTTP 客户端。**始终** 返回纯 Kotlin 实现（[WanHttpClient]），不再依赖内嵌核心：
     * 订阅拉取、规则库更新、更新检查在瘦管理器里同样必须可用。
     * 方法名保持与旧 `libcore.HTTPClient` 一致，调用点无需改写。
     */
    fun httpClient(): WanHttpClient = WanHttpClient()

    /** 断言某功能可用；不可用时抛出可被 UI 捕获的明确错误。 */
    fun requireAvailable(feature: String) {
        if (!available) throw UnsupportedOperationException("$feature 需要完整版 APK（含内嵌核心）")
    }

    /** About 页展示的核心版本；瘦版回退到应用版本，避免空白。 */
    fun versionBox(): String = if (available) {
        runCatching { libcore.Libcore.versionBox() }.getOrDefault("")
    } else {
        BuildConfig.VERSION_NAME
    }

    /**
     * .xz 解压（geoip / geosite 资源落盘用）。
     * 有核心时用 Go 实现；瘦管理器回退到纯 Java 的 Tukaani XZ，行为一致。
     */
    fun unxz(src: String, dst: String) {
        if (available) {
            libcore.Libcore.unxz(src, dst)
            return
        }
        org.tukaani.xz.XZInputStream(java.io.FileInputStream(src)).use { input ->
            java.io.FileOutputStream(dst).use { output -> input.copyTo(output) }
        }
    }
}
