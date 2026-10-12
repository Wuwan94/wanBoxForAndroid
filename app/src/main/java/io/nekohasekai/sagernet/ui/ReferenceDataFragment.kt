package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.database.*
import kotlinx.coroutines.*

abstract class ReferenceDataFragment : ReferenceFragment(), ProfileManager.Listener, GroupManager.Listener {
    protected var groups: List<ProxyGroup> = emptyList()
    protected var profiles: List<ProxyEntity> = emptyList()
    private var loadJob: Job? = null

    override fun onViewCreated(view: View, state: Bundle?) {
        super.onViewCreated(view, state)
        ProfileManager.addListener(this)
        GroupManager.addListener(this)
    }
    override fun onResume() {
        super.onResume()
        refreshData()
    }
    protected fun refreshData() {
        lifecycleScope.launch(Dispatchers.Main.immediate) {
            if (view == null) return@launch
            loadJob?.cancel()
            loadJob = viewLifecycleOwner.lifecycleScope.launch {
                val snapshot = withContext(Dispatchers.IO) {
                    SagerDatabase.groupDao.allGroups() to SagerDatabase.proxyDao.getAll()
                }
                groups = snapshot.first
                profiles = snapshot.second.sortedWith(compareBy({ it.groupId }, { it.userOrder }, { it.id }))
                renderData()
            }
        }
    }
    protected abstract fun renderData()
    override fun onDestroyView() {
        ProfileManager.removeListener(this)
        GroupManager.removeListener(this)
        loadJob?.cancel()
        super.onDestroyView()
    }
    override suspend fun onAdd(profile: ProxyEntity) = refreshData()
    override suspend fun onUpdated(data: List<TrafficData>) {}
    override suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean) { if (!noTraffic) refreshData() }
    override suspend fun onRemoved(groupId: Long, profileId: Long) = refreshData()
    override suspend fun groupAdd(group: ProxyGroup) = refreshData()
    override suspend fun groupUpdated(group: ProxyGroup) = refreshData()
    override suspend fun groupUpdated(groupId: Long) = refreshData()
    override suspend fun groupRemoved(groupId: Long) = refreshData()
}
