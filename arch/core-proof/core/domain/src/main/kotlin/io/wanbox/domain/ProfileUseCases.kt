package io.wanbox.domain

import io.wanbox.contracts.dto.ConnectionState
import io.wanbox.contracts.dto.ImportResult
import io.wanbox.contracts.dto.ProfileSpec
import io.wanbox.contracts.dto.ProfileSummary
import kotlinx.coroutines.flow.first

// ---------------- UC-PROF-01 从 URI / 剪贴板 / 扫码导入 ----------------

/**
 * 解析结果按链接协议走 LinkCodec。
 * 去重规则：批次内按 dedupeKey（含凭据）去重；与库内既有节点按 (协议, 主机, 端口) 判定重复，
 * 重复项只计数不覆盖——对应原 app "重复项只提示不覆盖" 的行为。
 */
suspend fun AppCore.importProfiles(source: String, groupId: Long? = null): ImportResult {
    var batchSkipped = 0
    val errors = mutableListOf<String>()
    val specs = mutableListOf<ProfileSpec>()
    val seen = mutableSetOf<String>()

    for (line in source.lineSequence().map { it.trim() }) {
        if (line.isEmpty() || line.startsWith("#")) continue
        val spec = LinkCodec.parse(line)
        if (spec == null) {
            errors += "无法解析：" + line.take(48)
            continue
        }
        if (!seen.add(spec.dedupeKey())) {
            batchSkipped++
            continue
        }
        specs += spec
    }

    var imported = 0
    var duplicates = 0
    val existing = profiles.observe().first()
    for (spec in specs) {
        val dup = existing.any {
            it.protocol.equals(spec.protocol, ignoreCase = true) &&
                it.server.equals(spec.server, ignoreCase = true) &&
                it.port == spec.port
        }
        if (dup) {
            duplicates++
            continue
        }
        profiles.create(spec.copy(groupId = groupId ?: spec.groupId))
        imported++
    }

    logger.info("core", "import: +$imported duplicate=${duplicates + batchSkipped} invalid=${errors.size}")
    publish()
    return ImportResult(imported, duplicates + batchSkipped, errors)
}

// ---------------- UC-PROF-02 手动新建 ----------------

suspend fun AppCore.createProfile(spec: ProfileSpec): ProfileSummary {
    val created = profiles.create(spec)
    logger.info("core", "profile created id=${created.id} ${created.protocol}")
    publish()
    return created
}

// ---------------- UC-PROF-03 编辑 ----------------

/** patch 里出现 server / port / name / protocol 或协议字段时合并；当前节点被改则重连。 */
suspend fun AppCore.updateProfile(id: Long, patch: Map<String, String>): ProfileSummary {
    val current = profiles.get(id) ?: error("节点不存在: $id")
    val fields = linkedMapOf(
        "server" to (patch["server"] ?: current.server),
        "port" to (patch["port"] ?: current.port.toString()),
    )
    patch.forEach { (k, v) ->
        if (k != "server" && k != "port" && k != "name" && k != "protocol") fields[k] = v
    }
    val spec = ProfileSpec(
        protocol = patch["protocol"] ?: current.protocol,
        name = patch["name"] ?: current.name,
        fields = fields,
        groupId = current.groupId,
    )
    val updated = profiles.update(id, spec)

    val connectedToThis = (connectionState.value as? ConnectionState.Connected)?.profileId == id
    if (connectedToThis) {
        logger.info("core", "profile $id updated while connected, reconnecting")
        disconnect()
        connect(id)
    }
    publish()
    return updated
}

// ---------------- UC-PROF-04 删除 ----------------

/** 副作用：若删的是当前节点先断开；指向被删节点的路由规则清空出站，避免悬挂引用。 */
suspend fun AppCore.deleteProfiles(ids: List<Long>): Int {
    if (ids.isEmpty()) return 0
    val currentId = (connectionState.value as? ConnectionState.Connected)?.profileId
    if (currentId != null && currentId in ids) disconnect()
    val cleared = routes.clearOutbound(ids)
    val deleted = profiles.delete(ids)
    logger.info("core", "profiles deleted=$deleted, route rules cleared=$cleared")
    publish()
    return deleted
}

// ---------------- UC-PROF-05 克隆 ----------------

suspend fun AppCore.cloneProfile(id: Long): ProfileSummary {
    val clone = profiles.clone(id)
    logger.info("core", "profile cloned from=$id to=${clone.id}")
    publish()
    return clone
}

// ---------------- UC-PROF-06 拖拽排序 ----------------

suspend fun AppCore.reorderProfiles(id: Long, toIndex: Int): List<Long> {
    val order = profiles.observe().first().map { it.id }.toMutableList()
    val from = order.indexOf(id)
    if (from < 0) return order
    val target = toIndex.coerceIn(0, order.lastIndex)
    order.removeAt(from)
    order.add(target, id)
    profiles.reorder(order)
    publish()
    return order
}

// ---------------- UC-CONN-03 选中节点（连接中即热切换） ----------------

suspend fun AppCore.selectProfile(id: Long): ConnectionState = switchTo(id)
