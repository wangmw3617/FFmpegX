pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

// =============================================================================
//  FFmpegKitNext 本地 Maven 仓库
//
//  ffmpeg-kit-next 只发源码、不发二进制制品，必须自己构建出 AAR。
//  构建产物默认落在 <ffmpeg-kit-next>/prebuilt/bundle-android-aar-<api>-maven。
//
//  路径解析顺序：
//    1. gradle.properties / local.properties 里的 ffmpegx.ffmpegKitNextRepo
//    2. 环境变量 FFMPEGX_FFMPEG_KIT_NEXT_REPO（CI 用）
//    3. 默认值 ../ffmpeg-kit-next/prebuilt/bundle-android-aar-24-maven
//
//  仓库不存在时不会构建失败，但依赖解析会给出明确提示（见 app/build.gradle.kts）。
// =============================================================================

val ffmpegKitNextRepo: String = run {
    val localProps = java.util.Properties().apply {
        val f = file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    // 优先级：命令行 -P / gradle.properties → local.properties → 环境变量 → 默认相对路径
    val configured = (extra.properties["ffmpegx.ffmpegKitNextRepo"] as String?)
        ?: localProps.getProperty("ffmpegx.ffmpegKitNextRepo")
        ?: System.getenv("FFMPEGX_FFMPEG_KIT_NEXT_REPO")
    val path = configured ?: "../ffmpeg-kit-next/prebuilt/bundle-android-aar-24-maven"
    // 相对路径按 settings 所在目录（仓库根）解析，避免落到 Gradle 守护进程的 cwd
    file(path).absolutePath
}

gradle.settingsEvaluated {
    logger.lifecycle("FFmpegKitNext 本地仓库 = $ffmpegKitNextRepo")
    if (!java.io.File(ffmpegKitNextRepo).exists()) {
        logger.lifecycle(
            """
            ┌────────────────────────────────────────────────────────────────────────┐
            │ 未找到 FFmpegKitNext 本地 Maven 仓库，依赖解析会失败。                  │
            │ 请先构建（需 Linux / macOS / WSL，Nix 环境）：                          │
            │   git clone https://github.com/arthenica/ffmpeg-kit-next             │
            │   ./nix-android.sh -p android-r27d --enable-gpl                       │
            │ 或设置 ffmpegx.ffmpegKitNextRepo 指向已有产物。详情见                   │
            │ docs/ffmpeg-kit-next-build.md                                         │
            └────────────────────────────────────────────────────────────────────────┘
            """.trimIndent(),
        )
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // FFmpegKitNext 的 AAR 与它生成的 POM 都从这里解析
        maven {
            url = uri(ffmpegKitNextRepo)
            // 只让这个仓库负责 ffmpeg-kit-next，避免它参与其它依赖的解析
            content { includeGroup("com.arthenica") }
        }
        // ---------------------------------------------------------------------
        // JitPack：dav4jvm 只发布到 JitPack，没有 Maven Central 制品。
        //
        // ⚠️ 为什么只限定 `com.github.bitfireAT`：
        //    JitPack 会按需**用源码现编**，任何一个坐标第一次被解析都可能
        //    触发一次远程构建（几十秒到几分钟），失败还会连带整个构建报错。
        //    用 content 过滤把它锁死在这一个 group，其余依赖仍走
        //    google / mavenCentral，不会被 JitPack 的构建延迟拖累。
        //
        // ⚠️ CI 上是单点依赖：JitPack 不可用时 `dav4jvm` 解析不到，
        //    整个 CI 会挂。缓解办法见 libs.versions.toml 里 dav4jvm 的注释。
        // ---------------------------------------------------------------------
        maven {
            url = uri("https://jitpack.io")
            content { includeGroup("com.github.bitfireAT") }
        }
    }
}

rootProject.name = "FFmpegX"
include(":app")
