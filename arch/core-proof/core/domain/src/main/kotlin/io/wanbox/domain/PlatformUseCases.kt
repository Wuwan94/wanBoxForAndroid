package io.wanbox.domain

import io.wanbox.contracts.dto.RootModuleInfo

// ---------------- UC-ROOT-01 安装 / 更新 root 模块 ----------------

/**
 * root 命令白名单。命令拼接面是典型注入点，规则放核心：
 * 只允许读取模块目录下的固定命令，出现 shell 元字符或 ../ 一律拒绝。
 */
object RootCommandPolicy {

    private val ALLOWLIST = listOf(
        Regex("^cat /data/adb/modules/[A-Za-z0-9._-]+/module[.]prop$"),
        Regex("^/data/adb/modules/[A-Za-z0-9._-]+/wanbox-bridge --(status|start|stop|restart)$"),
        Regex("^ls -l /data/adb/modules/[A-Za-z0-9._-]+$"),
    )

    private val FORBIDDEN = listOf(';', '|', '&', '`', '$', '<', '>', '\n', '\r')

    fun isAllowed(command: String): Boolean {
        val cmd = command.trim()
        if (cmd.isEmpty()) return false
        if (cmd.any { it in FORBIDDEN }) return false
        if (cmd.contains("..")) return false
        return ALLOWLIST.any { it.matches(cmd) }
    }
}

/** 返回 null 表示被核心拒绝，适配层不会收到任何命令。 */
suspend fun AppCore.rootCommand(command: String): String? {
    if (!RootCommandPolicy.isAllowed(command)) {
        logger.warn("core", "root command rejected: $command")
        return null
    }
    return root.exec(command.trim())
}

suspend fun AppCore.installRootModule(bundle: String): RootModuleInfo {
    val info = root.install(bundle)
    if (info.installed) {
        logger.info("core", "root module installed version=${info.version}")
        publish()
    } else {
        logger.warn("core", "root module install failed: ${info.error}")
    }
    return info
}

suspend fun AppCore.rootModuleVersion(): String? = root.installedVersion()
