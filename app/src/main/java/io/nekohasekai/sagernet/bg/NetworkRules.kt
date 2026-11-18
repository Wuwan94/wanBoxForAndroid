// B6（N3）：统一「按网络」规则。F1 智能启停与按网络选策略合并为一套规则表，
// 单规则表 UI 替代 F1 独立入口；Root supervisor 按实际出口指纹评估。
// JSON schema 与 rootmodule/network_rules.go 的 NetworkPolicy 一致：
// {"enabled":bool,"rules":[{"action":"suspend|direct|keep",
// "ssid":"...","gateway":"...","cidr":"...","egress":"wifi|not_wifi"}],
// "appPackage":"...","userId":0}
package io.nekohasekai.sagernet.bg

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.database.DataStore

enum class NetworkRuleAction(val id: String) {
    SUSPEND("suspend"),
    DIRECT("direct"),
    KEEP("keep");

    companion object {
        fun fromId(id: String?): NetworkRuleAction? = entries.firstOrNull { it.id == id }
    }
}

data class NetworkRule(
    val action: NetworkRuleAction,
    val ssid: String? = null,
    val gateway: String? = null,
    val cidr: String? = null,
    val egress: String? = null,
) {
    /** 至少一个匹配器；SSID 精确保留空格/大小写/逗号。 */
    fun hasMatcher(): Boolean =
        !ssid.isNullOrEmpty() || !gateway.isNullOrEmpty() || !cidr.isNullOrEmpty() || !egress.isNullOrEmpty()
}

data class NetworkPolicy(
    val enabled: Boolean,
    val rules: List<NetworkRule>,
    val appPackage: String = "",
    val userId: Int? = null,
)

object NetworkRules {
    const val MAX_RULES = 5

    /** 新规则默认动作取 keep（最保守，design.md 已记录的决定）。 */
    fun defaultNewRule(): NetworkRule = NetworkRule(NetworkRuleAction.KEEP)

    fun emptyPolicy(): NetworkPolicy = NetworkPolicy(enabled = false, rules = emptyList())

    fun parse(json: String): Result<NetworkPolicy> = runCatching {
        if (json.isBlank()) return@runCatching emptyPolicy()
        val root = JsonParser.parseString(json).asJsonObject
        val enabled = root.get("enabled")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
        val appPackage = root.get("appPackage")?.takeIf { !it.isJsonNull }?.asString ?: ""
        val userId = root.get("userId")?.takeIf { !it.isJsonNull }?.asInt
        val rules = mutableListOf<NetworkRule>()
        val arr = root.getAsJsonArray("rules") ?: JsonArray()
        require(arr.size() <= MAX_RULES) { "too_many_rules" }
        for (el in arr) {
            val o = el.asJsonObject
            val action = NetworkRuleAction.fromId(o.get("action")?.takeIf { !it.isJsonNull }?.asString)
                ?: throw IllegalArgumentException("invalid_action")
            val rule = NetworkRule(
                action = action,
                ssid = o.get("ssid")?.takeIf { !it.isJsonNull }?.asString,
                gateway = o.get("gateway")?.takeIf { !it.isJsonNull }?.asString,
                cidr = o.get("cidr")?.takeIf { !it.isJsonNull }?.asString,
                egress = o.get("egress")?.takeIf { !it.isJsonNull }?.asString,
            )
            validateRule(rule)
            rules.add(rule)
        }
        if (enabled) {
            require(userId != null && userId in 0..21474) { "user_required" }
            require(appPackage.isNotBlank()) { "package_required" }
        }
        NetworkPolicy(enabled, rules, appPackage, userId)
    }

    fun validateRule(rule: NetworkRule) {
        require(rule.hasMatcher()) { "matcher_required" }
        require(rule.egress == null || rule.egress == "wifi" || rule.egress == "not_wifi") { "invalid_egress" }
        rule.ssid?.let { ssid ->
            require(ssid.none { it.code < 0x20 || it.code == 0x7f }) { "invalid_ssid" }
        }
        rule.gateway?.let { checkAutoRule(it, gateway = true) }
        rule.cidr?.let { checkAutoRule(it, gateway = false) }
    }

    private fun checkAutoRule(value: String, gateway: Boolean) {
        val text = if (gateway) "gateway:$value" else value
        // 复用 F1 的数值 IP/CIDR 校验：非法主机名/CIDR 一律拒绝。
        val parsed = SmartAutoStopRuleParser.parse(text).getOrElse { throw IllegalArgumentException("invalid_rule") }
        require(parsed.size == 1) { "invalid_rule" }
    }

    fun serialize(policy: NetworkPolicy): String {
        val root = JsonObject()
        root.addProperty("enabled", policy.enabled)
        root.add("rules", JsonArray().apply {
            policy.rules.forEach { rule ->
                add(JsonObject().apply {
                    addProperty("action", rule.action.id)
                    rule.ssid?.let { addProperty("ssid", it) }
                    rule.gateway?.let { addProperty("gateway", it) }
                    rule.cidr?.let { addProperty("cidr", it) }
                    rule.egress?.let { addProperty("egress", it) }
                })
            }
        })
        root.addProperty("appPackage", policy.appPackage)
        policy.userId?.let { root.addProperty("userId", it) }
        return root.toString()
    }

    /** F1 迁移：已保存规则转为 suspend 动作规则，原顺序保留；失败时原数据不动。 */
    fun migrateFromSmartAutoStop(
        enabled: Boolean,
        rulesText: String,
        appPackage: String,
        userId: Int,
    ): Result<NetworkPolicy> = runCatching {
        val parsed = SmartAutoStopRuleParser.parse(rulesText).getOrThrow()
        val rules = parsed.map { raw ->
            val gateway = raw.startsWith("gateway:", ignoreCase = true)
            val value = if (gateway) raw.substring(8) else raw
            NetworkRule(
                action = NetworkRuleAction.SUSPEND,
                gateway = if (gateway) value else null,
                cidr = if (gateway) null else value,
            )
        }
        require(rules.size <= MAX_RULES) { "too_many_rules" }
        NetworkPolicy(enabled, rules, appPackage, userId)
    }

    /**
     * 一次性迁移：新表为空且旧 F1 规则非空时迁移并保存；迁移失败不清空旧数据、
     * 不写新表（调用方保留原 F1 数据并报错）。关闭不清空规则。
     */
    fun migrateIfNeeded(): Boolean {
        if (DataStore.networkRulesJson.isNotBlank()) return false
        if (DataStore.smartAutoStopRules.isBlank()) return false
        val migrated = migrateFromSmartAutoStop(
            DataStore.smartAutoStopEnabled,
            DataStore.smartAutoStopRules,
            io.nekohasekai.sagernet.BuildConfig.APPLICATION_ID,
            SmartAutoStopAndroidUser.fromUid(android.os.Process.myUid()),
        ).getOrNull() ?: return false
        DataStore.networkRulesJson = serialize(migrated)
        return true
    }

    /** 快照用当前策略：新表优先；新表为空时按 F1 现场迁移（失败则空策略）。 */
    fun currentPolicyForSnapshot(): NetworkPolicy {
        migrateIfNeeded()
        parse(DataStore.networkRulesJson).getOrNull()?.let { policy ->
            return policy.copy(
                enabled = DataStore.smartAutoStopEnabled,
                appPackage = io.nekohasekai.sagernet.BuildConfig.APPLICATION_ID,
                userId = SmartAutoStopAndroidUser.fromUid(android.os.Process.myUid()),
            )
        }
        return emptyPolicy()
    }
}
