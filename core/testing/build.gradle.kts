// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    api(project(":core-contracts"))
}

kotlin {
    jvmToolchain(17)
}
