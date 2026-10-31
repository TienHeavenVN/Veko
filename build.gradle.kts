// Top-level Gradle Kotlin DSL — Khai báo plugin chung cho kiến trúc 9 module của Veko Browser (AGP 9 tích hợp sẵn Kotlin)
plugins {
    id("com.android.application") version "9.1.1" apply false
    id("com.android.library") version "9.1.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
    id("com.google.dagger.hilt.android") version "2.59.2" apply false
    id("com.google.devtools.ksp") version "2.3.6" apply false
}

subprojects {
    tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
        dependsOn(":chromium-native:buildHostChromiumNativeGn")
        val hostNativeDir = rootProject.file("out/host_x64").absolutePath
        systemProperty("veko.native.lib.dir", hostNativeDir)
        systemProperty(
            "java.library.path",
            listOfNotNull(hostNativeDir, System.getProperty("java.library.path")).joinToString(java.io.File.pathSeparator)
        )
    }
}
