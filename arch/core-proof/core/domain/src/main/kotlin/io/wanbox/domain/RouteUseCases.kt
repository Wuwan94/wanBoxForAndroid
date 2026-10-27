package io.wanbox.domain

import io.wanbox.contracts.dto.RouteRule
import kotlinx.coroutines.flow.first

// ---------------- UC-ROUTE-01 修改分流规则 ----------------

/** 写入/更新一条规则。连接中需要即时生效时，由适配层监听规则流重建配置。 */
suspend fun AppCore.saveRouteRule(rule: RouteRule): List<RouteRule> {
    routes.upsert(rule)
    val rules = routes.rules().first()
    logger.info("core", "route rule saved id=${rule.id} match=${rule.match} rules=${rules.size}")
    publish()
    return rules
}

suspend fun AppCore.deleteRouteRules(ids: List<Long>): List<RouteRule> {
    routes.delete(ids)
    val rules = routes.rules().first()
    publish()
    return rules
}

// ---------------- UC-ROUTE-02 排序 / 启停 ----------------

suspend fun AppCore.reorderRoutes(ids: List<Long>): List<RouteRule> {
    routes.reorder(ids)
    val rules = routes.rules().first()
    publish()
    return rules
}

suspend fun AppCore.setRouteEnabled(ids: List<Long>, enabled: Boolean): List<RouteRule> {
    routes.setEnabled(ids, enabled)
    val rules = routes.rules().first()
    logger.info("core", "route rules ${ids.size} set enabled=$enabled")
    publish()
    return rules
}

// ---------------- UC-APP-01 分应用代理 ----------------

/**
 * 设置分应用选择集。mode 由核心校验，界面只传原始选择。
 * 返回归一化后的包名列表（去重、去空白、排序），便于界面回显与服务重建。
 */
suspend fun AppCore.setPerAppProxy(packages: List<String>, mode: String): List<String> {
    val normalizedMode = mode.lowercase()
    if (normalizedMode !in PER_APP_MODES) {
        throw IllegalArgumentException("未知的分应用模式：$mode")
    }
    val normalized = packages.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
    routes.setPerApp(normalized, normalizedMode)
    settings.update(io.wanbox.contracts.dto.SettingsPatch(perAppMode = normalizedMode))
    logger.info("core", "per-app mode=$normalizedMode apps=${normalized.size}")
    publish()
    return normalized
}

private val PER_APP_MODES = setOf("off", "include", "exclude")
