// Top-level build file where you can add configuration options common to all sub-projects/modules.
allprojects {
    apply(from = "${rootProject.projectDir}/repositories.gradle.kts")
}

tasks.register<Delete>("clean") {
    delete(rootProject.buildDir)
}

plugins {
    id("com.google.devtools.ksp") version "2.0.21-1.0.27" apply false
}

// @author 雾晚: core 三个模块是纯 JVM，插件由 buildSrc 的 classpath 提供，显式 apply 避免版本解析冲突。
subprojects {
    if (name == "core-contracts" || name == "core-domain" || name == "core-testing") {
        apply(plugin = "java-library")
        apply(plugin = "org.jetbrains.kotlin.jvm")
    }
}
