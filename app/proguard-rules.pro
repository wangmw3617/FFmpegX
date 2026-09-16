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
