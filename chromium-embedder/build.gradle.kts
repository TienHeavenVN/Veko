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
    namespace = "io.veko.browser.chromium.embedder"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs", "prebuilt/jniLibs")
            assets.srcDirs("src/main/assets", "prebuilt/assets")
        }
    }

    androidResources {
        noCompress += listOf("pak", "dat", "bin")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all {
            val hostNativeSo = rootProject.file("out/host_x64/libveko_chromium_native.so")
            if (hostNativeSo.isFile) {
                it.systemProperty("veko.native.lib.path", hostNativeSo.absolutePath)
            }
        }
    }
}

dependencies {
    api(project(":chromium-native"))
    // Tự động nạp các lớp Java thượng nguồn (content_java, ui_java, base_java, process_launcher_java)
    // khi gói SDK Chromium 156.0.8078.85 được nhập qua scripts/import-chromium-sdk.sh
    implementation(fileTree("prebuilt/jars") { include("*.jar") })
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation("junit:junit:4.13.2")
}
