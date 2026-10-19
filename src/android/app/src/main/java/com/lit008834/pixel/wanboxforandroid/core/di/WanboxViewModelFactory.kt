package com.lit008834.pixel.wanboxforandroid.core.di

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lit008834.pixel.wanboxforandroid.WanboxApplication
import com.lit008834.pixel.wanboxforandroid.core.module.ModuleAccessViewModel
import com.lit008834.pixel.wanboxforandroid.feature.apps.presentation.AppsViewModel
import com.lit008834.pixel.wanboxforandroid.feature.catalog.presentation.nodes.CatalogNodesViewModel
import com.lit008834.pixel.wanboxforandroid.feature.catalog.presentation.subscriptions.SubscriptionDetailsViewModel
import com.lit008834.pixel.wanboxforandroid.feature.catalog.presentation.subscriptions.SubscriptionEditorViewModel
import com.lit008834.pixel.wanboxforandroid.feature.catalog.presentation.subscriptions.SubscriptionsViewModel
import com.lit008834.pixel.wanboxforandroid.feature.dashboard.presentation.CatalogDashboardViewModel
import com.lit008834.pixel.wanboxforandroid.feature.kernel.presentation.SingBoxConfigViewModel
import com.lit008834.pixel.wanboxforandroid.feature.logs.presentation.LogsViewModel
import com.lit008834.pixel.wanboxforandroid.feature.inbound.presentation.InboundViewModel
import com.lit008834.pixel.wanboxforandroid.feature.settings.presentation.SettingsViewModel
import com.lit008834.pixel.wanboxforandroid.feature.routing.presentation.RoutingRulesViewModel
import com.lit008834.pixel.wanboxforandroid.feature.theme.presentation.ThemeViewModel

/** 在应用组合根集中创建 ViewModel，业务类不再依赖 Application 或服务定位器。 */
internal class WanboxViewModelFactory(
    private val container: AppContainer
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
        when (modelClass) {
            ModuleAccessViewModel::class.java -> ModuleAccessViewModel(container.moduleEnvironment)
            CatalogDashboardViewModel::class.java -> CatalogDashboardViewModel(
                container.serviceRepository,
                container.moduleEnvironment.totalMemoryBytes
            )

            CatalogNodesViewModel::class.java -> CatalogNodesViewModel(
                container.nodeRepository,
                container.nodeImportStore
            )

            SubscriptionsViewModel::class.java ->
                SubscriptionsViewModel(container.subscriptionRepository)

            SubscriptionDetailsViewModel::class.java ->
                SubscriptionDetailsViewModel(
                    container.subscriptionRepository,
                    container.nodeRepository
                )

            SubscriptionEditorViewModel::class.java ->
                SubscriptionEditorViewModel(container.subscriptionRepository)

            AppsViewModel::class.java -> AppsViewModel(
                container.appPolicyRepository,
                container.appPackageRepository,
                writes = container.configurationWrites
            )

            SettingsViewModel::class.java -> SettingsViewModel(
                container.configRepository,
                writes = container.configurationWrites
            )

            InboundViewModel::class.java -> InboundViewModel(container.inboundRepository,
                writes = container.configurationWrites)

            RoutingRulesViewModel::class.java -> RoutingRulesViewModel(container.configRepository)

            SingBoxConfigViewModel::class.java -> SingBoxConfigViewModel(
                container.configRepository,
                container.serviceRepository,
                container.inboundRepository
            )

            LogsViewModel::class.java -> LogsViewModel(container.logRepository)
            ThemeViewModel::class.java -> ThemeViewModel(container.themeManager)
            else -> error("不支持的 ViewModel: ${modelClass.name}")
        } as T
}

/** 使用当前 Miuix Nav 条目的 ViewModelStore 获取已注入依赖的 ViewModel。 */
@Composable
internal inline fun <reified T : ViewModel> wanboxViewModel(): T {
    val application = LocalContext.current.applicationContext as WanboxApplication
    return viewModel(factory = application.container.viewModelFactory)
}
