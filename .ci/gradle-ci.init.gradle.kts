/*
 * CI 专用 Gradle 初始化脚本（本地不要带这个跑）。
 *
 * 为什么需要它：settings.gradle.kts 的仓库顺序把阿里云/腾讯云放在官方源前面，注释里写明了
 * 原因 —— 本机网络访问官方源对部分构件返回 403。但 Gradle 对一个模块只绑定「第一个提供
 * 元数据的仓库」，跨仓库不回退补文件；阿里云缺 haze RC 的 aar，本机是靠预铺缓存蒙过去的。
 * CI 是空缓存，照这个顺序走就会在依赖解析上直接 404。
 *
 * beforeSettings 在 settings 脚本求值之前执行，这里先塞进官方源，settings 脚本随后追加的
 * 镜像源仍在列表里 —— 本机那份顺序完全不动，只有带 -I 的 CI 生效。
 */
beforeSettings {
    pluginManagement {
        repositories {
            google()
            mavenCentral()
            gradlePluginPortal()
        }
    }
    dependencyResolutionManagement {
        repositories {
            google()
            mavenCentral()
        }
    }
}
