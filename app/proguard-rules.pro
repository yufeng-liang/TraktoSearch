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
# sqlcipher-android 4.19.1 的 AAR 自带 consumer 规则（-keep net.zetetic.database.**），
# 这里显式重复一份做冗余保护：release 走 R8 fullMode，JNI 桥接类被裁会在运行期才崩，
# 且只在 release 复现（debug 不复现），属难查 bug。
-keep class net.zetetic.database.** { *; }
-dontwarn net.zetetic.database.**

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

# OpenImage 的 `openimage-coil` 模块把 Coil2 与 Coil3 两套适配层都打进来了，
# 而本项目只提供 Coil2（io.coil-kt:coil-compose，包名 coil.*）。Coil3 适配层引用的
# coil3.* 全都不在 classpath 上，R8 按「缺类即错误」处理，assembleRelease 直接失败。
# app 代码不引用 openimagecoillib 任何类（全仓 grep 为零命中），那条路径是死代码，
# 所以按 AGP 生成的建议抑制，而不是去补一个用不上的 Coil3 依赖。
# 缺这条规则的代价不是"警告变多"：R8 会真的编不过，CI 与本机空缓存构建都会红。
#
# 同源的另一个缺类（okhttp3.internal.Util）**不能照这样抑制**：那条路径运行期真的会走到
# （本项目装 Coil2，InitProvider 探测 coil3 失败后必落 Coil2 轨），dontwarn 只会把构建期
# 错误换成运行期 NoClassDefFoundError。故改为补真实实现，见
# app/src/main/java/okhttp3/internal/Util.kt（含完整来龙去脉与删除条件）。
-dontwarn coil3.**
