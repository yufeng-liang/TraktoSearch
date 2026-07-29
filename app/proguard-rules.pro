# Add project specific ProGuard rules here.
-keepattributes *Annotation*
-keep class kotlinx.serialization.** { *; }

# Keep serializable DTO classes
-keep class com.tracktosearch.data.remote.** { *; }
-keep class com.tracktosearch.data.repository.MediaType { *; }

# Keep serialization annotations
-keepclassmembers class ** {
    @kotlinx.serialization.Serializable <init>(...);
}

# Don't warn about missing classes from dependencies
-dontwarn kotlinx.serialization.**

# Retrofit 挂起函数的响应泛型需要在 R8 full mode 下保留，否则会退化为 Object。
-keep class com.tracktosearch.data.auth.GatewayResponse { *; }
-keep class com.tracktosearch.data.auth.GatewaySuccessResponse { *; }
-keepattributes SourceFile,LineNumberTable
