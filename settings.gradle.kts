pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "usbscreen"

include(":protocol")
include(":app-host")
include(":app-accessory")
// app-ios-qt 已整体并入 :app-host（选择页 →「iPad / iPhone」即为原来的调试入口）。
// 需要单独跑纯 iOS 版本时，从 git 历史恢复该模块即可。
