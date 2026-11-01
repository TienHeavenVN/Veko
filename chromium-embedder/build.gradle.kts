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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    api(project(":privacy"))
    api(project(":security"))

    // Tự động nạp các lớp Java thượng nguồn (content_java, ui_java, base_java, process_launcher_java)
    // khi gói SDK Chromium 156.0.8078.85 được nhập qua scripts/import-chromium-sdk.sh
    implementation(fileTree("prebuilt/jars") { include("*.jar") })

    implementation(platform("androidx.compose:compose-bom:2026.03.01"))
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
