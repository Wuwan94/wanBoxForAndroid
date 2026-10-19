package com.lit008834.pixel.wanboxforandroid.core.di

import android.content.Context
import android.widget.Toast
import com.lit008834.pixel.wanboxforandroid.R
import com.lit008834.pixel.wanboxforandroid.core.config.ConfigurationWrites
import com.lit008834.pixel.wanboxforandroid.core.api.ModuleApiClient
import com.lit008834.pixel.wanboxforandroid.core.module.AndroidModuleEnvironment
import com.lit008834.pixel.wanboxforandroid.core.module.ServiceRepository
import com.lit008834.pixel.wanboxforandroid.core.ui.userMessage
import com.lit008834.pixel.wanboxforandroid.feature.apps.data.AppPackageRepository
import com.lit008834.pixel.wanboxforandroid.feature.apps.data.AppPolicyRepository
import com.lit008834.pixel.wanboxforandroid.feature.catalog.data.NodeImportStore
import com.lit008834.pixel.wanboxforandroid.feature.catalog.data.NodeRepository
import com.lit008834.pixel.wanboxforandroid.feature.catalog.data.SubscriptionRepository
import com.lit008834.pixel.wanboxforandroid.feature.logs.data.LogRepository
import com.lit008834.pixel.wanboxforandroid.feature.inbound.data.InboundRepository
import com.lit008834.pixel.wanboxforandroid.feature.settings.data.ConfigRepository
import com.lit008834.pixel.wanboxforandroid.feature.theme.presentation.ThemeManager

/** 应用级依赖容器，保持 Repository 与 HTTP 客户端共享。 */
internal class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val apiClient = ModuleApiClient(appContext)
    val configurationWrites = ConfigurationWrites(reportFailure = { reportConfigurationSaveFailure(it.userMessage()) })

    val serviceRepository = ServiceRepository(apiClient)
    val nodeRepository = NodeRepository(apiClient)
    val subscriptionRepository = SubscriptionRepository(apiClient)
    val configRepository = ConfigRepository(apiClient,
        awaitPendingWrites = configurationWrites::await)
    val appPolicyRepository = AppPolicyRepository(configRepository)
    val inboundRepository = InboundRepository(configRepository, serviceRepository)
    val logRepository = LogRepository(apiClient, appContext)
    val moduleEnvironment = AndroidModuleEnvironment(appContext, apiClient)
    val nodeImportStore = NodeImportStore(appContext)
    val appPackageRepository = AppPackageRepository(apiClient)
    val themeManager = ThemeManager(
        appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    )
    val viewModelFactory = WanboxViewModelFactory(this)

    private fun reportConfigurationSaveFailure(message: String) {
        Toast.makeText(appContext, "${appContext.getString(R.string.json_save_failed)}: $message", Toast.LENGTH_LONG).show()
    }
}
