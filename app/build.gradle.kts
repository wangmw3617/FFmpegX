import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// =============================================================================
//  FFmpeg 核心：ffmpeg-kit-next（arthenica 官方续作）
//
//  本工程为**单后端**：只使用 ffmpeg-kit-next。原先的双后端（预编译 AAR /
//  自研 JNI 编 fftools）已移除，理由：
//    - ffmpeg-kit-next 不发布二进制，AAR 需自行构建 —— 这本身就替代了「自研编译」
//      的存在意义，两条编译链并存只会互相增加维护成本；
//    - 它的 Kotlin API 同时提供结构化进度回调与结构化 ffprobe，
//      正是原先 native 后端缺失、要靠日志正则去补的两块能力；
//    - 它内建 ffkitsaf: / ffkitmem: / ffkitstream: 协议，不再需要为 SAF 单独写
//      「复制到缓存」的兼容层。
//
//  AAR 来源：本地 Maven 仓库，路径由 settings.gradle.kts 解析。
//  构建方式见 docs/ffmpeg-kit-next-build.md。
// =============================================================================

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun prop(key: String, default: String): String =
    (project.findProperty(key) as String?) ?: localProps.getProperty(key) ?: default

val ffmpegAbis = prop("ffmpegx.abis", "arm64-v8a,armeabi-v7a,x86_64")
    .split(",").map { it.trim() }.filter { it.isNotEmpty() }

// =============================================================================
//  版本号
//
//  优先级：-Pffmpegx.versionName / gradle.properties < local.properties
//          < 环境变量 FFMPEGX_VERSION_NAME < 下面的兜底值
//
//  CI 发布时 workflow 从 tag 名取出（v1.0.3 -> 1.0.3）并通过环境变量传进来，
//  这样打 tag 就是唯一的「发版动作」，不需要再改代码。
//  versionCode 由 versionName 推导，保证单调递增（Android 要求新包 versionCode 更大）。
// =============================================================================

val fallbackVersionName = "1.0.0"
val versionNameValue = prop("ffmpegx.versionName", System.getenv("FFMPEGX_VERSION_NAME") ?: fallbackVersionName)
    .trim().removePrefix("v").ifBlank { fallbackVersionName }

// 1.0.3 -> 10003，1.2 -> 10002，1 -> 10000；负值或非法输入退回 1
val versionCodeValue: Int = run {
    val parts = versionNameValue.split('.', '-', '+')
        .map { it.takeWhile(Char::isDigit) }
        .filter { it.isNotEmpty() }
        .map { it.toIntOrNull() ?: 0 }
    val major = parts.getOrElse(0) { 0 }
    val minor = parts.getOrElse(1) { 0 }
    val patch = parts.getOrElse(2) { 0 }
    val code = major * 10_000 + minor * 100 + patch
    if (code > 0) code else 1
}

if (versionNameValue == fallbackVersionName && System.getenv("FFMPEGX_VERSION_NAME").isNullOrBlank()) {
    logger.lifecycle("未指定版本号，使用默认值 ${fallbackVersionName}（CI 发布时会由 tag 注入）")
}
logger.lifecycle("versionName = ${versionNameValue}  versionCode = ${versionCodeValue}")

// =============================================================================
//  发布签名
//
//  读取顺序：根目录 keystore.properties（本地构建，已 gitignore）→ 环境变量（CI 注入）。
//  两者都没有时，release 包保持「未签名」，这样没有配密钥的人（包括 fork）也能正常构建。
// =============================================================================

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signProp(propKey: String, envKey: String): String? =
    keystoreProps.getProperty(propKey) ?: System.getenv(envKey)

val releaseStoreFile = signProp("storeFile", "RELEASE_STORE_FILE")
val hasReleaseSigning: Boolean =
    !releaseStoreFile.isNullOrBlank() &&
        rootProject.file(releaseStoreFile).exists() &&
        !signProp("storePassword", "RELEASE_STORE_PASSWORD").isNullOrBlank() &&
        !signProp("keyAlias", "RELEASE_KEY_ALIAS").isNullOrBlank() &&
        !signProp("keyPassword", "RELEASE_KEY_PASSWORD").isNullOrBlank()

logger.lifecycle(
    if (hasReleaseSigning) {
        "Release 签名 = 已配置（${rootProject.file(releaseStoreFile!!).name}）"
    } else {
        "Release 签名 = 未配置（release 包将不签名）"
    },
)

logger.lifecycle("FFmpegX core = ffmpeg-kit-next ${libs.versions.ffmpegKitNext.get()}  abis = $ffmpegAbis")

android {
    namespace = "com.zhiwei.ffmpegx"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zhiwei.ffmpegx"
        minSdk = 24
        targetSdk = 36
        versionCode = versionCodeValue
        versionName = versionNameValue

        vectorDrawables.useSupportLibrary = true

        buildConfigField(
            "String",
            "FFMPEG_KIT_NEXT_VERSION",
            "\"${libs.versions.ffmpegKitNext.get()}\"",
        )
    }

    // 按 ABI 拆包：每个 CPU 架构单独出一个 APK（如 app-arm64-v8a-debug.apk），
    // 而不是打一个包含全部 ABI 的大包。isUniversalApk=false 表示不再额外产一个「全都有」的包。
    splits {
        abi {
            isEnable = true
            reset()
            include(*ffmpegAbis.toTypedArray())
            isUniversalApk = false
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = signProp("storePassword", "RELEASE_STORE_PASSWORD")
                keyAlias = signProp("keyAlias", "RELEASE_KEY_ALIAS")
                keyPassword = signProp("keyPassword", "RELEASE_KEY_PASSWORD")
                // APK Signature Scheme v1/v2/v3 全开：覆盖老设备，并支持密钥轮换
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // ffmpeg-kit-next 的 .so 是 release 构建的，debug 下也不开 JNI 调试
            isJniDebuggable = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // 共享库按未压缩方式打包，由系统直接从 APK 加载，避免安装时解压一份副本
            useLegacyPackaging = false
            pickFirsts += setOf("**/libc++_shared.so")
        }
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
            )
        }
    }

    androidResources {
        generateLocaleConfig = false
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // FFmpeg 核心（唯一后端）。AAR 从本地 Maven 仓库解析，
    // 传递依赖 com.arthenica:smart-exception-java 走 mavenCentral。
    implementation(libs.ffmpeg.kit.next)

    testImplementation(libs.junit)
}
