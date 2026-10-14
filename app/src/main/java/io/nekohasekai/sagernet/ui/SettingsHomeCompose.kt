// @author 雾晚
package io.nekohasekai.sagernet.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nekohasekai.sagernet.R

/**
 * 一级设置八组结构定义：
 * 【运行、HTTP/TLS、DNS设置、路由、测速、主题与外观、日志与诊断、其他】
 * 已移除独立“Root模块管理”与“高级设置与工具”入口；分应用代理保留在顶部独立入口及运行组内。
 */
enum class SettingsSectionGroup(
    val sectionKey: String,
    @StringRes val titleRes: Int,
    @DrawableRes val iconRes: Int
) {
    RUNTIME("runtime", R.string.settings_group_runtime, R.drawable.ic_minimal_wifi),
    HTTP_TLS("http_tls", R.string.settings_group_http_tls, R.drawable.ic_baseline_lock_24),
    DNS("dns", R.string.settings_group_dns, R.drawable.ic_minimal_globe),
    ROUTING("route", R.string.settings_group_routing, R.drawable.ic_minimal_connections),
    SPEED_TEST("speedtest", R.string.settings_group_speedtest, R.drawable.ic_baseline_speed_24),
    APPEARANCE("appearance", R.string.minimal_appearance, R.drawable.ic_minimal_brush),
    LOGS_DIAGNOSTICS("logs", R.string.minimal_logs, R.drawable.ic_minimal_subscription),
    OTHER("other", R.string.settings_group_other, R.drawable.ic_minimal_info)
}

@Composable
fun SettingsHomeComposeScreen(
    versionName: String,
    onOpenPerAppProxy: () -> Unit,
    onOpenSection: (String) -> Unit,
    onItemPositioned: (key: String, title: String, boundsInRoot: Rect) -> Unit = { _, _, _ -> },
    onDisposeHome: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    DisposableEffect(Unit) {
        onDispose { onDisposeHome() }
    }

    val bgColor = colorResource(R.color.minimal_background)
    val textColor = colorResource(R.color.minimal_text)
    val secondaryColor = colorResource(R.color.minimal_secondary)
    val lineColor = colorResource(R.color.minimal_line)
    val perAppTitle = stringResource(R.string.per_app_proxy)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(bgColor)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 16.dp)
    ) {
        // 保留分应用代理显著入口
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .testTag("settings_item_per_app_proxy")
                .semantics(mergeDescendants = true) { contentDescription = perAppTitle }
                .onGloballyPositioned { coords ->
                    if (coords.isAttached) {
                        onItemPositioned("per_app_proxy", perAppTitle, coords.boundsInRoot())
                    }
                }
                .clickable(onClick = onOpenPerAppProxy)
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(R.drawable.ic_minimal_apps),
                contentDescription = null,
                modifier = Modifier.size(26.dp)
            )
            Spacer(modifier = Modifier.width(20.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = perAppTitle,
                    color = textColor,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.per_app_proxy_summary),
                    color = secondaryColor,
                    fontSize = 12.sp
                )
            }
            Image(
                painter = painterResource(R.drawable.ic_minimal_chevron),
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        }

        HorizontalDivider(thickness = 1.dp, color = lineColor)
        Spacer(modifier = Modifier.height(20.dp))

        // 八大一级设置分组列表
        SettingsSectionGroup.entries.forEachIndexed { index, group ->
            val groupTitle = stringResource(group.titleRes)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .testTag("settings_section_${group.sectionKey}")
                    .semantics(mergeDescendants = true) { contentDescription = groupTitle }
                    .onGloballyPositioned { coords ->
                        if (coords.isAttached) {
                            onItemPositioned(group.sectionKey, groupTitle, coords.boundsInRoot())
                        }
                    }
                    .clickable { onOpenSection(group.sectionKey) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(group.iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(modifier = Modifier.width(20.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = groupTitle,
                        color = textColor,
                        fontSize = 16.sp
                    )
                }
                Image(
                    painter = painterResource(R.drawable.ic_minimal_chevron),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
            HorizontalDivider(thickness = 1.dp, color = lineColor)
            if (index == 4) {
                Spacer(modifier = Modifier.height(20.dp))
            }
        }

        Text(
            text = versionName,
            color = secondaryColor,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp, bottom = 8.dp)
                .onGloballyPositioned { coords ->
                    if (coords.isAttached) {
                        onItemPositioned("version_footer", versionName, coords.boundsInRoot())
                    }
                }
        )
    }
}
