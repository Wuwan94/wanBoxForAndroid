// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
dependencies {
    api(project(":core-contracts"))
    testImplementation(project(":core-testing"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.6.4")
}

kotlin {
    jvmToolchain(17)
}
