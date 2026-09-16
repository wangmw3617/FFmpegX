package com.zhiwei.ffmpegx.native

/**
 * JNI 入口声明。
 *
 * 刻意单独放一个 object 而不是塞进 [JniBackend]：
 * JNI 的符号名由「声明所在的类名」决定，独立出来可以让 C 侧的名字保持稳定、可预测。
 *
 * 注意：这里所有成员都必须是 **public**（不能是 internal）。
 * Kotlin 会给 internal 成员的名字加 `$模块名` 后缀，那样 C 侧按
 * `Java_com_zhiwei_ffmpegx_native_FfmpegJni_nativeInit` 就找不到了。
 */
object FfmpegJni {

    external fun nativeInit(): Boolean

    external fun nativeVersion(): String

    external fun nativeLoadError(): String

    external fun nativeRunFfmpeg(args: Array<String>, sink: NativeLogSink?): Int

    external fun nativeRunFfprobe(args: Array<String>, sink: NativeLogSink?): Int

    external fun nativeCancel()
}
