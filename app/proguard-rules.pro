# ---- 保留 JNI 入口 ----
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class com.zhiwei.ffmpegx.native.** { *; }

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

# ---- 保留行号，便于排查原生崩溃 ----
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
