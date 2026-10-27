// :adapters:memory —— 内存实现。用途：CLI 无界面运行、单元测试、以及作为"换实现不动核心"的证明。
// 依赖纪律：只能依赖 :core:contracts（+ 必要时的 :core:domain 纯函数），不得引用 Android。
package io.wanbox.adapters.memory

import io.wanbox.contracts.ProfileRepository
import io.wanbox.contracts.dto.ProfileSpec
import io.wanbox.contracts.dto.ProfileSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class InMemoryProfileRepository : ProfileRepository {

    private val items = MutableStateFlow<List<ProfileSummary>>(emptyList())
    private var nextId = 1L

    val current: List<ProfileSummary> get() = items.value
    val count: Int get() = items.value.size

    override fun observe(): Flow<List<ProfileSummary>> = items.asStateFlow()

    override suspend fun get(id: Long): ProfileSummary? = items.value.firstOrNull { it.id == id }

    override suspend fun create(spec: ProfileSpec): ProfileSummary {
        val summary = ProfileSummary(
            id = nextId++,
            groupId = spec.groupId,
            name = spec.name,
            protocol = spec.protocol,
            server = spec.server,
            port = spec.port,
            latencyMs = null,
            enabled = true,
        )
        items.value = items.value + summary
        return summary
    }

    override suspend fun update(id: Long, spec: ProfileSpec): ProfileSummary {
        val old = get(id) ?: error("profile $id not found")
        val updated = old.copy(
            name = spec.name,
            protocol = spec.protocol,
            server = spec.server,
            port = spec.port,
            groupId = spec.groupId ?: old.groupId,
        )
        items.value = items.value.map { if (it.id == id) updated else it }
        return updated
    }

    override suspend fun delete(ids: List<Long>): Int {
        val before = items.value.size
        items.value = items.value.filterNot { it.id in ids }
        return before - items.value.size
    }

    override suspend fun clone(id: Long): ProfileSummary {
        val old = get(id) ?: error("profile $id not found")
        val copy = old.copy(id = nextId++, name = old.name + " (副本)")
        items.value = items.value + copy
        return copy
    }

    override suspend fun reorder(ids: List<Long>) {
        val byId = items.value.associateBy { it.id }
        val ordered = ids.mapNotNull { byId[it] }
        val rest = items.value.filterNot { it.id in ids }
        items.value = ordered + rest
    }

    override suspend fun deleteByGroup(groupId: Long): Int {
        val before = items.value.size
        items.value = items.value.filterNot { it.groupId == groupId }
        return before - items.value.size
    }
}
