package com.lit008834.pixel.wanboxforandroid.feature.settings.presentation

import androidx.compose.runtime.Immutable
import com.lit008834.pixel.wanboxforandroid.feature.settings.model.WifiPolicySettings

@Immutable
data class SettingsUiState(
    val hasLoaded: Boolean = false,
    val autoStartEnabled: Boolean = false,
    val wifi: WifiPolicySettings = WifiPolicySettings(),
    val isLoading: Boolean = false,
    val isSavingWifi: Boolean = false,
    val isSavingAutoStart: Boolean = false,
    val hasPendingWifi: Boolean = false,
    val requiresReload: Boolean = false,
    val error: String = ""
) {
    val isSaving: Boolean get() = isSavingWifi || isSavingAutoStart
}
