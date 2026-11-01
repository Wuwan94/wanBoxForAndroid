pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
    resolutionStrategy {
        eachPlugin {
            // @author 雾晚 (build-env): `kotlin-dsl` 本该由发行版内置提供，但一旦没被当作
            // 内置插件命中，Gradle 就会去仓库解析它的 marker，而门户上并没有
            // org.gradle.kotlin.kotlin-dsl 的 marker（直接 404），于是报
            //   Plugin [id: 'org.gradle.kotlin.kotlin-dsl', version: '4.5.0'] was not found
            // 实际上发布出来的只有实现模块 org.gradle.kotlin:gradle-kotlin-dsl-plugins，
            // 所以这里把 id 映射到实现模块。内置插件正常命中时本分支不会触发，无副作用。
            if (requested.id.id == "org.gradle.kotlin.kotlin-dsl") {
                useModule("org.gradle.kotlin:gradle-kotlin-dsl-plugins:${requested.version}")
            }
        }
    }
}
