// @author 雾晚
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.gradle.AbstractAppExtension
import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import org.gradle.api.JavaVersion
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByName
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.Base64
import java.util.Properties
import kotlin.system.exitProcess

private val Project.android get() = extensions.getByName<ApplicationExtension>("android")

private lateinit var metadata: Properties
private lateinit var localProperties: Properties

// 版本码规则的唯一来源是 tools/version_code.py；这里是构建期复刻，
// tools/test_version_code.py 会校验乘数与预览序号范围一致；每次 Gradle 配置还会用
// tools/version_code_vectors.txt 的固定向量校验下面函数的实际返回值，禁止只改一边。
private val PREVIEW_VERSION_SUFFIX = Regex("-preview\\.([1-4])$")

fun apkVersionCode(code: Int, versionName: String): Int {
    // 与 version_code.py 的 parse_code 一致：正整数，且 ×5 + 4 仍在 Android int 范围内。
    require(code > 0) { "Metadata version code must be a positive integer: $code" }
    require(code.toLong() * 5 + 4 <= Int.MAX_VALUE) { "Metadata version code overflows versionCode: $code" }
    val preview = PREVIEW_VERSION_SUFFIX.find(versionName)
    require(!versionName.contains("-preview") || preview != null) {
        "Preview version must end in -preview.1 through -preview.4: $versionName"
    }
    return code * 5 + (preview?.groupValues?.get(1)?.toInt() ?: 0)
}

/**
 * 用 tools/version_code_vectors.txt 的固定向量校验 [apkVersionCode] 的实际行为，
 * 返回不符的向量说明；Python 规则的单测读取同一文件。 @author 雾晚
 */
fun versionCodeVectorMismatches(vectors: String): List<String> = vectors.lineSequence()
    .map { it.trim() }
    .filter { it.isNotEmpty() && !it.startsWith("#") }
    .mapNotNull { line ->
        val parts = line.split("|")
        if (parts.size != 3) return@mapNotNull "malformed vector: $line"
        val code = parts[0].trim().toIntOrNull() ?: return@mapNotNull "malformed code: $line"
        val actual = runCatching { apkVersionCode(code, parts[1].trim()) }
            .fold({ it.toString() }, { "reject" })
        if (actual == parts[2].trim()) null else "$line -> $actual"
    }
    .toList()

private fun Project.verifyVersionCodeRule() {
    val file = rootProject.file("tools/version_code_vectors.txt")
    if (!file.isFile) throw GradleException("Missing ${file.path}: version-code rule cannot be verified")
    val mismatches = versionCodeVectorMismatches(file.readText())
    if (mismatches.isNotEmpty()) {
        throw GradleException(
            "Kotlin apkVersionCode drifted from tools/version_code.py:\n" + mismatches.joinToString("\n")
        )
    }
}

fun Project.requireMetadata(): Properties {
    if (!::metadata.isInitialized) {
        verifyVersionCodeRule()
        metadata = Properties().apply {
            load(rootProject.file("nb4a.properties").inputStream())
        }
    }
    return metadata
}

fun Project.requireLocalProperties(): Properties {
    if (!::localProperties.isInitialized) {
        localProperties = Properties()

        val base64 = System.getenv("LOCAL_PROPERTIES")
        if (!base64.isNullOrBlank()) {

            localProperties.load(Base64.getDecoder().decode(base64).inputStream())
        } else if (project.rootProject.file("local.properties").exists()) {
            localProperties.load(rootProject.file("local.properties").inputStream())
        }
    }
    return localProperties
}

fun Project.setupCommon() {
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_1_8)
    }
    android.apply {
        buildToolsVersion = "35.0.1"
        compileSdk = 36
        defaultConfig {
            // @author 雾晚: product support remains Android 12+, independently of visual effects.
            minSdk = 31
            targetSdk = 35
        }
        buildTypes {
            getByName("release") {
                isMinifyEnabled = true
            }
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }
        lint {
            showAll = true
            checkAllWarnings = true
            checkReleaseBuilds = true
            warningsAsErrors = true
            textOutput = project.file("build/lint.txt")
            htmlOutput = project.file("build/lint.html")
        }
        packaging {
            resources.excludes.addAll(
                listOf(
                    "**/*.kotlin_*",
                    "/META-INF/*.version",
                    "/META-INF/native/**",
                    "/META-INF/native-image/**",
                    "/META-INF/INDEX.LIST",
                    "DebugProbesKt.bin",
                    "com/**",
                    "org/**",
                    "**/*.java",
                    "**/*.proto",
                    "okhttp3/**"
                )
            )
        }
        (this as? AbstractAppExtension)?.apply {
            buildTypes {
                getByName("release") {
                    isShrinkResources = true
                    if (System.getenv("nkmr_minify") == "0") {
                        isShrinkResources = false
                        isMinifyEnabled = false
                    }
                }
                getByName("debug") {
                    applicationIdSuffix = "debug"
                    debuggable(true)
                    jniDebuggable(true)
                }
            }
        }
    }
}

fun Project.setupAppCommon() {
    setupCommon()

    val lp = requireLocalProperties()
    val keystorePath = lp.getProperty("WANBOX_KEYSTORE_FILE") ?: System.getenv("WANBOX_KEYSTORE_FILE")
    val keystorePassword = lp.getProperty("WANBOX_KEYSTORE_PASSWORD") ?: System.getenv("WANBOX_KEYSTORE_PASSWORD")
    val releaseRequested = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }

    if (releaseRequested && (keystorePath.isNullOrBlank() || keystorePassword.isNullOrBlank())) {
        throw GradleException("Release signing requires WANBOX_KEYSTORE_FILE and WANBOX_KEYSTORE_PASSWORD")
    }
    if (!keystorePath.isNullOrBlank() && !keystorePassword.isNullOrBlank()) {
        val keystore = file(keystorePath)
        if (!keystore.isFile) {
            throw GradleException("Release keystore does not exist: $keystorePath")
        }
        android.apply {
            signingConfigs {
                create("release") {
                    storeFile = keystore
                    storePassword = keystorePassword
                    keyAlias = "wanboxforandroid"
                    keyPassword = keystorePassword
                    storeType = "pkcs12"
                }
            }
            buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
        }
    }
}

/** Public builds are ARM64; CI can explicitly build an unpublished emulator fixture. @author 雾晚 */
val Project.wanboxAbi: String
    get() = providers.gradleProperty("wanboxInternalTestAbi").orNull?.also {
        require(it == "x86_64" && System.getenv("WANBOX_INTERNAL_TEST") == "1") {
            "wanboxInternalTestAbi is restricted to the internal x86_64 verification job"
        }
    } ?: "arm64-v8a"

fun Project.setupApp() {
    val pkgName = requireMetadata().getProperty("PACKAGE_NAME")
    require(pkgName == "com.lit008834.pixel.wanboxforandroid") {
        "wanBoxForAndroid releases must use the stable applicationId: $pkgName"
    }
    val verName = requireMetadata().getProperty("VERSION_NAME")
    val verCode = apkVersionCode(requireMetadata().getProperty("VERSION_CODE").toInt(), verName)
    android.apply {
        defaultConfig {
            applicationId = pkgName
            versionCode = verCode
            versionName = verName
            buildConfigField("String", "PRE_VERSION_NAME", "\"\"")
        }
    }
    setupAppCommon()

    android.apply {
        this as AbstractAppExtension

        buildTypes {
            getByName("release") {
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    file("proguard-rules.pro")
                )
            }
        }

        splits.abi {
            reset()
            isEnable = true
            isUniversalApk = false
            include(wanboxAbi)
        }

        flavorDimensions += "vendor"
        productFlavors {
            create("oss")
            create("fdroid")
            create("play")
            create("preview") {
                val previewName = requireMetadata().getProperty("PRE_VERSION_NAME")
                versionName = previewName
                versionCode = apkVersionCode(
                    requireMetadata().getProperty("PRE_VERSION_CODE").toInt(), previewName
                )
                buildConfigField(
                    "String",
                    "PRE_VERSION_NAME",
                    "\"${requireMetadata().getProperty("PRE_VERSION_NAME")}\""
                )
            }
        }

        applicationVariants.all {
            outputs.all {
                this as BaseVariantOutputImpl
                val isPreview = outputFileName.contains("-preview")
                outputFileName = if (isPreview) {
                    outputFileName.replace(
                        project.name,
                        "wanBoxForAndroid-" + requireMetadata().getProperty("PRE_VERSION_NAME")
                    ).replace("-preview-", "-")
                } else {
                    outputFileName.replace(project.name, "wanBoxForAndroid-$versionName")
                        .replace("-release", "")
                        .replace("-oss", "")
                }
            }
        }

        for (abi in listOf("Arm64", "Arm", "X64", "X86")) {
            tasks.create("assemble" + abi + "FdroidRelease") {
                dependsOn("assembleFdroidRelease")
            }
        }

        sourceSets.getByName("main").apply {
            jniLibs.srcDir("executableSo")
        }
    }
}
