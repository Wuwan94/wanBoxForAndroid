// phase2: Macrobenchmark + Baseline Profile generator for the manager app.
// Only included when building with -PwanboxBenchmark=true (see settings.gradle.kts), so the
// default / CI / release builds are untouched. Runs on a real device (adb), never in CI here.
//   ./gradlew -PwanboxThin=true -PwanboxBenchmark=true :benchmark:connectedOssBenchmarkAndroidTest
// Baseline profile (rooted device or userdebug/emulator API 33+):
//   ./gradlew -PwanboxThin=true -PwanboxBenchmark=true :benchmark:connectedOssBenchmarkAndroidTest \
//     -Pandroid.testInstrumentationRunnerArguments.class=com.lit008834.wanbox.benchmark.BaselineProfileGenerator
//   then copy the pulled *-baseline-prof.txt to app/src/main/baseline-prof.txt
plugins {
    id("com.android.test")
    id("kotlin-android")
}

android {
    namespace = "com.lit008834.wanbox.benchmark"
    compileSdk = 36
    buildToolsVersion = "35.0.1"

    defaultConfig {
        minSdk = 31
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // Mirror the app's flavour dimension; only oss is meaningful for benchmarks.
    flavorDimensions += "vendor"
    productFlavors { create("oss") }

    buildTypes {
        // Matches the app's opt-in "benchmark" build type (non-debuggable, debug-signed).
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

androidComponents {
    beforeVariants(selector().all()) { it.enable = it.buildType == "benchmark" }
}

dependencies {
    implementation("androidx.test.ext:junit:1.1.5")
    implementation("androidx.test:runner:1.5.2")
    implementation("androidx.test.uiautomator:uiautomator:2.2.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.2.4")
}
