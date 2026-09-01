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

# 安全：release 构建剥离 Log.d/v/i 调用，防止 logcat 泄露敏感信息
# 保留 Log.w/e 用于线上问题排查
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# SQLCipher：保留 native 方法和 JNI 桥接类
-keep class net.sqlcipher.** { *; }
-keep class net.sqlcipher.database.** { *; }
-dontwarn net.sqlcipher.**

# sherpa-onnx：保留 JNI 桥接类（native 方法按类名反射绑定）
-keep class com.k2fsa.sherpa.onnx.** { *; }
