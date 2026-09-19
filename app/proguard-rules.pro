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
#  org.xmlpull（xpp3 带进来的）
#
#  真正修掉这问题的是 app/build.gradle.kts 里 `exclude org.ogce:xpp3` 那几行。
#
#  ⚠️ 这里**只能写 -dontwarn，绝对不能写 -keep**。
#  `-keep class org.xmlpull.**` 会把这些类标成 keep root，等于强行把它们
#  拉进 program class —— 那正是 R8 报错的成因：
#    Library class android.content.res.XmlResourceParser
#    implements program class org.xmlpull.v1.XmlPullParser
#  我第一版就是这么写错的，结果 release 依旧失败（CI 35443572069）。
#  这类 API 是**平台提供的**，正确做法是让 R8 把它当 library class，
#  所以只需要消除「找不到类」的警告，不要 keep。
#
#  换 webdav 库或升级 dav4jvm 后，这段可以一起删掉。
# =============================================================================
-dontwarn org.xmlpull.**
