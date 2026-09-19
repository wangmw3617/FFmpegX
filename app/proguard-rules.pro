# ---- Room ----
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# ---- kotlinx.serialization ----
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.zhiwei.ffmpegx.**$$serializer { *; }
-keepclassmembers class com.zhiwei.ffmpegx.** {
    *** Companion;
}
-keepclasseswithmembers class com.zhiwei.ffmpegx.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---- Hilt / Dagger ----
-dontwarn dagger.hilt.**

# =============================================================================
#  FFmpegKitNext
#
#  它的 NativeLoader 通过 System.loadLibrary + JNI 反射调用 FFmpegKitConfig 的
#  native 方法，且会话回调（CompleteCallback / LogCallback / StatisticsCallback）
#  是从 native 侧反向调上来的。这些类名与方法签名一旦被混淆就会 UnsatisfiedLinkError。
# =============================================================================

# native 方法所在类不能改名（JNI 按类名+方法名查找符号）
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class com.arthenica.ffmpegkit.** { *; }
-keep class com.arthenica.smartexception.** { *; }

# 回调接口由 native 侧持有全局引用后回调，必须整体保留
-keep class * implements com.arthenica.ffmpegkit.FFmpegSessionCompleteCallback { *; }
-keep class * implements com.arthenica.ffmpegkit.FFprobeSessionCompleteCallback { *; }
-keep class * implements com.arthenica.ffmpegkit.MediaInformationSessionCompleteCallback { *; }
-keep class * implements com.arthenica.ffmpegkit.LogCallback { *; }
-keep class * implements com.arthenica.ffmpegkit.StatisticsCallback { *; }

# 本项目自己的后端实现（会被 native 侧间接引用）
-keep class com.zhiwei.ffmpegx.native.** { *; }

# ffmpeg-kit-next 内部用 JSON 反射构造 MediaInformation
-dontwarn org.json.**

# ---- 保留行号，便于排查问题 ----
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# =============================================================================
#  org.xmlpull.v1（kxml2 带进来的，已在 build.gradle.kts 里排掉 kxml2）
#
#  真正修掉这问题的是 app/build.gradle.kts 里 `exclude kxml2` 那几行 ——
#  Android 平台自带 org.xmlpull.v1.*，两份同时进 dex 会让 release 的 R8 报：
#    Library class android.content.res.XmlResourceParser
#    implements program class org.xmlpull.v1.XmlPullParser
#
#  下面两行是**兜底**：万一将来某个依赖又把 xmlpull 拖回来，这里保证
#  R8 不会因为「库类/程序类」判定而失败，同时缺类也只是一条警告。
#  注意不能只写 -dontwarn —— 那是把错误藏起来，不是修掉。
# =============================================================================
-dontwarn org.xmlpull.**
-keep class org.xmlpull.** { *; }
