package io.wanbox.adapters.memory

import io.wanbox.contracts.RouteRepository
import io.wanbox.contracts.dto.RouteRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class InMemoryRoutes : RouteRepository {

    private val items = MutableStateFlow<List<RouteRule>>(emptyList())
    private var packages: List<String> = emptyList()

    var perAppMode: String = "off"
        private set

    val all: List<RouteRule> get() = items.value
    val selected: List<String> get() = packages

    override fun rules(): Flow<List<RouteRule>> = items.asStateFlow()

    override suspend fun upsert(rule: RouteRule) {
        val exists = items.value.any { it.id == rule.id }
        items.value = if (exists) items.value.map { if (it.id == rule.id) rule else it }
        else items.value + rule
    }

    override suspend fun delete(ids: List<Long>) {
        items.value = items.value.filterNot { it.id in ids }
    }

    override suspend fun reorder(ids: List<Long>) {
        val byId = items.value.associateBy { it.id }
        val ordered = ids.mapNotNull { byId[it] }.mapIndexed { index, rule -> rule.copy(order = index) }
        val rest = items.value.filterNot { it.id in ids }
        items.value = ordered + rest
    }

    override suspend fun setEnabled(ids: List<Long>, enabled: Boolean) {
        items.value = items.value.map { if (it.id in ids) it.copy(enabled = enabled) else it }
    }

    override suspend fun selectedPackages(): List<String> = packages

    override suspend fun setPerApp(packages: List<String>, mode: String) {
        this.packages = packages
        this.perAppMode = mode
    }

    override suspend fun clearOutbound(profileIds: List<Long>): Int {
        var cleared = 0
        items.value = items.value.map { rule ->
            if (rule.outboundProfileId != null && rule.outboundProfileId in profileIds) {
                cleared++
                rule.copy(outboundProfileId = null)
            } else {
                rule
            }
        }
        return cleared
    }
}
