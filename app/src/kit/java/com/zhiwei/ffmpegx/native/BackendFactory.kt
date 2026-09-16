package com.zhiwei.ffmpegx.native

import android.content.Context

/**
 * 后端工厂（kit 变体）。
 *
 * 这个文件只存在于 `app/src/kit/java`，与 `app/src/native/java` 下的同名函数互斥编译 ——
 * 由 app/build.gradle.kts 的 sourceSets 决定。
 */
internal fun createBackend(context: Context): FfmpegBackend = KitBackend(context)
