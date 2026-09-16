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
//  FFmpeg 后端选择
//
//  kit    （默认）使用 Maven Central 上的预编译 ffmpeg-kit AAR。
//                 不需要 NDK、不需要编译 FFmpeg，任何平台都能直接构建。
//
//  native          使用 app/src/main/cpp 下的自研 JNI 层。
//                 需要先执行 scripts/build-ffmpeg-android.sh（只能在 Linux / macOS / WSL 下跑）。
//                 切换方式：在 local.properties 或命令行加 -Pffmpegx.backend=native
//
//  两个后端在 Kotlin 侧是同一个接口（FfmpegBackend），上层代码完全不用改。
// =============================================================================

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun prop(key: String, default: String): String =
    (project.findProperty(key) as String?) ?: localProps.getProperty(key) ?: default

// =============================================================================
//  发布签名
//
//  读取顺序：根目录 keystore.properties（本地构建，已 gitignore）→ 环境变量（CI 注入）。
//  两者都没有时，release 包保持「未签名」，CI 会自动退回发布 debug 包，
//  这样没有配密钥的人（包括 fork）也能正常构建。
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

val requestedBackend = prop("ffmpegx.backend", "kit").lowercase()
val ffmpegAbis = prop("ffmpegx.abis", "arm64-v8a,armeabi-v7a,x86_64")
    .split(",").map { it.trim() }.filter { it.isNotEmpty() }

/** 自研 JNI 后端所需的预编译产物是否就绪 */
val ffmpegPrebuiltReady: Boolean = run {
    file("src/main/cpp/ffmpeg/include/libavcodec/avcodec.h").exists() &&
        file("src/main/cpp/fftools/ffmpeg.c").exists() &&
        ffmpegAbis.all { file("src/main/jniLibs/$it/libavcodec.so").exists() }
}

val useNativeBackend: Boolean = requestedBackend == "native" && ffmpegPrebuiltReady

if (requestedBackend == "native" && !ffmpegPrebuiltReady) {
    logger.lifecycle(
        """
        ┌──────────────────────────────────────────────────────────────────────┐
        │ 已请求 native 后端，但 FFmpeg 预编译产物不存在，已自动回退到 kit 后端。│
        │ 如需 native 后端，请先运行：                                        │
        │   ./scripts/build-ffmpeg-android.sh --abis ${ffmpegAbis.joinToString(",")}                     │
        └──────────────────────────────────────────────────────────────────────┘
        """.trimIndent(),
    )
}

logger.lifecycle("FFmpegX backend = ${if (useNativeBackend) "native (自研 JNI)" else "kit (ffmpeg-kit AAR)"}  abis = $ffmpegAbis")

android {
    namespace = "com.zhiwei.ffmpegx"
    compileSdk = 36

    // NDK 只在真的要编 C++ 时才声明，否则 AGP 会尝试去下载它
    if (useNativeBackend) {
        ndkVersion = "27.2.12479018"
    }

    defaultConfig {
        applicationId = "com.zhiwei.ffmpegx"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        vectorDrawables.useSupportLibrary = true

        if (useNativeBackend) {
            // 告诉 CMake 编哪些 ABI；kit 后端不编原生代码，交给下面的 splits 控制
            ndk { abiFilters += ffmpegAbis }

            externalNativeBuild {
                cmake {
                    arguments += listOf(
                        "-DANDROID_STL=c++_shared",
                        "-DCMAKE_BUILD_TYPE=Release",
                    )
                    cppFlags += listOf("-std=c++20", "-fexceptions", "-frtti", "-O2")
                    cFlags += listOf("-O2", "-fno-strict-aliasing")
                }
            }
        }

        buildConfigField(
            "String",
            "FFMPEG_BACKEND",
            "\"${if (useNativeBackend) "native" else "kit"}\"",
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

    if (useNativeBackend) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1+"
            }
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
            isJniDebuggable = useNativeBackend
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

    // 两个后端各自编译，互不污染 classpath：
    //   kit 构建时 AAR 在 classpath 上，native 构建时不在（反之亦然）
    sourceSets {
        getByName("main") {
            if (useNativeBackend) {
                java.srcDir("src/native/java")
            } else {
                java.srcDir("src/kit/java")
            }
        }
    }

    packaging {
        jniLibs {
            // 共享库按未压缩方式打包，由系统直接从 APK 加载，避免安装时解压一份副本
            useLegacyPackaging = false
            // ffmpeg-kit AAR 自带 libc++_shared.so；若同时存在其它来源则取第一个
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

    // 只有一个后端会被打进包：kit 走 AAR，native 走自研 JNI
    if (!useNativeBackend) {
        implementation(libs.ffmpeg.kit.full)
    }

    testImplementation(libs.junit)
}
