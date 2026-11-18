package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.BuildConfig

/**
 * 系统底层核心组件：不参与应用层规则强绑（B3/O6，借鉴 OwnBox 8.8 的
 * `SYSTEM_EXCLUDED_PACKAGES`，上游 commit d401e93）。
 *
 * wanBox 原先没有集中式名单：只有 `ProfileManager` 在 Play 商店规则修复里
 * 硬编码剔除两个下载器包名。本名单是唯一来源——`ConfigBuilder` 在应用路由
 * 生成时用它过滤规则包名，`ProfileManager` 的既有修复也引用同一份名单，
 * 不得再造第二份。
 *
 * 名单内组件不被应用层规则错分流：它们不进入规则的 `package_name`/`user_id`
 * 匹配，流量按后续规则与默认路由走，不会因为某条应用规则被分流进代理。
 */
object SystemExcludedPackages {

    val PACKAGES: Set<String> = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.providers.telephony",
        "com.android.providers.downloads",
        "com.android.providers.downloads.ui",
        "com.android.providers.media",
        "com.android.providers.media.module",
        "com.android.captiveportallogin",
        "com.android.networkstack",
        "com.google.android.networkstack",
        "com.android.externalstorage",
        "com.android.shell",
        "com.android.phone",
        "com.android.nfc",
        "com.android.bluetooth",
        "com.google.android.inputmethod.latin",
        "com.google.android.inputmethod.pinyin",
        // 管理器自身也不参与自身规则的强绑（与上游一致）。
        BuildConfig.APPLICATION_ID,
    )

    fun isExcluded(packageName: String): Boolean = packageName in PACKAGES

    /** 从应用层规则的包名集合里剔除系统组件；返回剔除后的集合（原集合不变）。 */
    fun exclude(packages: Set<String>): Set<String> = packages - PACKAGES
}
