package com.lit008834.pixel.wanboxforandroid.feature.about.presentation

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.dropUnlessResumed
import com.lit008834.pixel.wanboxforandroid.BuildConfig
import com.lit008834.pixel.wanboxforandroid.R
import com.lit008834.pixel.wanboxforandroid.navigation.LocalNavigator

/** 关于页入口：组装静态展示状态和页面动作。 */
@Composable
internal fun AboutScreen() {
    val navigator = LocalNavigator.current
    val uriHandler = LocalUriHandler.current
    val linksHtml = stringResource(
        id = R.string.about_source_code,
        "<b><a href=\"https://github.com/Wuwan94/wanBoxForAndroid\">GitHub</a></b>",
        "<b><a href=\"https://t.me/OwnBoxs\">Telegram</a></b>",
    )

    AboutScreenMiuix(
        state = AboutUiState(
            title = stringResource(R.string.about),
            appName = stringResource(R.string.app_name),
            versionName = BuildConfig.VERSION_NAME,
            links = extractLinks(linksHtml),
        ),
        actions = AboutScreenActions(
            onBack = dropUnlessResumed { navigator.pop() },
            onOpenLink = uriHandler::openUri,
        ),
    )
}
