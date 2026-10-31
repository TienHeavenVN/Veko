import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

val generatedJniLibsDir = layout.buildDirectory.dir("generated/jniLibs")

android {
    namespace = "io.veko.browser.chromium.nativebridge"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir(generatedJniLibsDir)
        }
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

// Tác vụ biên dịch C++20 + Rust adblock-rust bằng GN + Ninja cho 3 kiến trúc Android ABI trước khi đóng gói jniLibs
val buildAndroidChromiumNativeGn = tasks.register<Exec>("buildAndroidChromiumNativeGn") {
    group = "build"
    description = "Builds libveko_chromium_native.so + Rust adblock FFI via GN + Ninja for arm64-v8a, armeabi-v7a, x86_64."
    workingDir = rootProject.projectDir
    inputs.dir(rootProject.file("gn"))
    inputs.dir(rootProject.file("chromium-native/src/main/cpp"))
    inputs.dir(rootProject.file("privacy/rust/veko-adblock"))
    inputs.file(rootProject.file("scripts/build-native-gn.sh"))
    outputs.dir(generatedJniLibsDir)
    commandLine(
        "bash",
        rootProject.file("scripts/build-native-gn.sh").absolutePath,
        "--android-out",
        generatedJniLibsDir.get().asFile.absolutePath,
    )
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    dependsOn(buildAndroidChromiumNativeGn)
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
    testImplementation("junit:junit:4.13.2")
}
