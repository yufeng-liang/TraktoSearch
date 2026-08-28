pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        // Maven Central 主节点（repo1.maven.org 在当前网络环境不可达，官方主节点可直连）
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
        // Maven Central 主节点（repo1.maven.org 在当前网络环境不可达，官方主节点可直连）
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
