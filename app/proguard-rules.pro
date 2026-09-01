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
# RichTap 触感 SDK：lite 版的 proguard.txt 是 0 字节，没带 consumer 规则，只能自己写。
# 下面这几条抄自同版本 NETWORK 构建自带的 consumer 规则，去掉了 network 那两行
# （lite 版里没有 com.richtap.sdk.network 包）。
#
# 必须 keep 的理由：
# 1. SDK 内部用 getDeclaredMethod 反射调用自己的类，混淆掉方法名就找不到；
# 2. android.os.DynamicEffect / android.os.HapticPlayer 是 ROM 侧共享库
#    richtap-api 的编译期桩，运行期要按原名解析到系统实现；
# 3. com.sysrichtap.haptic 是系统实现侧的包名，桩类会引用到。
-keep class com.apprichtap.haptic.** { *; }
-keep class com.sysrichtap.haptic.** { *; }
-keep class android.os.DynamicEffect { *; }
-keep class android.os.HapticPlayer { *; }
-dontwarn android.os.VibrationAttributes
-dontwarn android.os.VibrationEffect$Composition
