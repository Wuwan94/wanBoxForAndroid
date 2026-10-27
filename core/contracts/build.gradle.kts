// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    // 与 app 现有版本一致，不引入新的运行时库
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
}

kotlin {
    jvmToolchain(17)
}
