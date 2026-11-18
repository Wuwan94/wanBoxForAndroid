package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.BuildConfig

/**
 * 系统底层核心组件：不参与应用层规则强绑（B3/O6，借鉴 OwnBox 8.8 的
 * `SYSTEM_EXCLUDED_PACKAGES`，上游 commit d401e93）。
 *
 * 本对象是**集中目录**，不是自动过滤器：当前 `ConfigBuilder` 不按它改写
 * 用户显式规则（B3 的教训：把名单套到显式规则上，会把仅含应用自身包名的
 * 规则静默删空，CI #182 已回退），`ProfileManager` 的 Play 商店规则修复
 * 也只显式剔除两个下载器包名，不整表引用本对象。后续分类层若接入，必须以
 * 显式白名单方式使用，不得静默改写用户规则。
 *
 * 名单内组件是“不应被应用层规则强绑”的候选集合；是否生效由调用方决定。
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
