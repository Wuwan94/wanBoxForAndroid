// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.core.testing

import io.nekohasekai.sagernet.core.contracts.AppSettings
import io.nekohasekai.sagernet.core.contracts.ImportResult
import io.nekohasekai.sagernet.core.contracts.PlatformUri
import io.nekohasekai.sagernet.core.contracts.ProfileRepository
import io.nekohasekai.sagernet.core.contracts.ProfileSummary
import io.nekohasekai.sagernet.core.contracts.Selection
import io.nekohasekai.sagernet.core.contracts.ServiceState
import io.nekohasekai.sagernet.core.contracts.SessionState
import io.nekohasekai.sagernet.core.contracts.SettingsPort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeProfileRepository(
    initial: List<ProfileSummary> = emptyList(),
) : ProfileRepository {

    private val items = MutableStateFlow(initial)
    private val selected = MutableStateFlow<Selection?>(null)
    val selectedCalls = mutableListOf<Long>()

    override fun observeProfiles(): Flow<List<ProfileSummary>> = items.asStateFlow()

    override fun observeSelected(): Flow<Selection?> = selected.asStateFlow()

    override suspend fun get(profileId: Long): ProfileSummary? =
        items.value.firstOrNull { it.id == profileId }

    override suspend fun select(profileId: Long) {
        selectedCalls += profileId
        selected.value = items.value.firstOrNull { it.id == profileId }
            ?.let { Selection(it.id, it.groupId) }
    }

    override suspend fun rename(profileId: Long, name: String) {
        items.value = items.value.map { if (it.id == profileId) it.copy(name = name) else it }
    }

    override suspend fun delete(profileId: Long) {
        items.value = items.value.filterNot { it.id == profileId }
    }

    override suspend fun importFrom(uri: PlatformUri): ImportResult =
        ImportResult(imported = 0, skipped = 0, errors = emptyList())
}

class FakeSettingsPort(
    initial: AppSettings = AppSettings(
        autoStart = false,
        clashApiEnabled = false,
        proxyMode = "vpn",
        perAppMode = "off",
    ),
    initialSession: SessionState = SessionState(
        currentProfileId = null,
        selectedProxyId = null,
        selectedGroupId = 1L,
        serviceState = ServiceState.IDLE,
        mixedInboundAuthed = false,
    ),
) : SettingsPort {

    private val settings = MutableStateFlow(initial)
    private val sessionState = MutableStateFlow(initialSession)

    val updates = mutableListOf<AppSettings>()
    val selectedGroups = mutableListOf<Long>()

    override fun observeSettings(): Flow<AppSettings> = settings.asStateFlow()

    override suspend fun update(settings: AppSettings) {
        updates += settings
        this.settings.value = settings
    }

    val current: AppSettings get() = settings.value

    val session: SettingsPort.Session = object : SettingsPort.Session {
        override fun observeSession(): Flow<SessionState> = sessionState.asStateFlow()

        override suspend fun selectGroup(groupId: Long) {
            selectedGroups += groupId
            sessionState.value = sessionState.value.copy(selectedGroupId = groupId)
        }
    }

    /** 供测试直接改会话状态（例如模拟服务进入 RUNNING）。 */
    fun setSession(state: SessionState) {
        sessionState.value = state
    }
}
