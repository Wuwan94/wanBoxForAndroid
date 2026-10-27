package io.wanbox.adapters.memory

import io.wanbox.contracts.SettingsStore
import io.wanbox.contracts.dto.SettingsPatch
import io.wanbox.contracts.dto.SettingsSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class InMemorySettings(
    initial: SettingsSnapshot = SettingsSnapshot(
        selectedProfileId = null,
        proxyMode = "vpn",
        perAppMode = "off",
        autoStart = false,
        clashApiEnabled = false,
    ),
) : SettingsStore {

    private val state = MutableStateFlow(initial)

    val value: SettingsSnapshot get() = state.value
    var replaceCalls: Int = 0
        private set

    override fun snapshot(): Flow<SettingsSnapshot> = state.asStateFlow()

    override suspend fun update(patch: SettingsPatch) {
        val old = state.value
        state.value = old.copy(
            selectedProfileId = patch.selectedProfileId ?: old.selectedProfileId,
            proxyMode = patch.proxyMode ?: old.proxyMode,
            perAppMode = patch.perAppMode ?: old.perAppMode,
            autoStart = patch.autoStart ?: old.autoStart,
            clashApiEnabled = patch.clashApiEnabled ?: old.clashApiEnabled,
            updateWhenConnectedOnly = patch.updateWhenConnectedOnly ?: old.updateWhenConnectedOnly,
        )
    }

    override suspend fun replace(snapshot: SettingsSnapshot) {
        replaceCalls++
        state.value = snapshot
    }
}
