pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        // 仓库顺序说明：Gradle 对每个模块绑定「第一个提供元数据的仓库」，跨仓库不回退补文件，
        // 因此主源必须覆盖全部构件。官方主站在本网络对部分构件返回 403，主源用阿里云；
        // 腾讯云/官方作冗余。若镜像漏构件（如阿里云缺 haze -android RC 的 aar，已预铺缓存），
        // 从有该构件的源下载后预铺进 Gradle 缓存即可。
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
        maven { url = uri("https://repo.maven.apache.org/maven2/") }
        gradlePluginPortal()
        maven { url = uri("https://developer.huawei.com/repo/") }
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // 仓库顺序说明：Gradle 对每个模块绑定「第一个提供元数据的仓库」，跨仓库不回退补文件，
        // 因此主源必须覆盖全部构件。官方主站在本网络对部分构件返回 403，主源用阿里云；
        // 腾讯云/官方作冗余。若镜像漏构件（如阿里云缺 haze -android RC 的 aar，已预铺缓存），
        // 从有该构件的源下载后预铺进 Gradle 缓存即可。
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
        maven { url = uri("https://repo.maven.apache.org/maven2/") }
        maven { url = uri("https://developer.huawei.com/repo/") }
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "TraktoSearch"
include(":app")
// Macrobenchmark 模块：release 构建上测启动/帧耗时，并生成 baseline + startup profile。
// 只在显式跑 :benchmark 的任务时参与构建，不影响 :app 的日常 assembleDebug。
include(":benchmark")
