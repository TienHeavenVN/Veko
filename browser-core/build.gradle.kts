import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

android {
    namespace = "io.veko.browser.core.module"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all {
            val hostNativeSo = rootProject.file("out/host_x64/libveko_adblock_jni.so")
            if (hostNativeSo.isFile) {
                it.systemProperty("veko.native.lib.path", hostNativeSo.absolutePath)
            }
        }
    }
}

dependencies {
    api(project(":privacy"))
    api(project(":downloads"))
    api(project(":storage"))
    api(project(":security"))

    implementation(platform("androidx.compose:compose-bom:2026.03.01"))
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("org.json:json:20240303")
}
