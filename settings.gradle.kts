pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Giữ kho Mozilla có kiểm soát phạm vi trong giai đoạn chuyển tiếp trước Phase 6 (không gỡ GeckoView trước khi Chromium Content chạy hoàn chỉnh)
        maven {
            url = uri("https://maven.mozilla.org/maven2/")
            content {
                includeGroup("org.mozilla.geckoview")
            }
        }
    }
}

rootProject.name = "Veko"

include(
    ":app",
    ":browser-core",
    ":chromium-embedder",
    ":chromium-native",
    ":privacy",
    ":downloads",
    ":storage",
    ":security",
    ":testing",
)
