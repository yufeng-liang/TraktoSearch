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

# 极光推送
-dontwarn cn.jpush.**
-keep class cn.jpush.** { *; }
-keep class * extends cn.jpush.android.service.JPushMessageReceiver { *; }
-dontwarn cn.jiguang.**
-keep class cn.jiguang.** { *; }

# 小米推送
-dontwarn com.xiaomi.push.**
-keep class com.xiaomi.push.** { *; }

# 华为推送
-ignorewarnings
-keepattributes *Annotation*
-keepattributes Exceptions
-keepattributes InnerClasses
-keepattributes Signature

# Retrofit 挂起函数的响应泛型需要在 R8 full mode 下保留，否则会退化为 Object。
-keep class com.tracktosearch.data.auth.GatewayResponse { *; }
-keep class com.tracktosearch.data.auth.GatewaySuccessResponse { *; }
-keepattributes SourceFile,LineNumberTable
-keep class com.hianalytics.android.**{*;}
-keep class com.huawei.updatesdk.**{*;}
-keep class com.huawei.hms.**{*;}

# OPPO 推送
-dontwarn com.heytalk.**
-keep class com.heytalk.** { *; }
-dontwarn com.coloros.**
-keep class com.coloros.** { *; }

# vivo 推送
-dontwarn com.vivo.push.**
-keep class com.vivo.push.** { *; }
