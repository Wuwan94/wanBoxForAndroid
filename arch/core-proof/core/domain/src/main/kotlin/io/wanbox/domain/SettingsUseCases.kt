package io.wanbox.domain

import io.wanbox.contracts.dto.ConnectionState
import io.wanbox.contracts.dto.SettingsPatch
import io.wanbox.contracts.dto.SettingsSnapshot
import kotlinx.coroutines.flow.first

private val PROXY_MODES = setOf("vpn", "proxy", "tun")
private val PER_APP_MODES = setOf("off", "include", "exclude")

// ---------------- UC-NET-01 全局代理模式 ----------------

/** 切换 vpn / proxy / tun。连接中切换需要重连，因为监听方式变了。 */
suspend fun AppCore.setProxyMode(mode: String): SettingsSnapshot {
    val normalized = mode.lowercase()
    if (normalized !in PROXY_MODES) throw IllegalArgumentException("未知代理模式：$mode")

    val wasConnected = connectionState.value is ConnectionState.Connected
    settings.update(SettingsPatch(proxyMode = normalized))
    if (wasConnected) {
        logger.info("core", "proxy mode -> $normalized, reconnecting")
        disconnect()
        connect()
    } else {
        publish()
    }
    return settings.snapshot().first()
}

suspend fun AppCore.setAutoStart(enabled: Boolean): SettingsSnapshot {
    settings.update(SettingsPatch(autoStart = enabled))
    publish()
    return settings.snapshot().first()
}

suspend fun AppCore.setClashApiEnabled(enabled: Boolean): SettingsSnapshot {
    settings.update(SettingsPatch(clashApiEnabled = enabled))
    publish()
    return settings.snapshot().first()
}

suspend fun AppCore.setUpdateWhenConnectedOnly(enabled: Boolean): SettingsSnapshot {
    settings.update(SettingsPatch(updateWhenConnectedOnly = enabled))
    publish()
    return settings.snapshot().first()
}

// ---------------- UC-DATA-04 设置迁移 ----------------

/**
 * 设置迁移的纯函数部分：只负责"旧键 → 新键 + 合法性校验"。
 *
 * 刻意返回"存在哪些键"，而不是一整个 SettingsSnapshot——因为快照里字段的缺省值
 * 会覆盖现有设置。升级时旧版本没写的键（比如 selectedProfileId），必须保持现值，
 * 否则用户升级后会发现"选中节点没了"。合并动作在 [migrateSettings] 里做。
 */
object SettingsMigration {

    data class Result(
        val values: Map<String, String>,
        val migratedKeys: Int,
        val error: String? = null,
    ) {
        val ok: Boolean get() = error == null
    }

    private val LEGACY_KEYS = mapOf(
        "proxy_mode" to "proxyMode",
        "pref_proxy_mode" to "proxyMode",
        "selected_profile" to "selectedProfileId",
        "pref_selected_profile" to "selectedProfileId",
        "per_app_mode" to "perAppMode",
        "auto_start" to "autoStart",
        "clash_api" to "clashApiEnabled",
        "update_when_connected_only" to "updateWhenConnectedOnly",
    )

    fun migrate(raw: Map<String, String>): Result {
        var migrated = 0
        val values = mutableMapOf<String, String>()
        for ((key, value) in raw) {
            val target = LEGACY_KEYS[key] ?: continue
            values[target] = value
            migrated++
        }
        values["proxyMode"]?.let {
            if (it !in PROXY_MODES) return Result(emptyMap(), migrated, "proxyMode 非法：$it")
        }
        values["perAppMode"]?.let {
            if (it !in PER_APP_MODES) return Result(emptyMap(), migrated, "perAppMode 非法：$it")
        }
        values["selectedProfileId"]?.let {
            if (it.trim().isNotEmpty() && it.trim().toLongOrNull() == null) {
                return Result(emptyMap(), migrated, "selectedProfileId 非法：$it")
            }
        }
        return Result(values, migrated)
    }

    fun toBool(value: String?): Boolean = when (value?.trim()?.lowercase()) {
        "true", "1", "yes", "on" -> true
        else -> false
    }
}

/**
 * 迁移入口：校验 → 合并到当前快照 → 原子替换。
 * 校验失败时不调用 replace，原设置一个字都不改（失败回滚）。
 */
suspend fun AppCore.migrateSettings(raw: Map<String, String>): Int {
    val result = SettingsMigration.migrate(raw)
    if (!result.ok) {
        logger.warn("core", "settings migration aborted: ${result.error}, keep existing settings")
        return 0
    }
    if (result.values.isEmpty()) return 0

    val current = settings.snapshot().first()
    val merged = current.copy(
        selectedProfileId = result.values["selectedProfileId"]?.trim()?.toLongOrNull()
            ?: current.selectedProfileId,
        proxyMode = result.values["proxyMode"] ?: current.proxyMode,
        perAppMode = result.values["perAppMode"] ?: current.perAppMode,
        autoStart = result.values["autoStart"]?.let(SettingsMigration::toBool) ?: current.autoStart,
        clashApiEnabled = result.values["clashApiEnabled"]?.let(SettingsMigration::toBool)
            ?: current.clashApiEnabled,
        updateWhenConnectedOnly = result.values["updateWhenConnectedOnly"]?.let(SettingsMigration::toBool)
            ?: current.updateWhenConnectedOnly,
    )
    settings.replace(merged)
    logger.info("core", "settings migrated, keys=${result.migratedKeys}")
    publish()
    return result.migratedKeys
}
