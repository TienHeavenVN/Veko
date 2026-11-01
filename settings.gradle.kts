pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

val useMavenLocalGecko = providers.gradleProperty("veko.geckoview.useMavenLocal")
    .map { it.equals("true", ignoreCase = true) }
    .getOrElse(false)

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        if (useMavenLocalGecko) {
            // Chế độ biên dịch GeckoView từ nguồn Firefox cục bộ (MozFennec publishToMavenLocal / -Dmaven.repo.local):
            // ngắt hoàn toàn kết nối tới maven.mozilla.org để không bao giờ tải AAR prebuilt.
            mavenLocal {
                content {
                    includeGroup("org.mozilla.geckoview")
                }
            }
        } else {
            // Mặc định trên máy trạm & GitHub Actions CI: kho chính thức Mozilla có kiểm soát phạm vi group
            maven {
                url = uri("https://maven.mozilla.org/maven2/")
                content {
                    includeGroup("org.mozilla.geckoview")
                }
            }
        }
    }
}

rootProject.name = "Veko"

include(
    ":app",
    ":browser-core",
    ":privacy",
    ":downloads",
    ":storage",
    ":security",
    ":testing",
)
