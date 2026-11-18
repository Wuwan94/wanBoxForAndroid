// B6（N5）：分应用名单按 Android 用户细化。持久化键为严格的
// `<userId>:<package>` 字符串；同一包名在不同用户下是不同条目。
// UID 解析由 Root/Go 侧按用户查询 Android package service 后合并，
// 不得把包名直接交给 sing-box，不得跨用户合并 UID。
// 迁移：旧单用户名单（DataStore.individual）按当前用户（user 0）+ 包名反查迁移；
// 无法反查的 UID 条目保留在旧字段并提示用户手动确认，不得静默丢弃。
// 本轮不设旧字段清理版本（design.md 已记录的决定）：旧字段只读保留，
// 清理留待以后版本另行决定。
package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.utils.PackageCache

object PerUserApps {
    private val refPattern = Regex("^(\\d+):([a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+)$")

    data class Ref(val userId: Int, val packageName: String) {
        val key: String get() = "$userId:$packageName"
    }

    fun parseRef(raw: String): Ref? {
        val m = refPattern.matchEntire(raw.trim()) ?: return null
        val userId = m.groupValues[1].toIntOrNull() ?: return null
        if (userId !in 0..21474) return null
        return Ref(userId, m.groupValues[2])
    }

    fun formatRef(userId: Int, packageName: String): String = "$userId:$packageName"

    fun parseRefs(text: String): List<Ref> =
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
            .mapNotNull { parseRef(it) }.distinct().toList()

    fun serializeRefs(refs: Collection<Ref>): String =
        refs.distinctBy { it.key }.joinToString("\n") { it.key }

    /**
     * 迁移旧单用户名单：裸包名按当前用户（user 0）迁移；数字 UID 用
     * PackageCache 的 UID→包名反查；已是 `<userId>:<package>` 的原样保留。
     * 返回（已迁移，无法反查而保留的旧行）。顺序保留、去重。
     */
    fun migrateLegacy(
        lines: List<String>,
        reverseLookup: (Int) -> String? = { uid -> PackageCache.uidMap[uid]?.firstOrNull() },
    ): Pair<List<Ref>, List<String>> {
        val migrated = linkedSetOf<Ref>()
        val retained = linkedSetOf<String>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val ref = parseRef(line)
            if (ref != null) {
                migrated.add(ref)
                continue
            }
            val uid = line.toIntOrNull()
            if (uid != null && uid >= 0) {
                val pkg = reverseLookup(uid)
                if (pkg != null) migrated.add(Ref(0, pkg)) else retained.add(line)
                continue
            }
            if (line.matches(Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$"))) {
                migrated.add(Ref(0, line))
            } else {
                retained.add(line)
            }
        }
        return migrated.toList() to retained.toList()
    }

    /**
     * 一次性迁移：individualPerUser 为空且旧 individual 非空时迁移并保存；
     * 无法反查的行写入 individualUnresolved（只读保留，供用户手动确认），
     * 旧 individual 字段本身保留不动（本轮不设清理版本）。
     */
    fun migrateIfNeeded(): Boolean {
        if (DataStore.individualPerUser.isNotBlank()) return false
        if (DataStore.individual.isBlank()) return false
        val (migrated, retained) = migrateLegacy(DataStore.individual.lineSequence().toList())
        if (migrated.isEmpty() && retained.isEmpty()) return false
        if (migrated.isNotEmpty()) {
            DataStore.individualPerUser = serializeRefs(migrated)
        }
        if (retained.isNotEmpty()) {
            DataStore.individualUnresolved = retained.joinToString("\n")
        }
        return migrated.isNotEmpty() || retained.isNotEmpty()
    }

    /** 当前生效的名单引用：新键优先；新键为空时先迁移旧名单。 */
    fun currentRefs(): List<Ref> {
        migrateIfNeeded()
        val refs = parseRefs(DataStore.individualPerUser)
        if (refs.isNotEmpty()) return refs
        // 旧名单全部无法反查时仍为空（保留行只读，不参与筛选）。
        return emptyList()
    }

    /**
     * 按用户解析 UID（供 ConfigBuilder）：user 0 走 PackageCache 的
     * packageMap，其他用户走 Root 的 `pm list packages --user` 映射
     * （PackageCache.uidsForUserPackage）。UID 0 永不自动加入；
     * Root UID 策略与应用名单正交（由调用方另行处理）。
     * 仅 eBPF 共享路径时调用方 MUST NOT 查询应用 UID（见 ConfigBuilder）。
     */
    fun resolveUids(refs: List<Ref>): List<Int> {
        PackageCache.awaitLoadSync()
        val out = linkedSetOf<Int>()
        for (ref in refs) {
            val uids = if (ref.userId == 0) {
                PackageCache.packageMap[ref.packageName]?.let { listOf(it) }.orEmpty() +
                    PackageCache.uidsForUserPackage(0, ref.packageName)
            } else {
                PackageCache.uidsForUserPackage(ref.userId, ref.packageName)
            }
            uids.filter { it >= 1000 }.forEach { out.add(it) }
        }
        return out.toList()
    }
}
