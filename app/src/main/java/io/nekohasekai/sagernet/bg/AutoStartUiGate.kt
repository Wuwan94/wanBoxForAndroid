// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.bg

/**
 * 「这一条同步状态能不能改界面」的判定。
 *
 * 从 `SettingsPreferenceFragment.currentHost()` 里抽出来不是为了好看：这段判定是
 * 自启同步唯一的 UI 安全边界（已分离的 Fragment、已销毁的视图、正在退出的 Activity、
 * 旧请求的迟到结果都不许写 Preference 或弹 Toast），而它原来藏在 `collectLatest`
 * 的局部函数里——既不可注入，也没法在 JVM 上测，于是契约里写的“UI 门有测试”成了
 * 一句没有证据的话（v3.0.18-preview.3 复审 P1）。抽成纯函数之后，
 * `AutoStartSyncUiGateTest` 可以逐条钉住每个判据。
 *
 * 五条判据同时成立才放行：
 *  1. Fragment 已挂载（`isAdded`）：分离之后任何 `requireContext()` 都会抛；
 *  2. 视图仍存在（`view != null`）：`onDestroyView` 之后视图生命周期已释放；
 *  3. 宿主 Activity 没有正在 finishing；
 *  4. 宿主 Activity 没有被销毁；
 *  5. 视图生命周期至少 STARTED（后台不改可见 UI），且这条状态仍是最新发布的那条
 *     （归档/过期的结果不许覆盖更新请求的 pending 或结果）。
 */
internal object AutoStartUiGate {
    fun accepts(
        attached: Boolean,
        hasView: Boolean,
        activityFinishing: Boolean,
        activityDestroyed: Boolean,
        viewStarted: Boolean,
        emitted: AutoStartSync.State,
        current: AutoStartSync.State,
    ): Boolean = attached && hasView && !activityFinishing && !activityDestroyed &&
        viewStarted && emitted == current
}
