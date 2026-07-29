# Keep attributes for crash reporting
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep all project code (IPC services, AIDL stubs, ViewBinding, Room, serializers)
-keep class org.fcitx.fcitx5.android.plugin.quicksend.** { *; }

# Keep AIDL IPC interfaces (shared package with host, outside plugin.**)
-keep class org.fcitx.fcitx5.android.common.ipc.** { *; }

# kotlinx.serialization: keep serializer annotations and generated serializer classes
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class org.fcitx.fcitx5.android.plugin.quicksend.**$$serializer { *; }

# OkHttp (model download; uses reflection internally)
-dontwarn okhttp3.internal.platform.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
